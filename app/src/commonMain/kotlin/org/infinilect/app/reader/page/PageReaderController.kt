// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.media.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.reader.PROGRESS_SAVE_INTERVAL_MILLIS
import org.infinilect.core.*

// A synchronous provider may finish after cancellation. Serialize across replacement
// page-reader owners too, so rapid Back/open cannot overlap abandoned native decodes.
private val pageDecodeLock = Mutex()

internal sealed interface PageFrame {
    data object Loading : PageFrame
    data class Ready(val raster: Raster, val stamp: Long) : PageFrame
    data object Unavailable : PageFrame
}
internal data class PagePosition(val index: Int, val fraction: Double = 0.0)
internal data class PageReaderState(
    val position: PagePosition = PagePosition(0),
    val settings: PageReaderSettings = PageReaderSettings(),
    val ticket: Long = 0,
    val frames: Map<Int, PageFrame> = emptyMap(),
    val controlsVisible: Boolean = false,
    val transition: PageTransition? = null,
    val navigationFailed: Boolean = false,
)

/** UI-thread commands and immutable state. One serialized acquisition/decode worker,
 * at most three slots, prioritizing current and latest target. Generations reject late results.
 * This owner knows neither source, transport, EPUB, filesystem nor platform image types. */
internal class PageReaderController(
    val document: PageDocument,
    private val scope: CoroutineScope,
    private val persistence: ProgressPersistence? = null,
    private val preferences: PageSettingsPersistence? = null,
    private val decoder: RasterDecoder = defaultRasterDecoder(),
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val progressId = document.progressId
    private val mutableState = MutableStateFlow(PageReaderState())
    val state = mutableState.asStateFlow()
    val settingsSaveFailed = preferences?.saveFailed ?: MutableStateFlow(false).asStateFlow()
    private var request: Job? = null
    private var timer: Job? = null
    private var generation = 0L
    private var window: List<Int> = emptyList()
    private var closed = false
    private var settingsLease = 0L
    private var pending: ReadingProgress? = null
    private var lastLocator: ReadingLocator.Page? = null
    private var lastTimestamp = 0L
    internal val retainedPages get() = state.value.frames.values.count { it is PageFrame.Ready }

    init {
        require(document.pages.size in 1..PagePolicy.MAX_PAGES)
        require(document.progressId.publicationId == document.publicationId && document.progressId.format == PublicationFormat.PAGES)
        require(document.pages.all { it.resource.publicationId == document.publicationId })
        require(document.pages.map { it.key }.distinct().size == document.pages.size)
    }
    suspend fun initialize(restored: ReadingProgress?) {
        preferences?.awaitLoaded()
        currentCoroutineContext().ensureActive(); check(!closed)
        val saved = restored?.takeIf { it.id == progressId }
        val locator = saved?.locator as? ReadingLocator.Page
        val byKey = document.pages.indexOfFirst { it.key == locator?.pageKey }
        val position = PagePosition(if (byKey >= 0) byKey else (locator?.pageIndex ?: 0).coerceIn(document.pages.indices), locator?.pageProgression ?: 0.0)
        lastTimestamp = saved?.updatedAtEpochMillis ?: 0
        lastLocator = locator(position)
        settingsLease = preferences?.claimReader() ?: 0
        mutableState.value = PageReaderState(position, preferences?.settings?.value ?: PageReaderSettings(), 1)
        loadWindow(position.index)
    }
    fun navigate(index: Int) {
        if (closed || index !in document.pages.indices) return
        mutableState.value = state.value.copy(position = PagePosition(index), ticket = state.value.ticket + 1, transition = null, navigationFailed = false)
        loadWindow(index)
    }
    fun next() = turn(1)
    fun previous() = turn(-1)
    private fun turn(direction: Int) {
        if (closed) return
        val s = state.value
        if (!pagedMode(s.settings.mode)) { navigate(s.position.index + direction); return }
        // Coalesce rapid requests into one latest target, never an animation queue.
        val old = s.transition
        val base = old?.takeIf { it.phase != PageTransitionPhase.RETURNING }?.target ?: s.position.index
        val target = base + direction
        if (target !in document.pages.indices) return
        val side = pageIncomingSide(s.position.index, target, s.settings.mode)
        val offset = old?.offset?.takeIf { side == 0 || it * side <= 0 } ?: 0f
        val ticket = s.ticket + 1
        mutableState.value = s.copy(ticket = ticket, navigationFailed = false,
            transition = PageTransition(ticket, s.position.index, target, offset,
                if (target == s.position.index) PageTransitionPhase.RETURNING else PageTransitionPhase.WAITING))
        loadWindow(s.position.index, visible = listOf(target))
    }
    fun beginDrag(zoom: Float, sourceStamp: Long?): Long? {
        if (closed || zoom != 1f || !pagedMode(state.value.settings.mode)) return null
        val s = state.value
        val source = s.frames[s.position.index] as? PageFrame.Ready ?: return null
        if (sourceStamp != source.stamp) return null
        val ticket = s.ticket + 1
        val old = s.transition
        val offset = old?.offset ?: 0f
        val target = old?.target?.takeIf { pageIncomingSide(s.position.index, it, s.settings.mode) * offset < 0 }
            ?: pageDragTarget(s.position.index, offset, s.settings.mode, document.pages.size)
        mutableState.value = s.copy(ticket = ticket, navigationFailed = false,
            transition = PageTransition(ticket, s.position.index, target, offset, PageTransitionPhase.DRAGGING))
        loadWindow(s.position.index, visible = listOf(target))
        return ticket
    }
    fun drag(ticket: Long, delta: Float) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket || t.phase != PageTransitionPhase.DRAGGING || !delta.isFinite()) return
        val offset = (t.offset + delta).coerceIn(-1f, 1f)
        // Grabbing an in-flight coalesced turn keeps its authoritative incoming page
        // until the finger crosses the origin; it must not flash a different neighbor.
        val target = t.target.takeIf { pageIncomingSide(t.from, it, state.value.settings.mode) * offset < 0 }
            ?: pageDragTarget(t.from, offset, state.value.settings.mode, document.pages.size)
        mutableState.value = state.value.copy(transition = t.copy(target = target, offset = if (target == t.from) 0f else offset))
        loadWindow(t.from, visible = listOf(target))
    }
    fun releaseDrag(ticket: Long, velocity: Float) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket || t.phase != PageTransitionPhase.DRAGGING) return
        mutableState.value = state.value.copy(transition = t.copy(phase =
            if (t.target != t.from && pageDragCompletes(t.offset, velocity)) PageTransitionPhase.WAITING else PageTransitionPhase.RETURNING))
    }
    /** Only UI conversion success may arm a settle; decoder readiness/prefetch is insufficient. */
    fun transitionReady(ticket: Long, target: Int, stamp: Long) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket || t.target != target || t.phase != PageTransitionPhase.WAITING) return
        val frame = state.value.frames[target] as? PageFrame.Ready ?: return
        if (frame.stamp == stamp) mutableState.value = state.value.copy(transition = t.copy(phase = PageTransitionPhase.SETTLING, targetStamp = stamp))
    }
    fun returnTransition(ticket: Long, failed: Boolean = false) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket) return
        mutableState.value = state.value.copy(transition = t.copy(phase = PageTransitionPhase.RETURNING, targetStamp = null), navigationFailed = failed)
    }
    fun transitionOffset(ticket: Long, offset: Float) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket || !offset.isFinite() ||
            (t.phase != PageTransitionPhase.SETTLING && t.phase != PageTransitionPhase.RETURNING)) return
        mutableState.value = state.value.copy(transition = t.copy(offset = offset.coerceIn(-1f, 1f)))
    }
    fun finishTransition(ticket: Long) {
        val s = state.value
        val t = s.transition ?: return
        if (closed || t.ticket != ticket) return
        val target = when (t.phase) {
            PageTransitionPhase.SETTLING -> {
                val frame = s.frames[t.target] as? PageFrame.Ready ?: return
                val goal = -pageIncomingSide(t.from, t.target, s.settings.mode).toFloat()
                if (frame.stamp != t.targetStamp || kotlin.math.abs(t.offset - goal) > .001f) return
                t.target
            }
            PageTransitionPhase.RETURNING -> { if (kotlin.math.abs(t.offset) > .001f) return; t.from }
            else -> return
        }
        // Establish logical position only at rest. The new composed frame then acknowledges progress.
        mutableState.value = s.copy(position = if (target == t.from) s.position else PagePosition(target), ticket = s.ticket + 1, transition = null)
        loadWindow(target)
    }
    fun cancelTransition() {
        if (closed || state.value.transition == null) return
        mutableState.value = state.value.copy(ticket = state.value.ticket + 1, transition = null)
        loadWindow(state.value.position.index)
    }
    fun toggleControls() {
        if (!closed) mutableState.value = state.value.copy(controlsVisible = !state.value.controlsVisible)
    }
    fun tap(fraction: Float) {
        if (closed) return
        when (pageTapAction(fraction, state.value.settings.mode)) {
            PageTapAction.PREVIOUS -> previous()
            PageTapAction.NEXT -> next()
            PageTapAction.CONTROLS -> toggleControls()
            null -> Unit
        }
    }
    /** Acknowledge an actually composed bitmap, not a navigation request or prefetch.
     * The UI's conversion, obsolete callbacks and closed owners cannot advance progress. */
    fun presented(ticket: Long, pageIndex: Int, stamp: Long) {
        if (closed || state.value.transition != null || ticket != state.value.ticket || pageIndex != state.value.position.index) return
        val frame = state.value.frames[pageIndex] as? PageFrame.Ready ?: return
        if (frame.stamp == stamp) changedPosition()
    }
    /** Positive horizontal swipe advances in RTL; negative advances in LTR. */
    fun swipe(right: Boolean) {
        when (state.value.settings.mode) {
            PageReadingMode.PAGED_RTL -> if (right) next() else previous()
            PageReadingMode.PAGED_LTR -> if (right) previous() else next()
            else -> Unit
        }
    }
    fun mode(mode: PageReadingMode) {
        if (closed || mode == state.value.settings.mode) return
        val settings = PageReaderSettings(mode)
        preferences?.submit(settingsLease, settings)
        // Invalidate callbacks from the old presentation, but retain semantic position.
        flush()
        mutableState.value = state.value.copy(settings = settings, ticket = state.value.ticket + 1, transition = null)
        loadWindow(state.value.position.index, force = true)
    }
    /** A resized viewport restores the same semantic position and retires old layout callbacks. */
    fun presentationChanged() {
        if (closed) return
        flush()
        mutableState.value = state.value.copy(ticket = state.value.ticket + 1, transition = null)
        loadWindow(state.value.position.index)
    }
    fun report(ticket: Long, pageIndex: Int, fraction: Double, visiblePages: List<Int> = emptyList()) {
        if (closed || ticket != state.value.ticket || pageIndex !in document.pages.indices || !fraction.isFinite()) return
        mutableState.value = state.value.copy(position = PagePosition(pageIndex, fraction.coerceIn(0.0, 1.0)), transition = null)
        loadWindow(pageIndex, visible = visiblePages)
    }
    /** Initial/restored viewport can request frames without claiming a new reading position. */
    fun visible(ticket: Long, pages: List<Int>) {
        if (!closed && ticket == state.value.ticket) loadWindow(state.value.position.index, visible = pages)
    }
    private fun locator(position: PagePosition) = ReadingLocator.Page(document.pages[position.index].key, position.index, position.fraction)
    private fun changedPosition() {
        val position = state.value.position
        val locator = locator(position)
        if (lastLocator == locator) return
        lastLocator = locator
        if (persistence == null) return
        lastTimestamp = maxOf(persistence.clock().coerceAtLeast(0), if (lastTimestamp == Long.MAX_VALUE) lastTimestamp else lastTimestamp + 1)
        pending = ReadingProgress(progressId, locator, ((position.index + position.fraction) / document.pages.size).coerceIn(0.0, 1.0), lastTimestamp)
        if (timer?.isActive != true) timer = scope.launch { delay(PROGRESS_SAVE_INTERVAL_MILLIS); flush() }
    }
    private fun loadWindow(center: Int, force: Boolean = false, visible: List<Int> = emptyList()) {
        // Visible pages take precedence over adjacent prefetch. Large viewports still have
        // at most three decoded slots; no job/map is allocated for all publication pages.
        val wanted = (listOf(center) + visible.take(PagePolicy.RETAINED) + listOf(center - PagePolicy.PREFETCH, center + PagePolicy.PREFETCH))
            .filter { it in document.pages.indices }.distinct().take(PagePolicy.RETAINED)
        if (!force && wanted == window) return
        window = wanted
        val work = ++generation
        request?.cancel()
        mutableState.value = state.value.copy(frames = wanted.associateWith { state.value.frames[it] ?: PageFrame.Loading })
        request = scope.launch {
            for (index in wanted) {
                if (closed || work != generation) return@launch
                if (state.value.frames[index] !is PageFrame.Loading) continue
                val frame = try {
                    withTimeout(PagePolicy.DECODE_TIMEOUT_MILLIS) {
                        pageDecodeLock.withLock {
                            currentCoroutineContext().ensureActive()
                            withContext(decodeDispatcher) {
                                val page = document.pages[index]
                                require(PagePolicy.supported(page))
                                val content = document.openPage(page)
                                val bytes = try {
                                    val expected = content.sizeBytes
                                    require(expected != null && expected in 1..RasterPolicy.ENCODED_BYTES.toLong())
                                    content.readBytes(RasterPolicy.ENCODED_BYTES).also { require(it.size.toLong() == expected) }
                                } finally { content.close() }
                                val dimensions = inspectRaster(bytes, page.resource.mediaType)
                                require(dimensions == (page.dimensions.width to page.dimensions.height))
                                val raster = decoder.decode(bytes, page.resource.mediaType)
                                currentCoroutineContext().ensureActive()
                                require(raster.width == dimensions.first && raster.height == dimensions.second)
                                PageFrame.Ready(raster, work)
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive(); PageFrame.Unavailable
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { currentCoroutineContext().ensureActive(); PageFrame.Unavailable }
                currentCoroutineContext().ensureActive()
                if (!closed && work == generation && index in window)
                    mutableState.value = state.value.copy(frames = state.value.frames + (index to frame))
            }
        }
    }
    fun flush() { timer?.cancel(); timer = null; pending?.let { persistence?.submit(it) }; pending = null; preferences?.flush() }
    fun close() {
        if (closed) return
        closed = true; generation++; request?.cancel(); flush(); window = emptyList()
        mutableState.value = state.value.copy(ticket = state.value.ticket + 1, frames = emptyMap(), transition = null)
        document.close()
    }
}
