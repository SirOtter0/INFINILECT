// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import kotlin.math.roundToInt
import org.infinilect.app.ReaderAppearance
import org.infinilect.app.ReaderAppearanceEffect
import org.infinilect.app.ui.applicationColors
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.app.media.rasterImageBitmap
import org.infinilect.core.PageEntry

internal class ComicTapOwner { var job: Job? = null }
internal class PageTransform(val taps: ComicTapOwner = ComicTapOwner()) {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var edgeTicket by mutableStateOf<Long?>(null)
    val doubleTap = ComicDoubleTap()
    var tapJob: Job?
        get() = taps.job
        set(value) { taps.job = value }
    var tapAnchor: Int? = null
    fun resetTap() { tapJob?.cancel(); tapJob = null; doubleTap.clear(); edgeTicket = null }
    fun reset() { zoom = 1f; pan = Offset.Zero; resetTap() }
}

/** Presentation only: bitmap conversion is a UI adapter, not part of page semantics. */
@Composable
internal fun PageReader(reader: PageReaderController, saveFailed: Boolean, onBack: () -> Unit, backLabel: String,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    appearanceChanged: (ReaderAppearance?) -> Unit = {},
    publicationActions: (@Composable () -> Unit)? = null,
    notices: (@Composable () -> Unit)? = null,
    controlsDark: Boolean = !MaterialTheme.colors.isLight,
) {
    val state by reader.state.collectAsState()
    val settingsFailed by reader.settingsSaveFailed.collectAsState()
    val readyFrames = state.frames.filterValues { it is PageFrame.Ready }
    val conversionFrames by rememberUpdatedState(readyFrames)
    val presented by produceState<Map<Int, PageConversion<ImageBitmap>>>(emptyMap(), reader) {
        convertPageFrames(snapshotFlow { conversionFrames.mapValues { (_, frame) -> (frame as PageFrame.Ready).stamp } },
            { conversionFrames }, ::rasterImageBitmap) { value = it }
    }
    // Never flash an obsolete frame during the coroutine/recomposition handover.
    val validConversions = presented.filter { (index, old) -> (readyFrames[index] as? PageFrame.Ready)?.stamp == old.stamp }
    val bitmaps = validConversions.filterValues { it.bitmap != null }.mapValues { it.value.bitmap!! }
    val failedConversions = validConversions.filterValues { it.bitmap == null }.keys
    val taps = remember(reader) { ComicTapOwner() }
    val transform = remember(reader, state.position.index, state.settings) { PageTransform(taps) }
    val focus = remember(reader) { FocusRequester() }
    var artworkFocused by remember { mutableStateOf(false) }
    var artworkPlaced by remember(reader) { mutableStateOf(false) }
    DisposableEffect(reader,state.settings) { onDispose { taps.job?.cancel(); taps.job=null } }
    LaunchedEffect(reader, artworkPlaced, state.position.index, state.settings, state.controlsVisible) {
        if (artworkPlaced && !state.controlsVisible) { withFrameNanos { }; focus.requestFocus() }
    }
    fun zoomBy(delta: Float) { reader.cancelTransition(); transform.edgeTicket = null; transform.zoom = (transform.zoom + delta).coerceIn(1f, 4f); transform.pan = Offset.Zero }
    MaterialTheme(colors = applicationColors(controlsDark)) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.onSurface) {
    ComicSheetHost(reader,state,transform,backHandler,publicationActions,notices,onDismissed={if(artworkPlaced)focus.requestFocus()}) { openSheet,sheetOpen ->
    ReaderAppearanceEffect(if(state.controlsVisible || sheetOpen) ReaderAppearance(controlsDark,MaterialTheme.colors.surface) else ReaderAppearance(true,COMIC_BACKGROUND),appearanceChanged)
    Box(Modifier.fillMaxSize().background(COMIC_BACKGROUND).focusRequester(focus).onPlaced { artworkPlaced=true }.onFocusChanged { artworkFocused = it.isFocused }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when {
                event.key == Key.Escape -> { if (state.controlsVisible) reader.hideControls() else onBack(); true }
                event.key == Key.F10 -> { reader.toggleControls(); true }
                sheetOpen || !artworkFocused -> false
                event.key == Key.DirectionRight && pagedMode(state.settings.mode) -> { transform.reset(); reader.tap(.9f); reader.hideControls(); true }
                event.key == Key.DirectionLeft && pagedMode(state.settings.mode) -> { transform.reset(); reader.tap(.1f); reader.hideControls(); true }
                event.key == Key.PageDown -> { transform.reset(); reader.next(); reader.hideControls(); true }
                event.key == Key.PageUp -> { transform.reset(); reader.previous(); reader.hideControls(); true }
                event.key == Key.MoveHome -> { transform.reset(); reader.seek(0); reader.hideControls(); true }
                event.key == Key.MoveEnd -> { transform.reset(); reader.seek(reader.document.pages.lastIndex); reader.hideControls(); true }
                event.key == Key.Equals && pagedMode(state.settings.mode) -> { zoomBy(.5f); true }
                event.key == Key.Minus && pagedMode(state.settings.mode) -> { zoomBy(-.5f); true }
                event.key == Key.Zero && pagedMode(state.settings.mode) -> { reader.cancelTransition(); transform.reset(); true }
                else -> false
            }
        }.focusable()) {
        val continuous = state.settings.mode == PageReadingMode.VERTICAL || state.settings.mode == PageReadingMode.WEBTOON
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (continuous) ContinuousPages(reader, state, bitmaps, Modifier.fillMaxSize())
            else PagedCanvas(reader, state, bitmaps, failedConversions, transform, Modifier.fillMaxSize())
        }
        if (state.controlsVisible) ComicChrome(reader,state,transform,onBack,backLabel,saveFailed,settingsFailed,openSheet)
        // Failures stay visible even with hidden chrome, without reserving page height.
        else if (saveFailed || settingsFailed || state.navigationFailed) Surface(Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colors.surface.copy(alpha = .94f)) {
            Text(if (state.navigationFailed) "Requested page unavailable or unsupported" else if (saveFailed) "Reading position could not be saved on this device." else "Page reader preferences could not be saved on this device.", Modifier.padding(8.dp))
        }
    }
    }
    }
    }
}

