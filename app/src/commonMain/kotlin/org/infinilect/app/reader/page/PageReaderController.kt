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
    data class Ready(val raster: Raster, val stamp: Long, val paired: Boolean = false) : PageFrame
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
 * three Single/continuous slots or four Double slots, prioritizing current and latest target. Generations reject late results.
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
    private var spreads = pageSpreads(document.pages, PageReaderSettings())
    // Exact session page/fraction. state.position is its current presentation projection.
    private var semanticPosition = PagePosition(0)
    fun spread(index: Int = state.value.position.index): PageSpread = spreads.first { index in it.indices }
    private fun neighbor(index: Int, direction: Int): Int? = spreads.getOrNull(spreads.indexOf(spread(index)) + direction)?.anchor
    private fun dragTarget(index: Int, offset: Float): Int {
        if (offset == 0f) return index
        val direction = if ((offset < 0) == (state.value.settings.mode == PageReadingMode.PAGED_LTR)) 1 else -1
        return neighbor(index, direction) ?: index
    }
    private fun paired(index: Int) = spread(index).second != null
    private fun normalize(position: PagePosition) = if (state.value.settings.layout == PageLayout.DOUBLE && pagedMode(state.value.settings.mode))
        PagePosition(spread(position.index).anchor) else position
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
        val settings = preferences?.settings?.value ?: PageReaderSettings()
        spreads = pageSpreads(document.pages, settings)
        mutableState.value = PageReaderState(position, settings, 1)
        semanticPosition = position
        mutableState.value = state.value.copy(position = normalize(position))
        loadWindow(state.value.position.index)
    }
    fun navigate(index: Int) {
        if (closed || index !in document.pages.indices) return
        if (state.value.settings.layout == PageLayout.DOUBLE && pagedMode(state.value.settings.mode)) {
            requestTurn(spread(index).anchor); return
        }
        semanticPosition = PagePosition(index)
        mutableState.value = state.value.copy(position = semanticPosition, ticket = state.value.ticket + 1, transition = null, navigationFailed = false)
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
        val target = neighbor(base, direction) ?: return
        requestTurn(target)
    }
    private fun requestTurn(target: Int) {
        val s = state.value
        val old = s.transition
        if (target == s.position.index && old == null) return
        val side = pageIncomingSide(s.position.index, target, s.settings.mode)
        val offset = old?.offset?.takeIf { side == 0 || it * side <= 0 } ?: 0f
        val ticket = s.ticket + 1
        mutableState.value = s.copy(ticket = ticket, navigationFailed = false,
            transition = PageTransition(ticket, s.position.index, target, offset,
                if (target == s.position.index) PageTransitionPhase.RETURNING else PageTransitionPhase.WAITING))
        loadWindow(s.position.index, visible = listOf(target))
    }
    /** UI may call only after a one-finger horizontal gesture exhausts actual pan bounds.
     * Uses the same identity/ticket/resource guards as fit-page dragging. */
    fun beginEdgeDrag(sourceStamp: Long?): Long? = beginDrag(1f, sourceStamp)
    fun beginDrag(zoom: Float, sourceStamp: Long?): Long? {
        if (closed || zoom != 1f || !pagedMode(state.value.settings.mode)) return null
        val s = state.value
        val source = s.frames[s.position.index] as? PageFrame.Ready ?: return null
        if (sourceStamp != source.stamp || spread().indices.any { s.frames[it] !is PageFrame.Ready }) return null
        val ticket = s.ticket + 1
        val old = s.transition
        val offset = old?.offset ?: 0f
        val target = old?.target?.takeIf { pageIncomingSide(s.position.index, it, s.settings.mode) * offset <= 0 }
            ?: dragTarget(s.position.index, offset)
        val continuation = old?.let {
            if (it.phase == PageTransitionPhase.DRAGGING) it.continuation
            else PageDragContinuation(it.target, it.phase, it.offset, s.navigationFailed)
        }
        mutableState.value = s.copy(ticket = ticket, navigationFailed = false,
            transition = PageTransition(ticket, s.position.index, target, offset, PageTransitionPhase.DRAGGING,
                continuation = continuation))
        loadWindow(s.position.index, visible = listOf(target))
        return ticket
    }
    fun drag(ticket: Long, delta: Float) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket || t.phase != PageTransitionPhase.DRAGGING || !delta.isFinite() || delta == 0f) return
        val offset = (t.offset + delta).coerceIn(-1f, 1f)
        // Grabbing an in-flight coalesced turn keeps its authoritative incoming page
        // until the finger crosses the origin; it must not flash a different neighbor.
        val target = t.target.takeIf { pageIncomingSide(t.from, it, state.value.settings.mode) * offset < 0 }
            ?: dragTarget(t.from, offset)
        mutableState.value = state.value.copy(transition = t.copy(target = target, offset = if (target == t.from) 0f else offset))
        loadWindow(t.from, visible = listOf(target))
    }
    fun releaseDrag(ticket: Long, velocity: Float) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket || t.phase != PageTransitionPhase.DRAGGING) return
        val old = t.continuation
        // A same-direction continuation does not have to requalify an already accepted turn.
        // Reversals still unwind the visual offset and use the normal completion rules.
        val continues = old != null && old.phase in listOf(PageTransitionPhase.WAITING, PageTransitionPhase.SETTLING) &&
            t.target == old.target && pageIncomingSide(t.from, t.target, state.value.settings.mode) * (t.offset - old.offset) <= 0
        mutableState.value = state.value.copy(transition = t.copy(phase =
            if (t.target != t.from && (continues || pageDragCompletes(t.offset, velocity))) PageTransitionPhase.WAITING else PageTransitionPhase.RETURNING,
            targetStamp = null, targetStamps = emptyMap(), continuation = null))
    }
    /** A touch that never established horizontal intent resumes the interrupted motion.
     * Current ticket/offset remain authoritative; conversion stamps must still validate. */
    fun resumeDrag(ticket: Long) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket || t.phase != PageTransitionPhase.DRAGGING) return
        val old = t.continuation
        if (old == null || t.target != old.target) { returnTransition(ticket); return }
        val phase = when (old.phase) {
            PageTransitionPhase.SETTLING, PageTransitionPhase.WAITING -> PageTransitionPhase.WAITING
            else -> PageTransitionPhase.RETURNING
        }
        mutableState.value = state.value.copy(transition = t.copy(phase = phase, continuation = null), navigationFailed = old.navigationFailed)
    }
    /** Only UI conversion success may arm a settle; decoder readiness/prefetch is insufficient. */
    fun transitionReady(ticket: Long, target: Int, stamp: Long) {
        if (!closed && target in document.pages.indices && spread(target).second == null) transitionReady(ticket, target, mapOf(target to stamp))
    }
    private fun validStamps(index: Int, stamps: Map<Int, Long>): Boolean =
        stamps.keys == spread(index).indices.toSet() && stamps.all { (page, stamp) ->
            (state.value.frames[page] as? PageFrame.Ready)?.stamp == stamp
        }
    fun transitionReady(ticket: Long, target: Int, stamps: Map<Int, Long>) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket || t.target != target || t.phase != PageTransitionPhase.WAITING) return
        if (validStamps(target, stamps)) mutableState.value = state.value.copy(transition =
            t.copy(phase = PageTransitionPhase.SETTLING, targetStamp = stamps[target], targetStamps = stamps.toMap()))
    }
    fun returnTransition(ticket: Long, failed: Boolean = false) {
        val t = state.value.transition ?: return
        if (closed || t.ticket != ticket) return
        mutableState.value = state.value.copy(transition = t.copy(phase = PageTransitionPhase.RETURNING, targetStamp = null, targetStamps = emptyMap(), continuation = null), navigationFailed = failed)
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
                val goal = -pageIncomingSide(t.from, t.target, s.settings.mode).toFloat()
                if (!validStamps(t.target, t.targetStamps) || kotlin.math.abs(t.offset - goal) > .001f) return
                t.target
            }
            PageTransitionPhase.RETURNING -> { if (kotlin.math.abs(t.offset) > .001f) return; t.from }
            else -> return
        }
        // Establish logical position only at rest. The new composed frame then acknowledges progress.
        if (target != t.from) semanticPosition = PagePosition(target)
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
        if (pageIndex in document.pages.indices && spread(pageIndex).second == null) presented(ticket, pageIndex, mapOf(pageIndex to stamp))
    }
    fun presented(ticket: Long, pageIndex: Int, stamps: Map<Int, Long>) {
        if (closed || state.value.transition != null || ticket != state.value.ticket || pageIndex != state.value.position.index) return
        if (validStamps(pageIndex, stamps)) changedPosition()
    }
    /** Positive horizontal swipe advances in RTL; negative advances in LTR. */
    fun swipe(right: Boolean) {
        when (state.value.settings.mode) {
            PageReadingMode.PAGED_RTL -> if (right) next() else previous()
            PageReadingMode.PAGED_LTR -> if (right) previous() else next()
            else -> Unit
        }
    }
    fun mode(mode: PageReadingMode) = settings(state.value.settings.copy(mode = mode))
    fun layout(layout: PageLayout) = settings(state.value.settings.copy(layout = layout))
    fun fit(fit: PageFit) = settings(state.value.settings.copy(fit = fit))
    /** Slider/keyboard intent uses the existing validated spatial turn, not early progress. */
    fun seek(index: Int) {
        if (closed || index !in document.pages.indices) return
        if (pagedMode(state.value.settings.mode)) requestTurn(spread(index).anchor) else navigate(index)
    }
    fun hideControls() { if (!closed) mutableState.value = state.value.copy(controlsVisible = false) }
    private fun settings(settings: PageReaderSettings) {
        if (closed || settings == state.value.settings) return
        val regroup = settings.mode != state.value.settings.mode || settings.layout != state.value.settings.layout
        preferences?.submit(settingsLease, settings)
        flush()
        if (regroup) spreads = pageSpreads(document.pages, settings)
        mutableState.value = state.value.copy(settings = settings, ticket = state.value.ticket + 1, transition = null, navigationFailed = false)
        mutableState.value = state.value.copy(position = normalize(semanticPosition))
        loadWindow(state.value.position.index, force = regroup)
    }
    /** A resized viewport restores the same semantic position and retires old layout callbacks. */
    fun presentationChanged() {
        if (closed) return
        flush()
        mutableState.value = state.value.copy(ticket = state.value.ticket + 1, transition = null)
        loadWindow(state.value.position.index)
    }
    fun report(ticket: Long, pageIndex: Int, fraction: Double, visiblePages: List<Int> = emptyList()) {
        if (closed || ticket != state.value.ticket || pageIndex !in document.pages.indices || !fraction.isFinite() ||
            state.value.settings.layout == PageLayout.DOUBLE && pagedMode(state.value.settings.mode)) return
        semanticPosition = PagePosition(pageIndex, fraction.coerceIn(0.0, 1.0))
        mutableState.value = state.value.copy(position = semanticPosition, transition = null)
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
        // at most three Single/continuous or four Double slots; no decode/map for the whole book.
        val double = state.value.settings.layout == PageLayout.DOUBLE && pagedMode(state.value.settings.mode)
        val wanted = if (double) {
            val target = visible.firstOrNull { it != center } ?: neighbor(center, 1)
            (spread(center).indices + target?.let { spread(it).indices }.orEmpty()).distinct().take(PagePolicy.SPREAD_RETAINED)
        } else (listOf(center) + visible.take(PagePolicy.RETAINED) + listOf(center - PagePolicy.PREFETCH, center + PagePolicy.PREFETCH))
            .filter { it in document.pages.indices }.distinct().take(PagePolicy.RETAINED)
        if (!force && wanted == window) return
        window = wanted
        val pairPolicy = wanted.associateWith(::paired)
        val work = ++generation
        request?.cancel()
        mutableState.value = state.value.copy(frames = wanted.associateWith { index -> state.value.frames[index]?.takeUnless { it is PageFrame.Ready && it.paired != paired(index) } ?: PageFrame.Loading })
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
                                val inPair = pairPolicy.getValue(index)
                                val raster = decoder.decodePage(bytes, page.resource.mediaType, inPair)
                                currentCoroutineContext().ensureActive()
                                require((raster.width == dimensions.first && raster.height == dimensions.second) ||
                                    inPair && raster.width in maxOf(1, dimensions.first / 2)..(dimensions.first + 1) / 2 && raster.height in maxOf(1, dimensions.second / 2)..(dimensions.second + 1) / 2)
                                PageFrame.Ready(raster, work, inPair)
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
