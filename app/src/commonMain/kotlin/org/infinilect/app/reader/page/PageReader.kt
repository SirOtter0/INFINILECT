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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
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

private class PageTransform {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var edgeTicket by mutableStateOf<Long?>(null)
    fun reset() { zoom = 1f; pan = Offset.Zero; edgeTicket = null }
}

/** Presentation only: bitmap conversion is a UI adapter, not part of page semantics. */
@Composable
internal fun PageReader(reader: PageReaderController, saveFailed: Boolean, onBack: () -> Unit, backLabel: String) {
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
    val transform = remember(reader, state.position.index, state.settings) { PageTransform() }
    Box(Modifier.fillMaxSize()) {
        val continuous = state.settings.mode == PageReadingMode.VERTICAL || state.settings.mode == PageReadingMode.WEBTOON
        if (continuous) ContinuousPages(reader, state, bitmaps, Modifier.fillMaxSize())
        else PagedCanvas(reader, state, bitmaps, failedConversions, transform, Modifier.fillMaxSize())
        if (state.controlsVisible) PageReaderChrome(reader, state, onBack, backLabel, transform, saveFailed, settingsFailed)
        // Failures stay visible even with hidden chrome, without reserving page height.
        else if (saveFailed || settingsFailed || state.navigationFailed) Surface(Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colors.surface.copy(alpha = .94f)) {
            Text(if (state.navigationFailed) "Requested page unavailable or unsupported" else if (saveFailed) "Reading position could not be saved on this device." else "Page reader preferences could not be saved on this device.", Modifier.padding(8.dp))
        }
    }
}

private fun modeLabel(mode: PageReadingMode) = when (mode) {
    PageReadingMode.PAGED_RTL -> "Right-to-left (manga)"
    PageReadingMode.PAGED_LTR -> "Left-to-right"
    PageReadingMode.VERTICAL -> "Vertical"
    PageReadingMode.WEBTOON -> "Webtoon"
}