internal val COMIC_BACKGROUND = Color(0xFF080C0C)
internal fun fitLabel(fit: PageFit) = when (fit) { PageFit.SCREEN -> "Fit screen"; PageFit.WIDTH -> "Fit width"; PageFit.HEIGHT -> "Fit height" }

internal fun modeLabel(mode: PageReadingMode) = when (mode) {
    PageReadingMode.PAGED_RTL -> "Right-to-left (manga)"
    PageReadingMode.PAGED_LTR -> "Left-to-right"
    PageReadingMode.VERTICAL -> "Vertical"
    PageReadingMode.WEBTOON -> "Webtoon"
}

@Composable
internal fun PagedCanvas(reader: PageReaderController, state: PageReaderState, bitmaps: Map<Int, ImageBitmap>, failedConversions: Set<Int>, transform: PageTransform, modifier: Modifier) {
    var zoom by transform::zoom
    var pan by transform::pan
    val tapScope = rememberCoroutineScope()
    val current = reader.spread(state.scrub?.displayed ?: state.position.index)
    fun stamps(spread: PageSpread): Map<Int, Long>? = spread.indices.associateWith { index ->
        (state.frames[index] as? PageFrame.Ready)?.stamp?.takeIf { bitmaps[index] != null } ?: return null
    }
    val sourceStamp by rememberUpdatedState(stamps(current)?.get(current.anchor))
    val scrub = state.scrub
    val preview = scrub?.let { reader.spread(it.requested) }
    val previewStamps = preview?.let(::stamps)
    val previewFailed = preview?.indices?.any { state.frames[it] is PageFrame.Unavailable || it in failedConversions } == true
    LaunchedEffect(reader,state.ticket,preview?.anchor,previewStamps,previewFailed) {
        if(scrub!=null) {
            if(previewFailed)reader.cancelScrub(failed=true)
            else if(previewStamps!=null)reader.previewReady(state.ticket,preview.anchor,previewStamps)
        }
    }
    val transition = state.transition
    val target = transition?.let { reader.spread(it.target) }
    val targetStamps = target?.let(::stamps)
    val targetFailed = target?.indices?.any { state.frames[it] is PageFrame.Unavailable || it in failedConversions } == true
    LaunchedEffect(reader, transition?.ticket, transition?.phase, targetStamps, targetFailed, zoom, transform.edgeTicket) {
        val t = transition ?: return@LaunchedEffect
        if (zoom != 1f && transform.edgeTicket != t.ticket) { reader.cancelTransition(); return@LaunchedEffect }
        if ((t.phase == PageTransitionPhase.WAITING || t.phase == PageTransitionPhase.SETTLING) && targetFailed) {
            reader.returnTransition(t.ticket, failed = true); return@LaunchedEffect
        }
        when (t.phase) {
            PageTransitionPhase.WAITING -> if (targetStamps != null) reader.transitionReady(t.ticket, t.target, targetStamps)
            PageTransitionPhase.SETTLING, PageTransitionPhase.RETURNING -> {
                // Begin at the exact release offset; one effect/job, superseded by the next ticket.
                val goal = if (t.phase == PageTransitionPhase.SETTLING) -pageIncomingSide(t.from, t.target, state.settings.mode).toFloat() else 0f
                val motion = Animatable(t.offset)
                motion.animateTo(goal, tween(180)) { reader.transitionOffset(t.ticket, value) }
                reader.finishTransition(t.ticket)
                if (transform.edgeTicket == t.ticket) transform.edgeTicket = null
            }
            PageTransitionPhase.DRAGGING -> Unit
        }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = maxWidth
        val density = LocalDensity.current
        val viewport = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val fit = fitPageSpread(current.indices.map { reader.document.pages[it].dimensions }, viewport,
            with(density) { PAGE_SPREAD_GUTTER_DP.dp.toPx() }, state.settings.fit)
        val panAtFit = fit.size.width > viewport.width + .5f || fit.size.height > viewport.height + .5f
        // A resized canvas retires callbacks from the previous spatial presentation.
        DisposableEffect(reader, width, maxHeight) { transform.reset(); reader.presentationChanged(); onDispose {} }
        Box(Modifier.fillMaxSize().pointerInput(reader, transform, width, maxHeight, fit, scrub != null) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (scrub == null && event.type == PointerEventType.Scroll && event.changes.none { it.isConsumed }) {
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: Offset.Zero
                        if (zoom > 1f || panAtFit) {
                            val bounds = pagePanBounds(fit.size, viewport, zoom)
                            pan = Offset((pan.x - delta.x * 48f).coerceIn(-bounds.x, bounds.x), (pan.y - delta.y * 48f).coerceIn(-bounds.y, bounds.y))
                        } else if (delta.y != 0f) {
                            transform.reset(); if (delta.y > 0) reader.next() else reader.previous(); reader.hideControls()
                        }
                        if (delta != Offset.Zero) reader.hideControls()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }.semantics {
            onClick(label = "Toggle reader controls") { reader.toggleControls(); true }
            customActions = listOf(CustomAccessibilityAction("Previous page") { transform.reset(); reader.stepTap(-1); true },
                CustomAccessibilityAction("Next page") { transform.reset(); reader.stepTap(1); true },
                CustomAccessibilityAction("Zoom or reset") { reader.cancelTransition();transform.resetTap()
                    val next=comicDoubleTapZoom(zoom,pan,Offset(viewport.width/2,viewport.height/2),fit.size,viewport)
                    zoom=next.zoom;pan=next.pan;true })
        }.pointerInput(reader, transform, width, maxHeight, fit, scrub != null) {
          coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (scrub != null) return@awaitEachGesture
                transform.tapJob?.cancel()
                var releasedAt = down.uptimeMillis
                val velocity = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                var horizontal = 0f
                var vertical = 0f
                var multiplePointers = false
                var moved = false
                var consumed = false
                var fitDragStarted = false
                var dragTicket: Long? = null
                val edgePan = PageEdgePanGesture(viewConfiguration.touchSlop, size.width.toFloat())
                // Freeze an active presentation before slop, so settlement cannot
                // replace this gesture's source/transform midway through recognition.
                val oldEdge = reader.state.value.transition?.takeIf { zoom == 1f || transform.edgeTicket == it.ticket }
                if (oldEdge != null && pageTapAction(down.position.x / size.width, state.settings.mode) == PageTapAction.CONTROLS) {
                    dragTicket = if (zoom == 1f) reader.beginDrag(zoom, sourceStamp) else reader.beginEdgeDrag(sourceStamp)
                    if (dragTicket != null && (zoom > 1f || panAtFit)) {
                        transform.edgeTicket = dragTicket
                        edgePan.overscroll = oldEdge.offset * size.width
                    }
                }
                val edgeVelocity = PageEdgeVelocity(down.uptimeMillis, edgePan.overscroll)
                try {
                do {
                    val event = awaitPointerEvent()
                    val primary = event.changes.firstOrNull { it.id == down.id }
                    primary?.let { velocity.addPosition(it.uptimeMillis, it.position); releasedAt = it.uptimeMillis }
                    if (event.changes.any { it.isConsumed } && dragTicket == null) consumed = true
                    if (event.changes.count { it.pressed } > 1) {
                        multiplePointers = true
                        transform.resetTap()
                        reader.cancelTransition(); dragTicket = null; transform.edgeTicket = null; edgePan.overscroll = 0f
                    }
                    if (event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) moved = true
                    if(moved) {transform.tapJob?.cancel();transform.doubleTap.clear()}
                    val factor = event.calculateZoom()
                    val delta = event.calculatePan()
                    if (multiplePointers) {
                        zoom = (zoom * factor).coerceIn(1f, 4f)
                        val bounds = pagePanBounds(fit.size, viewport, zoom)
                        pan = Offset((pan.x + delta.x).coerceIn(-bounds.x, bounds.x), (pan.y + delta.y).coerceIn(-bounds.y, bounds.y))
                    } else if (zoom > 1f || panAtFit) {
                        // calculatePan excludes a lifted touch pointer. Its final measured
                        // movement still belongs to this one-finger gesture, before release.
                        val fingerDelta = primary?.let { it.position - it.previousPosition } ?: Offset.Zero
                        pan = edgePan.move(pan, fingerDelta, pagePanBounds(fit.size, viewport, zoom))
                        if (!consumed && size.width > 0) {
                            if (dragTicket == null && edgePan.canHandoff) {
                                dragTicket = reader.beginEdgeDrag(sourceStamp)
                                if (dragTicket != null) {
                                    transform.edgeTicket = dragTicket
                                    edgePan.overscroll += (reader.state.value.transition?.offset ?: 0f) * size.width
                                }
                            }
                            dragTicket?.let { ticket ->
                                val t = reader.state.value.transition?.takeIf { it.ticket == ticket }
                                if (t != null) {
                                    reader.drag(ticket, edgePan.overscroll / size.width - t.offset)
                                    edgePan.overscroll = (reader.state.value.transition?.offset ?: 0f) * size.width
                                }
                            }
                        }
                        primary?.let { edgeVelocity.add(it.uptimeMillis, edgePan.overscroll) }
                    } else {
                        val measured = primary?.let { it.position - it.previousPosition } ?: Offset.Zero
                        horizontal += measured.x; vertical += measured.y
                        if (!consumed && size.width > 0) {
                            if (!fitDragStarted && kotlin.math.abs(horizontal) > viewConfiguration.touchSlop && kotlin.math.abs(horizontal) > kotlin.math.abs(vertical) * 1.2f) {
                                if (dragTicket == null) dragTicket = reader.beginDrag(zoom, sourceStamp)
                                dragTicket?.let { reader.drag(it, horizontal / size.width); fitDragStarted = true }
                            } else if (fitDragStarted) dragTicket?.let { reader.drag(it, measured.x / size.width) }
                        }
                    }
                    if (moved && !consumed && (multiplePointers || zoom > 1f || panAtFit)) reader.hideControls()
                    if (multiplePointers || zoom > 1f || panAtFit || dragTicket != null) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                val releasedTicket = dragTicket
                if (releasedTicket != null && !multiplePointers && size.width > 0) {
                    if (if (zoom > 1f || panAtFit) !edgePan.horizontalMotion else !fitDragStarted) reader.resumeDrag(releasedTicket)
                    else reader.releaseDrag(releasedTicket,
                        (if (zoom > 1f || panAtFit) edgeVelocity.pixelsPerSecond() else velocity.calculateVelocity().x) / size.width)
                    if (fitDragStarted || edgePan.horizontalMotion) reader.hideControls()
                    dragTicket = null
                }
                if (!multiplePointers && !moved && !consumed && size.width > 0 && releasedAt - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                    val fraction = down.position.x / size.width
                    val action = pageTapAction(fraction, reader.state.value.settings.mode)
                    if(transform.tapAnchor!=current.anchor)transform.doubleTap.clear()
                    transform.tapAnchor=current.anchor
                    val second = transform.doubleTap.matches(down.position,down.uptimeMillis,viewConfiguration.doubleTapMinTimeMillis,
                        viewConfiguration.doubleTapTimeoutMillis,viewConfiguration.touchSlop*2)
                    val artwork = sourceStamp != null && comicArtworkContains(down.position,fit.size,viewport,zoom,pan)
                    val zoomIntent = artwork && (second || comicZoomTapIntent(action,zoom,releasedAt-down.uptimeMillis))
                    if (zoomIntent || action == PageTapAction.CONTROLS) {
                        val double = transform.doubleTap.tap(down.position,down.uptimeMillis,releasedAt,viewConfiguration.doubleTapMinTimeMillis,
                            viewConfiguration.doubleTapTimeoutMillis,viewConfiguration.touchSlop*2)
                        if(!zoomIntent)transform.doubleTap.clear()
                        transform.tapJob?.cancel()
                        if(double && zoomIntent && reader.state.value.transition==null) {
                            reader.cancelTransition()
                            val next = comicDoubleTapZoom(zoom,pan,down.position,fit.size,viewport)
                            zoom=next.zoom;pan=next.pan;transform.edgeTicket=null;reader.hideControls()
                        } else if(action == PageTapAction.CONTROLS) transform.tapJob = tapScope.launch {
                            val start = withFrameNanos { it }
                            var elapsed=0L
                            while(elapsed < viewConfiguration.doubleTapTimeoutMillis*1_000_000)elapsed=withFrameNanos { it }-start
                            transform.doubleTap.clear();reader.toggleControls()
                        }
                    } else if(action != null && zoom == 1f) {
                        transform.resetTap();reader.tap(fraction);reader.hideControls()
                    }
                }
                } finally { dragTicket?.let { reader.returnTransition(it) } }
            }
          }
        }, contentAlignment = Alignment.Center) {
            val offset = transition?.offset ?: 0f
            SpreadArtwork(current, state, bitmaps, failedConversions, reader.document.pages,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = pan.x + size.width * offset; translationY = pan.y
                })
            if (transition != null && transition.target != transition.from && target != null) {
                val side = pageIncomingSide(transition.from, transition.target, state.settings.mode)
                SpreadArtwork(target, state, bitmaps, failedConversions, reader.document.pages,
                    Modifier.fillMaxSize().graphicsLayer { translationX = size.width * (offset + side) })
                if (transition.phase == PageTransitionPhase.WAITING && targetStamps == null && !targetFailed)
                    Text("Loading ${target.indicator(reader.document.pages.size)}", Modifier.align(Alignment.BottomCenter), color=Color.White)
            }
            if (transition == null) stamps(current)?.let { ready ->
                if(scrub==null && state.tapIntent==null) SideEffect { reader.presented(state.ticket,current.anchor,ready) }
                else LaunchedEffect(reader,state.ticket,current.anchor,ready,scrub?.released) {
                    withFrameNanos { }
                    if(scrub!=null)reader.previewPresented(state.ticket,current.anchor,ready) else reader.presented(state.ticket,current.anchor,ready)
                }
            }
        }
    }
}

