// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.reader.PROGRESS_SAVE_INTERVAL_MILLIS
import org.infinilect.core.*
import org.infinilect.app.reader.EpubException

internal sealed interface EpubReaderState {
    data object Loading : EpubReaderState
    data class Ready(val chapter: EpubChapter, val spineIndex: Int, val initialPosition: Pair<Int, Int>, val ticket: Long, val presentation: Long = ticket, val error: String? = null) : EpubReaderState
    data class Error(val message: String) : EpubReaderState
}

/** Session owns document and parser work. UI-thread commands; all parsing/storage is off UI.
 * One serialized parse, two semantic windows retained; stale callbacks cannot report/save.
 */
internal class EpubReaderController(
    val document: EpubDocument,
    val progressId: ReadingProgressId,
    private val scope: CoroutineScope,
    private val parser: EpubParser = defaultEpubParser(),
    private val persistence: ProgressPersistence? = null,
    private val preferences: EpubSettingsPersistence? = null,
) {
    private val paths = document.spine.map { spine -> document.manifest.single { it.id == spine.itemId }.path }
    private val mutableState = MutableStateFlow<EpubReaderState>(EpubReaderState.Loading)
    val state = mutableState.asStateFlow()
    // A rendering snapshot of the last ready model, not another position owner. Cold
    // navigation/errors never remove readable content; callbacks still validate state.ticket.
    private val mutableDisplayed = MutableStateFlow<EpubReaderState.Ready?>(null)
    val displayed = mutableDisplayed.asStateFlow()
    private val mutableLoading = MutableStateFlow(false)
    val loading = mutableLoading.asStateFlow()
    private fun publish(ready: EpubReaderState.Ready) { mutableState.value = ready; mutableDisplayed.value = ready }
    private val mutableProgression = MutableStateFlow(0.0)
    val progression = mutableProgression.asStateFlow()
    private val mutableSettings = MutableStateFlow(EpubReaderSettings())
    val settings = mutableSettings.asStateFlow()
    val settingsSaveFailed = preferences?.saveFailed ?: MutableStateFlow(false).asStateFlow()
    private var settingsLease = 0L
    val media = EpubMediaController(document, scope)
    var toc: List<EpubTocEntry> = emptyList(); private set
    private val parseMutex = Mutex()
    private val cache = linkedMapOf<Pair<EpubEntryPath, Int>, EpubChapter>()
    private var visibleBlock = 0
    private var presentedEpoch = -1L
    private var scrollDirection = 1
    private var failedBuffer: Pair<EpubEntryPath, Int>? = null
    private data class Destination(val path: EpubEntryPath, val query: EpubWindowRequest, val atEnd: Boolean = false)
    private data class Failed(val destination: Destination, val navigation: Boolean, val withinChapter: Boolean)
    private var failed: Failed? = null
    val canRetry get() = failed != null && !closed
    private var workGeneration = 0L
    private var destination: Destination? = null
    private var navigating = false
    private var within = false
    private var request: Job? = null
    private var timer: Job? = null
    private var generation = 0L
    private var closed = false
    private var pending: ReadingProgress? = null
    private var lastLocator: ReadingLocator.Epub? = null
    private var lastTimestamp = 0L
    internal val retainedChapters get() = cache.size
    internal val retainedBlocks get() = cache.values.sumOf { it.blocks.size }
    internal val retainedTextUnits get() = cache.values.sumOf { window -> window.blocks.sumOf { it.text.length } }

    suspend fun initialize(restored: ReadingProgress?) {
        preferences?.awaitLoaded()
        currentCoroutineContext().ensureActive()
        check(!closed)
        preferences?.let { settingsLease = it.claimReader(); mutableSettings.value = it.settings.value }
        toc = parser.toc(document)
        currentCoroutineContext().ensureActive()
        check(!closed)
        val saved = restored?.takeIf { it.id == progressId }?.locator as? ReadingLocator.Epub
        lastTimestamp = restored?.takeIf { it.id == progressId }?.updatedAtEpochMillis ?: 0
        val path = saved?.spinePath?.takeIf { it in paths } ?: paths.first()
        val chapter = parse(path, saved?.takeIf { it.spinePath == path }?.let { EpubWindowRequest.Locator(it) } ?: EpubWindowRequest.Block(0))
        currentCoroutineContext().ensureActive()
        check(!closed)
        cache[path to chapter.startBlock] = chapter
        val index = paths.indexOf(path)
        val position = chapter.locate(saved?.takeIf { it.spinePath == path })
        lastLocator = chapter.locator(position.first, position.second)
        mutableProgression.value = (index + lastLocator!!.chapterProgression) / paths.size
        visibleBlock = chapter.startBlock + position.first
        publish(EpubReaderState.Ready(chapter, index, position, ++generation))
        buffer(1)
    }

    private fun cached(path: EpubEntryPath, query: EpubWindowRequest): EpubChapter? = cache.values.filter { chapter ->
        if (chapter.path != path) false else when (query) {
            is EpubWindowRequest.Block -> query.index in chapter.startBlock until chapter.endBlock
            EpubWindowRequest.End -> chapter.endBlock == chapter.totalBlocks
            is EpubWindowRequest.Locator -> chapter.blocks.any { it.elementPath == query.value.elementPath && query.value.codePointOffset in it.startOffset.toLong()..(it.startOffset + it.codePoints).toLong() }
            is EpubWindowRequest.Anchor -> chapter.anchors[query.value]?.let { anchor -> chapter.blocks.any { it.elementPath == anchor.elementPath && anchor.codePointOffset in it.startOffset..it.startOffset + it.codePoints } } == true
        }
    }.maxByOrNull { it.startBlock }
    private suspend fun parse(path: EpubEntryPath, query: EpubWindowRequest): EpubChapter = cached(path, query) ?: parseMutex.withLock {
        currentCoroutineContext().ensureActive(); check(!closed)
        parser.window(document, path, query).also { window ->
            if (window.blocks.size > EpubWindowPolicy.BLOCKS || window.blocks.sumOf { it.text.length } > EpubWindowPolicy.TEXT_UNITS || window.blocks.sumOf { it.runs.size } > EpubWindowPolicy.APPEND_EVENTS || window.blocks.any { it.text.length > 8192 } || window.anchors.size > 4096)
                throw EpubException(org.infinilect.app.reader.EpubFailure.LIMIT)
            if (window.path != path || window.blocks.isEmpty() || window.startBlock < 0 || window.endBlock > window.totalBlocks)
                throw EpubException(org.infinilect.app.reader.EpubFailure.INVALID)
        }
    }
    private fun retain(chapter: EpubChapter) {
        val key = chapter.path to chapter.startBlock
        cache.remove(key); cache[key] = chapter
        while (cache.size > EpubWindowPolicy.RETAINED) cache.remove(cache.keys.first())
    }
    fun navigate(target: EpubTarget) {
        if (closed || target.path !in paths) return
        load(target.path, target.anchor?.let { EpubWindowRequest.Anchor(it) } ?: EpubWindowRequest.Block(0))
    }
    private fun load(path: EpubEntryPath, query: EpubWindowRequest, withinChapter: Boolean = false, atEnd: Boolean = false) {
        if (closed) return
        val target = Destination(path, query, atEnd)
        flush(); failedBuffer = null; failed = null
        if (request?.isActive == true && destination == target) {
            // Promote a matching lookahead. Do not discard completed scanning or start
            // the same parse again merely because the reader pressed Next repeatedly.
            navigating = true; within = withinChapter; mutableLoading.value = true
            if (!withinChapter) mutableState.value = EpubReaderState.Loading
            return
        }
        request?.cancel(); request = null; destination = null; ++workGeneration
        cached(path, query)?.let { complete(target, it); return }
        start(target, navigation = true, withinChapter = withinChapter)
    }
    private fun complete(target: Destination, chapter: EpubChapter) {
        val position = when (val query = target.query) {
            is EpubWindowRequest.Locator -> chapter.locate(query.value)
            is EpubWindowRequest.Anchor -> {
                val anchor = chapter.anchors[query.value] ?: throw EpubException(org.infinilect.app.reader.EpubFailure.INVALID)
                chapter.locate(ReadingLocator.Epub(target.path, anchor.elementPath, anchor.codePointOffset.toLong(), 0.0))
            }
            EpubWindowRequest.End -> chapter.blocks.lastIndex to chapter.blocks.last().codePoints
            is EpubWindowRequest.Block -> (query.index - chapter.startBlock).coerceIn(chapter.blocks.indices) to if (target.atEnd) chapter.blocks.last().codePoints else 0
        }
        media.reset(); retain(chapter); visibleBlock = chapter.startBlock + position.first
        mutableLoading.value = false
        publish(EpubReaderState.Ready(chapter, paths.indexOf(target.path), position, ++generation))
        // Existing small-chapter contract. Long-window targets still require the
        // measured/restored Compose acknowledgement; requests/decodes never save them.
        if (chapter.startBlock == 0 && chapter.endBlock == chapter.totalBlocks) report(generation, position.first, position.second)
    }
    private fun start(target: Destination, navigation: Boolean, withinChapter: Boolean = true) {
        val work = ++workGeneration
        destination = target; navigating = navigation; within = withinChapter
        mutableLoading.value = navigation
        if (navigation && !withinChapter) mutableState.value = EpubReaderState.Loading
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(15_000) {
                    val incoming = parse(target.path, target.query)
                    currentCoroutineContext().ensureActive()
                    if (closed || work != workGeneration) return@withTimeout
                    if (navigating) complete(target, incoming) else append(incoming)
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive(); failure(work, target, "Opening this EPUB chapter timed out.")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure(work, target, (error as? EpubException)?.failure?.userMessage ?: "This EPUB chapter or internal link is unsupported or unavailable.")
            } finally {
                if (!closed && work == workGeneration) {
                    val lookahead = !navigating
                    request = null; destination = null; navigating = false; mutableLoading.value = false
                    if (lookahead) buffer(scrollDirection)
                }
            }
        }
        request = job; job.start()
    }
    private fun failure(work: Long, target: Destination, message: String) {
        if (closed || work != workGeneration) return
        failed = Failed(target, navigating, within)
        if (!navigating) failedBuffer = target.path to ((target.query as? EpubWindowRequest.Block)?.index ?: -1)
        val previous = (state.value as? EpubReaderState.Ready) ?: displayed.value
        if (!navigating || within) previous?.let {
            publish(it.copy(initialPosition = it.chapter.locate(liveLocator(it)), ticket = ++generation, error = message))
        } else mutableState.value = EpubReaderState.Error(message)
    }
    fun retry() {
        if (closed || request?.isActive == true) return
        val retry = failed ?: return
        failed = null; failedBuffer = null
        (state.value as? EpubReaderState.Ready)?.let { publish(it.copy(error = null)) }
        if (retry.navigation) load(retry.destination.path, retry.destination.query, retry.withinChapter, retry.destination.atEnd)
        else start(retry.destination, navigation = false)
    }
    fun chapter(index: Int) { paths.getOrNull(index)?.let { load(it, EpubWindowRequest.Block(0)) } }
    /** Measured viewport boundary only, with a current ticket. Never skip a window. */
    fun scrollBoundary(ticket: Long, forward: Boolean) {
        val ready = state.value as? EpubReaderState.Ready ?: return
        if (closed || ticket != ready.ticket) return
        val chapter = ready.chapter
        if (forward) {
            if (chapter.endBlock < chapter.totalBlocks) load(chapter.path, EpubWindowRequest.Block(chapter.endBlock), withinChapter = true)
            else chapter(ready.spineIndex + 1)
        } else {
            if (chapter.startBlock > 0) load(chapter.path, EpubWindowRequest.Block(chapter.startBlock - 1), withinChapter = true, atEnd = true)
            else paths.getOrNull(ready.spineIndex - 1)?.let { load(it, EpubWindowRequest.End) }
        }
    }
    private fun currentWindow(ready: EpubReaderState.Ready): EpubChapter = cache.values.firstOrNull {
        it.path == ready.chapter.path && visibleBlock in it.startBlock until it.endBlock
    } ?: ready.chapter
    val canPrevious get() = (state.value as? EpubReaderState.Ready)?.let { currentWindow(it).startBlock > 0 || it.spineIndex > 0 } == true
    val canNext get() = (state.value as? EpubReaderState.Ready)?.let { currentWindow(it).endBlock < it.chapter.totalBlocks || it.spineIndex + 1 < paths.size } == true
    fun next() {
        val ready = state.value as? EpubReaderState.Ready ?: return
        val current = currentWindow(ready)
        if (current.endBlock < current.totalBlocks) load(current.path, EpubWindowRequest.Block(current.endBlock), withinChapter = true)
        else chapter(ready.spineIndex + 1)
    }
    fun previous() {
        val ready = state.value as? EpubReaderState.Ready ?: return
        val current = currentWindow(ready)
        if (current.startBlock > 0) load(current.path, EpubWindowRequest.Block(current.startBlock - 1), withinChapter = true, atEnd = true)
        else chapter(ready.spineIndex - 1)
    }
    /** Called only after Compose restores/measures the target presentation. */
    fun presented(ticket: Long) {
        val ready = state.value as? EpubReaderState.Ready ?: return
        if (ready.ticket == ticket) { report(ticket, ready.initialPosition.first, ready.initialPosition.second); buffer(scrollDirection) }
    }
    /** Scroll-only lookahead. Appending/prepending preserves presentation identity and
     * stable global LazyColumn keys; the current visual paragraph stays in place. */
    private fun buffer(direction: Int) {
        val ready = state.value as? EpubReaderState.Ready ?: return
        if (closed || request?.isActive == true) return
        val current = currentWindow(ready)
        val index = if (direction >= 0) current.endBlock else current.startBlock - 1
        val target = if (index in 0 until current.totalBlocks) Destination(current.path, EpubWindowRequest.Block(index), direction < 0)
            // Preserve the short-chapter behavior. Long chapters also prepare the next
            // spine before its boundary, using the same two-window ownership budget.
            else if (current.totalBlocks > EpubWindowPolicy.BLOCKS) paths.getOrNull(ready.spineIndex + if (direction >= 0) 1 else -1)?.let {
                // Existing chapter buttons enter at the chapter beginning in either
                // direction; prepare that exact destination, not an unused End window.
                Destination(it, EpubWindowRequest.Block(0))
            } else null
        if (target == null || cached(target.path, target.query) != null || failedBuffer == target.path to ((target.query as? EpubWindowRequest.Block)?.index ?: -1)) return
        start(target, navigation = false)
    }
    private fun append(incoming: EpubChapter) {
        val latest = state.value as? EpubReaderState.Ready ?: return
        val live = currentWindow(latest)
        if (live.path == incoming.path && live.startBlock != incoming.endBlock && live.endBlock != incoming.startBlock) return
        if (live.path != incoming.path && kotlin.math.abs(paths.indexOf(live.path) - paths.indexOf(incoming.path)) != 1) return
        val locator = liveLocator(latest)
        cache.clear(); retain(live); retain(incoming)
        val combined = if (live.path == incoming.path) {
            val windows = cache.values.sortedBy { it.startBlock }
            EpubChapter(live.path, windows.flatMap { it.blocks }, live.anchors, windows.first().startBlock, live.totalBlocks, live.totalCodePoints)
        } else live
        publish(latest.copy(chapter = combined, initialPosition = combined.locate(locator), ticket = ++generation, error = null))
    }
    fun visibleMedia(ticket: Long, images: List<EpubImage>) {
        val ready = state.value as? EpubReaderState.Ready ?: return
        if (!closed && ready.ticket == ticket) media.visible(images)
    }
    fun report(ticket: Long, block: Int, localOffset: Int) {
        val ready = state.value as? EpubReaderState.Ready ?: return
        if (closed || ready.ticket != ticket || block !in ready.chapter.blocks.indices) return
        presentedEpoch = ready.presentation
        val direction = (ready.chapter.startBlock + block - visibleBlock).compareTo(0)
        visibleBlock = ready.chapter.startBlock + block
        val locator = ready.chapter.locator(block, localOffset)
        val progression = ((ready.spineIndex + locator.chapterProgression) / paths.size).coerceIn(0.0, 1.0)
        mutableProgression.value = progression
        if (direction != 0) scrollDirection = direction
        // Publish the live locator before launching lookahead: an inline parser can
        // complete immediately, and its rebase must use this newly visible passage.
        if (lastLocator != locator) {
            lastLocator = locator
            if (persistence != null) {
                lastTimestamp = maxOf(persistence.clock().coerceAtLeast(0), if (lastTimestamp == Long.MAX_VALUE) lastTimestamp else lastTimestamp + 1)
                pending = ReadingProgress(progressId, locator, progression, lastTimestamp)
                if (timer?.isActive != true) timer = scope.launch { delay(PROGRESS_SAVE_INTERVAL_MILLIS); flush() }
            }
        }
        if (request?.isActive == true && !navigating) {
            mutableLoading.value = if (scrollDirection >= 0) visibleBlock >= ready.chapter.endBlock - 8 else visibleBlock <= ready.chapter.startBlock + 7
        }
        buffer(scrollDirection)
    }
    private fun liveLocator(ready: EpubReaderState.Ready): ReadingLocator.Epub =
        lastLocator?.takeIf { presentedEpoch == ready.presentation && it.spinePath == ready.chapter.path }
            ?: ready.chapter.locator(ready.initialPosition.first, ready.initialPosition.second)
    fun flush() { timer?.cancel(); timer = null; pending?.let { persistence?.submit(it) }; pending = null }
    /** Layout changes invalidate old callbacks and restore the latest semantic position. */
    fun presentationChanged(settings: EpubReaderSettings = this.settings.value) {
        if (closed) return
        if (settings != mutableSettings.value) preferences?.submit(settingsLease, settings)
        mutableSettings.value = settings
        val ready = (state.value as? EpubReaderState.Ready) ?: displayed.value ?: return
        flush()
        // Typography/viewport changes invalidate UI callbacks, not semantic parser work.
        // A pending destination and its lookahead remain valid for the same document.
        val locator = liveLocator(ready)
        val ticket = ++generation
        val rebased = ready.copy(initialPosition = ready.chapter.locate(locator), ticket = ticket, presentation = ticket)
        if (state.value is EpubReaderState.Ready) publish(rebased) else mutableDisplayed.value = rebased
    }
    fun close() {
        if (closed) return
        closed = true; generation++; workGeneration++; request?.cancel(); destination = null; failed = null; mutableLoading.value = false; mutableDisplayed.value = null; flush(); preferences?.flush(); media.close(); cache.clear(); toc = emptyList(); mutableState.value = EpubReaderState.Loading; document.close()
    }
}