@Composable
private fun PagedCanvas(reader: PageReaderController, state: PageReaderState, bitmaps: Map<Int, ImageBitmap>, failedConversions: Set<Int>, transform: PageTransform, modifier: Modifier) {
    var zoom by transform::zoom
    var pan by transform::pan
    val current = reader.spread(state.position.index)
    fun stamps(spread: PageSpread): Map<Int, Long>? = spread.indices.associateWith { index ->
        (state.frames[index] as? PageFrame.Ready)?.stamp?.takeIf { bitmaps[index] != null } ?: return null
    }
    val sourceStamp by rememberUpdatedState(stamps(current)?.get(current.anchor))
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
            with(density) { PAGE_SPREAD_GUTTER_DP.dp.toPx() })
        // A resized canvas retires callbacks from the previous spatial presentation.
        DisposableEffect(reader, width, maxHeight) { transform.reset(); reader.presentationChanged(); onDispose {} }
        Box(Modifier.fillMaxSize().semantics {
            onClick(label = "Toggle reader controls") { reader.toggleControls(); true }
            customActions = listOf(CustomAccessibilityAction("Previous page") { transform.reset(); reader.previous(); true },
                CustomAccessibilityAction("Next page") { transform.reset(); reader.next(); true })
        }.pointerInput(reader, transform, width, maxHeight, fit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val velocity = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                var horizontal = 0f
                var vertical = 0f
                var multiplePointers = false
                var moved = false
                var consumed = false
                var dragTicket: Long? = null
                val edgePan = PageEdgePanGesture(viewConfiguration.touchSlop, size.width.toFloat())
                // Grabbing an in-flight edge turn uses the same authoritative incoming identity.
                val oldEdge = reader.state.value.transition?.takeIf { transform.edgeTicket == it.ticket }
                if (oldEdge != null) {
                    dragTicket = reader.beginEdgeDrag(sourceStamp)
                    transform.edgeTicket = dragTicket
                    edgePan.overscroll = oldEdge.offset * size.width
                }
                try {
                do {
                    val event = awaitPointerEvent()
                    val primary = event.changes.firstOrNull { it.id == down.id }
                    primary?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                    if (event.changes.any { it.isConsumed } && dragTicket == null) consumed = true
                    if (event.changes.count { it.pressed } > 1) {
                        multiplePointers = true
                        reader.cancelTransition(); dragTicket = null; transform.edgeTicket = null; edgePan.overscroll = 0f
                    }
                    if (event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) moved = true
                    val factor = event.calculateZoom()
                    val delta = event.calculatePan()
                    if (multiplePointers) {
                        zoom = (zoom * factor).coerceIn(1f, 4f)
                        val bounds = pagePanBounds(fit.size, viewport, zoom)
                        pan = Offset((pan.x + delta.x).coerceIn(-bounds.x, bounds.x), (pan.y + delta.y).coerceIn(-bounds.y, bounds.y))
                    } else if (zoom > 1f) {
                        pan = edgePan.move(pan, delta, pagePanBounds(fit.size, viewport, zoom))
                        if (!consumed && size.width > 0) {
                            if (dragTicket == null && edgePan.canHandoff) {
                                dragTicket = reader.beginEdgeDrag(sourceStamp)
                                transform.edgeTicket = dragTicket
                            }
                            dragTicket?.let { ticket ->
                                val t = reader.state.value.transition?.takeIf { it.ticket == ticket }
                                if (t != null) {
                                    reader.drag(ticket, edgePan.overscroll / size.width - t.offset)
                                    edgePan.overscroll = (reader.state.value.transition?.offset ?: 0f) * size.width
                                }
                            }
                        }
                    } else {
                        horizontal += delta.x; vertical += delta.y
                        if (!consumed && size.width > 0) {
                            if (dragTicket == null && kotlin.math.abs(horizontal) > viewConfiguration.touchSlop && kotlin.math.abs(horizontal) > kotlin.math.abs(vertical) * 1.2f) {
                                dragTicket = reader.beginDrag(zoom, sourceStamp)
                                dragTicket?.let { reader.drag(it, horizontal / size.width) }
                            } else dragTicket?.let { reader.drag(it, delta.x / size.width) }
                        }
                    }
                    if (multiplePointers || zoom > 1f || dragTicket != null) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                val releasedTicket = dragTicket
                if (releasedTicket != null && !multiplePointers && size.width > 0) {
                    if (zoom > 1f && !edgePan.horizontalMotion) reader.returnTransition(releasedTicket)
                    else reader.releaseDrag(releasedTicket, velocity.calculateVelocity().x / size.width)
                    dragTicket = null
                } else if (!multiplePointers && !moved && !consumed && size.width > 0) {
                    val fraction = down.position.x / size.width
                    val action = pageTapAction(fraction, reader.state.value.settings.mode)
                    if (action == PageTapAction.CONTROLS || zoom == 1f) {
                        if (action != PageTapAction.CONTROLS) transform.reset()
                        reader.tap(fraction)
                    }
                }
                } finally { dragTicket?.let { reader.returnTransition(it) } }
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
                    Text("Loading ${target.indicator(reader.document.pages.size)}", Modifier.align(Alignment.BottomCenter))
            }
            if (transition == null) stamps(current)?.let { ready ->
                SideEffect { reader.presented(state.ticket, current.anchor, ready) }
            }
        }
    }
}

/** One canvas/layer per spread; both halves borrow the same conversion map.
 * A partial pair is never presented as a successful spread. */
@Composable
private fun SpreadArtwork(spread: PageSpread, state: PageReaderState, bitmaps: Map<Int, ImageBitmap>, failed: Set<Int>, pages: List<PageEntry>, modifier: Modifier) {
    val count = pages.size
    if (spread.second == null) {
        val frame = state.frames[spread.anchor].let { if (it is PageFrame.Ready && bitmaps[spread.anchor] == null && spread.anchor !in failed) PageFrame.Loading else it }
        PageArtwork(bitmaps[spread.anchor], frame, spread.anchor, count, modifier)
    } else BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (spread.indices.any { state.frames[it] is PageFrame.Unavailable || it in failed }) Text("Requested pages unavailable or unsupported")
        else if (spread.indices.all { bitmaps[it] != null }) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val density = LocalDensity.current
            val order = spread.visualOrder(state.settings.mode)
            val fit = with(density) { fitPageSpread(order.map { pages[it].dimensions },
                Size(maxWidth.toPx(), maxHeight.toPx()), PAGE_SPREAD_GUTTER_DP.dp.toPx()) }
            Row(horizontalArrangement = Arrangement.spacedBy(with(density) { fit.gutter.toDp() }), verticalAlignment = Alignment.CenterVertically) {
                for ((slot, index) in order.withIndex()) PageArtwork(bitmaps[index], state.frames[index], index, count,
                    with(density) { Modifier.size(fit.pages[slot].width.toDp(), fit.pages[slot].height.toDp()) })
            }
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Loading ${spread.indicator(count)}") }
    }
}