/** One canvas/layer per spread; both halves borrow the same conversion map.
 * A partial pair is never presented as a successful spread. */
@Composable
private fun SpreadArtwork(spread: PageSpread, state: PageReaderState, bitmaps: Map<Int, ImageBitmap>, failed: Set<Int>, pages: List<PageEntry>, modifier: Modifier) {
    val count = pages.size
    if (spread.second == null && state.settings.fit == PageFit.SCREEN) {
        // Preserve the established Single fit-screen canvas/semantics and placeholder contract.
        val frame = state.frames[spread.anchor].let { if (it is PageFrame.Ready && bitmaps[spread.anchor] == null && spread.anchor !in failed) PageFrame.Loading else it }
        PageArtwork(bitmaps[spread.anchor], frame, spread.anchor, count, modifier)
        return
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (spread.indices.any { state.frames[it] is PageFrame.Unavailable || it in failed }) Text("Requested pages unavailable or unsupported")
        else if (spread.indices.all { bitmaps[it] != null }) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val density = LocalDensity.current
            val order = spread.visualOrder(state.settings.mode)
            val fit = with(density) { fitPageSpread(order.map { pages[it].dimensions },
                Size(maxWidth.toPx(), maxHeight.toPx()), PAGE_SPREAD_GUTTER_DP.dp.toPx(), state.settings.fit) }
            Row(Modifier.requiredSize(with(density) { fit.size.width.toDp() }, with(density) { fit.size.height.toDp() }),
                horizontalArrangement = Arrangement.spacedBy(with(density) { fit.gutter.toDp() }), verticalAlignment = Alignment.CenterVertically) {
                for ((slot, index) in order.withIndex()) PageArtwork(bitmaps[index], state.frames[index], index, count,
                    with(density) { Modifier.requiredSize(fit.pages[slot].width.toDp(), fit.pages[slot].height.toDp()) })
            }
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Loading ${spread.indicator(count)}") }
    }
}

