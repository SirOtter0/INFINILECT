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
    data class Ready(val chapter: EpubChapter, val spineIndex: Int, val initialPosition: Pair<Int, Int>, val ticket: Long) : EpubReaderState
    data class Error(val message: String) : EpubReaderState
}

/** Session owns document and parser work. UI-thread commands; all parsing/storage is off UI.
 * One navigation job, two parsed chapters retained; stale callbacks cannot report/save.
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
    private val mutableProgression = MutableStateFlow(0.0)
    val progression = mutableProgression.asStateFlow()
    private val mutableSettings = MutableStateFlow(EpubReaderSettings())
    val settings = mutableSettings.asStateFlow()
    val settingsSaveFailed = preferences?.saveFailed ?: MutableStateFlow(false).asStateFlow()
    private var settingsLease = 0L
    val media = EpubMediaController(document, scope)
    var toc: List<EpubTocEntry> = emptyList(); private set
    private val parseMutex = Mutex()
    private val cache = linkedMapOf<EpubEntryPath, EpubChapter>()
    private var request: Job? = null
    private var timer: Job? = null
    private var generation = 0L
    private var closed = false
    private var pending: ReadingProgress? = null
    private var lastLocator: ReadingLocator.Epub? = null
    private var lastTimestamp = 0L
    internal val retainedChapters get() = cache.size

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
        val chapter = parser.chapter(document, path)
        currentCoroutineContext().ensureActive()
        check(!closed)
        cache[path] = chapter
        val index = paths.indexOf(path)
        val position = chapter.locate(saved?.takeIf { it.spinePath == path })
        lastLocator = chapter.locator(position.first, position.second)
        mutableProgression.value = (index + lastLocator!!.chapterProgression) / paths.size
        mutableState.value = EpubReaderState.Ready(chapter, index, position, ++generation)
    }

    fun navigate(target: EpubTarget) {
        if (closed || target.path !in paths) return
        flush()
        media.reset()
        val ticket = ++generation
        request?.cancel()
        mutableState.value = EpubReaderState.Loading
        request = scope.launch {
            try {
                withTimeout(15_000) {
                    val chapter = cache[target.path] ?: parseMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        check(!closed)
                        parser.chapter(document, target.path)
                    }
                    currentCoroutineContext().ensureActive()
                    if (closed || ticket != generation) return@withTimeout
                    val position = if (target.anchor == null) 0 to 0 else {
                        val anchor = chapter.anchors[target.anchor] ?: throw IllegalArgumentException()
                        chapter.locate(ReadingLocator.Epub(chapter.path, anchor.elementPath, anchor.codePointOffset.toLong(), 0.0))
                    }
                    cache.remove(target.path); cache[target.path] = chapter
                    while (cache.size > 2) cache.remove(cache.keys.first())
                    val index = paths.indexOf(target.path)
                    mutableState.value = EpubReaderState.Ready(chapter, index, position, ticket)
                    report(ticket, position.first, position.second)
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (!closed && ticket == generation) mutableState.value = EpubReaderState.Error("Opening this EPUB chapter timed out.")
            } catch (error: CancellationException) { throw error }
            catch (error: EpubException) {
                currentCoroutineContext().ensureActive()
                if (!closed && ticket == generation) mutableState.value = EpubReaderState.Error(error.failure.userMessage)
            }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (!closed && ticket == generation) mutableState.value = EpubReaderState.Error("This EPUB chapter or internal link is unsupported or unavailable.")
            }
        }
    }
    fun chapter(index: Int) { paths.getOrNull(index)?.let { navigate(EpubTarget(it)) } }
    fun visibleMedia(ticket: Long, images: List<EpubImage>) {
        val ready = state.value as? EpubReaderState.Ready ?: return
        if (!closed && ready.ticket == ticket) media.visible(images)
    }
    fun report(ticket: Long, block: Int, localOffset: Int) {
        val ready = state.value as? EpubReaderState.Ready ?: return
        if (closed || ready.ticket != ticket) return
        val locator = ready.chapter.locator(block, localOffset)
        val progression = ((ready.spineIndex + locator.chapterProgression) / paths.size).coerceIn(0.0, 1.0)
        mutableProgression.value = progression
        if (lastLocator == locator) return
        lastLocator = locator
        if (persistence == null) return
        lastTimestamp = maxOf(persistence.clock().coerceAtLeast(0), if (lastTimestamp == Long.MAX_VALUE) lastTimestamp else lastTimestamp + 1)
        pending = ReadingProgress(progressId, locator, progression, lastTimestamp)
        if (timer?.isActive != true) timer = scope.launch { delay(PROGRESS_SAVE_INTERVAL_MILLIS); flush() }
    }
    fun flush() { timer?.cancel(); timer = null; pending?.let { persistence?.submit(it) }; pending = null }
    /** Layout changes invalidate old callbacks and restore the latest semantic position. */
    fun presentationChanged(settings: EpubReaderSettings = this.settings.value) {
        if (closed) return
        if (settings != mutableSettings.value) preferences?.submit(settingsLease, settings)
        mutableSettings.value = settings
        val ready = state.value as? EpubReaderState.Ready ?: return
        flush()
        mutableState.value = ready.copy(initialPosition = ready.chapter.locate(lastLocator), ticket = ++generation)
    }
    fun close() {
        if (closed) return
        closed = true; generation++; request?.cancel(); flush(); preferences?.flush(); media.close(); cache.clear(); toc = emptyList(); mutableState.value = EpubReaderState.Loading; document.close()
    }
}