/** Sibling overlay: buttons consume their own input before the canvas beneath them.
 * A bounded scrollable chrome column also keeps all controls reachable in short windows. */
@Composable
private fun BoxScope.PageReaderChrome(reader: PageReaderController, state: PageReaderState, onBack: () -> Unit, backLabel: String,
    transform: PageTransform, saveFailed: Boolean, settingsFailed: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val topLimit = maxHeight / 3
      val bottomLimit = maxHeight * 2 / 3
      val navigationIndex = state.transition?.takeIf { it.phase != PageTransitionPhase.RETURNING }?.target ?: state.position.index
      Column(Modifier.fillMaxSize()) {
        Surface(Modifier.heightIn(max = topLimit).verticalScroll(rememberScrollState()), color = MaterialTheme.colors.surface.copy(alpha = .94f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(backLabel) }
                Text(reader.document.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = reader::toggleControls) { Text("Hide") }
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(Modifier.heightIn(max = bottomLimit).verticalScroll(rememberScrollState()), color = MaterialTheme.colors.surface.copy(alpha = .94f)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(reader.spread(state.position.index).indicator(reader.document.pages.size), Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(enabled = navigationIndex > 0, onClick = { transform.reset(); reader.previous() }) { Text("Previous") }
                    var settings by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { settings = true }) { Text("Settings") }
                        DropdownMenu(expanded = settings, onDismissRequest = { settings = false }) {
                            Text("Reading direction", Modifier.padding(12.dp), style = MaterialTheme.typography.subtitle2)
                            for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) {
                                DropdownMenuItem(onClick = { reader.mode(mode); settings = false }, modifier = Modifier.semantics { selected = state.settings.mode == mode }) { Text(modeLabel(mode)) }
                            }
                            // Retain the earlier reader's modes/zoom; PR #21 adds none of them.
                            Divider()
                            for (mode in listOf(PageReadingMode.VERTICAL, PageReadingMode.WEBTOON)) {
                                DropdownMenuItem(onClick = { reader.mode(mode); settings = false }) { Text(modeLabel(mode)) }
                            }
                            if (state.settings.mode == PageReadingMode.PAGED_LTR || state.settings.mode == PageReadingMode.PAGED_RTL) {
                                Divider()
                                Text("Page layout", Modifier.padding(12.dp), style = MaterialTheme.typography.subtitle2)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    for (layout in PageLayout.entries) {
                                        TextButton(onClick = { reader.layout(layout); settings = false }, modifier = Modifier.semantics { selected = state.settings.layout == layout }) {
                                            Text(if (layout == PageLayout.SINGLE) "Single page" else "Double page")
                                        }
                                    }
                                }
                                Divider()
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    TextButton(onClick = { reader.cancelTransition(); transform.edgeTicket = null; transform.zoom = (transform.zoom - .5f).coerceAtLeast(1f); transform.pan = Offset.Zero; settings = false }) { Text("Zoom out") }
                                    TextButton(onClick = { reader.cancelTransition(); transform.edgeTicket = null; transform.zoom = (transform.zoom + .5f).coerceAtMost(4f); settings = false }) { Text("Zoom in") }
                                    TextButton(onClick = { reader.cancelTransition(); transform.reset(); settings = false }) { Text("Reset zoom") }
                                }
                            }
                        }
                    }
                    TextButton(enabled = reader.spread(navigationIndex).indices.last() < reader.document.pages.lastIndex, onClick = { transform.reset(); reader.next() }) { Text("Next") }
                }
                if (saveFailed) Text("Reading position could not be saved on this device.")
                if (settingsFailed) Text("Page reader preferences could not be saved on this device.")
                if (state.navigationFailed) Text("Requested page unavailable or unsupported")
            }
        }
      }
    }
}

@Composable
private fun ContinuousPages(reader: PageReaderController, state: PageReaderState, bitmaps: Map<Int, ImageBitmap>, modifier: Modifier) {
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
                if (scrolling) userScrolled = true
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