@Composable
internal fun ContinuousPages(reader: PageReaderController, state: PageReaderState, bitmaps: Map<Int, ImageBitmap>, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val width = maxWidth
        val density = LocalDensity.current
        DisposableEffect(reader, width) { reader.presentationChanged(); onDispose {} }
        val list = remember(reader) { LazyListState() }
        var restored by remember(reader, state.ticket, width) { mutableStateOf(false) }
        val initial = remember(reader, state.ticket, width) { state.position }
        LaunchedEffect(reader, state.ticket, width) {
            val height = with(density) { (width / PagePolicy.aspectRatio(reader.document.pages[initial.index])).toPx() }
            list.scrollToItem(initial.index, (height * initial.fraction).toInt().coerceAtLeast(0))
            restored = true
        }
        LaunchedEffect(reader, state.ticket, width, restored) {
            if (!restored) return@LaunchedEffect
            var userScrolled = false
            snapshotFlow {
                val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
                Triple(first?.index, first?.let { ((list.layoutInfo.viewportStartOffset - it.offset).toDouble() / it.size.coerceAtLeast(1)).coerceIn(0.0, 1.0) }, list.isScrollInProgress)
            }.collect { (index, fraction, scrolling) ->
                if (scrolling) { userScrolled = true; reader.hideControls() }
                if (userScrolled && index != null && fraction != null) reader.report(state.ticket, index, fraction,
                    list.layoutInfo.visibleItemsInfo.take(PagePolicy.RETAINED).map { it.index })
                else reader.visible(state.ticket, list.layoutInfo.visibleItemsInfo.take(PagePolicy.RETAINED).map { it.index })
            }
        }
        LazyColumn(Modifier.fillMaxSize().pointerInput(reader) {
            detectTapGestures { if (size.width > 0) reader.tap(it.x / size.width) }
        }.semantics { onClick(label = "Toggle reader controls") { reader.toggleControls(); true } }, state = list, userScrollEnabled = restored,
            verticalArrangement = Arrangement.spacedBy(if (state.settings.mode == PageReadingMode.WEBTOON) 0.dp else 8.dp)) {
            itemsIndexed(reader.document.pages, key = { _, page -> page.key }) { index, page ->
                // Exact same geometry for loading, errors and decoded images: stable in both directions.
                Box(Modifier.fillMaxWidth().height(width / PagePolicy.aspectRatio(page)), contentAlignment = Alignment.Center) {
                    PageArtwork(bitmaps[index], state.frames[index], index, reader.document.pages.size, Modifier.fillMaxSize())
                    if (bitmaps[index] != null && index == state.position.index) (state.frames[index] as? PageFrame.Ready)?.let { frame ->
                        SideEffect { reader.presented(state.ticket, index, frame.stamp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageArtwork(bitmap: ImageBitmap?, frame: PageFrame?, index: Int, count: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, "Page ${index + 1} of $count", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (frame is PageFrame.Unavailable || frame is PageFrame.Ready) Text("Page ${index + 1} unavailable or unsupported")
        else Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Loading page ${index + 1}") }
    }
}
