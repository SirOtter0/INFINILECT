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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.app.media.rasterImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class PresentedPage(val stamp: Long, val bitmap: ImageBitmap)

private class PageTransform {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    fun reset() { zoom = 1f; pan = Offset.Zero }
}

/** Presentation only: bitmap conversion is a UI adapter, not part of page semantics. */
@Composable
internal fun PageReader(reader: PageReaderController, saveFailed: Boolean, onBack: () -> Unit, backLabel: String) {
    val state by reader.state.collectAsState()
    val settingsFailed by reader.settingsSaveFailed.collectAsState()
    val conversionLock = remember(reader) { Mutex() }
    val readyFrames = state.frames.filterValues { it is PageFrame.Ready }
    val presented by produceState<Map<Int, PresentedPage>>(emptyMap(), reader, readyFrames) {
        value = value.filter { (index, old) -> (readyFrames[index] as? PageFrame.Ready)?.stamp == old.stamp }
        for ((index, frame) in readyFrames) {
            if (frame !is PageFrame.Ready || index in value) continue
            val bitmap = try { conversionLock.withLock { currentCoroutineContext().ensureActive(); rasterImageBitmap(frame.raster) } }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { continue }
            currentCoroutineContext().ensureActive()
            value = value + (index to PresentedPage(frame.stamp, bitmap))
        }
    }
    // Never flash an obsolete frame during the coroutine/recomposition handover.
    val bitmaps = presented.filter { (index, old) -> (readyFrames[index] as? PageFrame.Ready)?.stamp == old.stamp }.mapValues { it.value.bitmap }
    val transform = remember(reader, state.position.index, state.settings.mode) { PageTransform() }
    // One cancellable animation of the incoming layer. No outgoing raster/bitmap is captured.
    val arrival = remember(reader) { Animatable(1f) }
    val lastShown = remember(reader) { arrayOfNulls<Int>(1) }
    var arrivalSign by remember(reader) { mutableIntStateOf(0) }
    val currentBitmap = bitmaps[state.position.index]
    val currentFrame = state.frames[state.position.index] as? PageFrame.Ready
    LaunchedEffect(reader, state.ticket, state.position.index, currentBitmap, currentFrame?.stamp) {
        arrival.snapTo(1f)
        if (currentBitmap != null && currentFrame != null) {
            arrivalSign = pageTransitionSign(lastShown[0], state.position.index, state.settings.mode)
            lastShown[0] = state.position.index
            if (arrivalSign != 0) { arrival.snapTo(0f); arrival.animateTo(1f, tween(140)) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val continuous = state.settings.mode == PageReadingMode.VERTICAL || state.settings.mode == PageReadingMode.WEBTOON
        if (continuous) ContinuousPages(reader, state, bitmaps, Modifier.fillMaxSize())
        else PagedCanvas(reader, state, currentBitmap, transform, Modifier.fillMaxSize(), arrival.value, arrivalSign)
        if (state.controlsVisible) PageReaderChrome(reader, state, onBack, backLabel, transform, saveFailed, settingsFailed)
        // Failures stay visible even with hidden chrome, without reserving page height.
        else if (saveFailed || settingsFailed) Surface(Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colors.surface.copy(alpha = .94f)) {
            Text(if (saveFailed) "Reading position could not be saved on this device." else "Page reader preferences could not be saved on this device.", Modifier.padding(8.dp))
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
private fun PagedCanvas(reader: PageReaderController, state: PageReaderState, bitmap: ImageBitmap?, transform: PageTransform, modifier: Modifier, arrival: Float, arrivalSign: Int) {
    var zoom by transform::zoom
    var pan by transform::pan
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
        Box(modifier.clipToBounds().semantics {
            onClick(label = "Toggle reader controls") { reader.toggleControls(); true }
            customActions = listOf(CustomAccessibilityAction("Previous page") { reader.previous(); true },
                CustomAccessibilityAction("Next page") { reader.next(); true })
        }.pointerInput(reader, transform) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var swipe = 0f
                var vertical = 0f
                var transformed = zoom > 1f
                var multiplePointers = false
                var moved = false
                var consumed = false
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) consumed = true
                    if (event.changes.count { it.pressed } > 1) { transformed = true; multiplePointers = true }
                    if (event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) moved = true
                    val factor = event.calculateZoom()
                    val delta = event.calculatePan()
                    if (transformed || zoom > 1f) {
                        zoom = (zoom * factor).coerceIn(1f, 4f)
                        pan = Offset((pan.x + delta.x).coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2),
                            (pan.y + delta.y).coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2))
                    } else { swipe += delta.x; vertical += delta.y }
                    if (kotlin.math.abs(swipe) > viewConfiguration.touchSlop || kotlin.math.abs(vertical) > viewConfiguration.touchSlop) moved = true
                    if (transformed || kotlin.math.abs(swipe) > threshold) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                if (!transformed && zoom == 1f && kotlin.math.abs(swipe) >= threshold && kotlin.math.abs(swipe) > kotlin.math.abs(vertical) * 1.2f) reader.swipe(swipe > 0)
                else if (!multiplePointers && !moved && !consumed && size.width > 0) reader.tap(down.position.x / size.width)
            }
        }, contentAlignment = Alignment.Center) {
            PageArtwork(bitmap, state.frames[state.position.index], state.position.index, reader.document.pages.size,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = pan.x + size.width * .035f * arrivalSign * (1f - arrival)
                    translationY = pan.y; alpha = .85f + .15f * arrival
                })
            if (bitmap != null) (state.frames[state.position.index] as? PageFrame.Ready)?.let { frame ->
                SideEffect { reader.presented(state.ticket, state.position.index, frame.stamp) }
            }
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
                Text("${state.position.index + 1} / ${reader.document.pages.size}", Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(enabled = state.position.index > 0, onClick = reader::previous) { Text("Previous") }
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
                                DropdownMenuItem(onClick = { transform.zoom = (transform.zoom - .5f).coerceAtLeast(1f); transform.pan = Offset.Zero; settings = false }) { Text("Zoom out") }
                                DropdownMenuItem(onClick = { transform.zoom = (transform.zoom + .5f).coerceAtMost(4f); settings = false }) { Text("Zoom in") }
                                DropdownMenuItem(onClick = { transform.reset(); settings = false }) { Text("Reset zoom") }
                            }
                        }
                    }
                    TextButton(enabled = state.position.index < reader.document.pages.lastIndex, onClick = reader::next) { Text("Next") }
                }
                if (saveFailed) Text("Reading position could not be saved on this device.")
                if (settingsFailed) Text("Page reader preferences could not be saved on this device.")
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
