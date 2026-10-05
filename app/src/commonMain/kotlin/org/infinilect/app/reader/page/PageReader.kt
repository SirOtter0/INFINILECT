// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.app.media.rasterImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class PresentedPage(val stamp: Long, val bitmap: ImageBitmap)

/** Presentation only: bitmap conversion is a UI adapter, not part of page semantics. */
@Composable
internal fun PageReader(reader: PageReaderController, saveFailed: Boolean, onBack: () -> Unit, backLabel: String) {
    val state by reader.state.collectAsState()
    val settingsFailed by reader.settingsSaveFailed.collectAsState()
    var controls by remember(reader) { mutableStateOf(false) }
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(backLabel) }
            Text("${state.position.index + 1} / ${reader.document.pages.size}", Modifier.weight(1f), style = MaterialTheme.typography.subtitle1)
            TextButton(onClick = { controls = !controls }) { Text(if (controls) "Hide controls" else "Controls") }
        }
        if (controls) {
            Text(reader.document.title, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.subtitle1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(enabled = state.position.index > 0, onClick = reader::previous) { Text("Previous") }
                var modes by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { modes = true }) { Text(modeLabel(state.settings.mode)) }
                    DropdownMenu(expanded = modes, onDismissRequest = { modes = false }) {
                        PageReadingMode.entries.forEach { mode -> DropdownMenuItem(onClick = { reader.mode(mode); modes = false }) { Text(modeLabel(mode)) } }
                    }
                }
                TextButton(enabled = state.position.index < reader.document.pages.lastIndex, onClick = reader::next) { Text("Next") }
            }
        }
        if (saveFailed) Text("Reading position could not be saved on this device.", Modifier.padding(horizontal = 12.dp))
        if (settingsFailed) Text("Page reader preferences could not be saved on this device.", Modifier.padding(horizontal = 12.dp))
        val continuous = state.settings.mode == PageReadingMode.VERTICAL || state.settings.mode == PageReadingMode.WEBTOON
        if (continuous) ContinuousPages(reader, state, bitmaps, Modifier.weight(1f).fillMaxWidth())
        else key(reader, state.position.index, state.settings.mode) {
            PagedCanvas(reader, state, bitmaps[state.position.index], controls, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

private fun modeLabel(mode: PageReadingMode) = when (mode) {
    PageReadingMode.PAGED_RTL -> "Paged RTL"
    PageReadingMode.PAGED_LTR -> "Paged LTR"
    PageReadingMode.VERTICAL -> "Vertical"
    PageReadingMode.WEBTOON -> "Webtoon"
}

@Composable
private fun PagedCanvas(reader: PageReaderController, state: PageReaderState, bitmap: ImageBitmap?, controls: Boolean, modifier: Modifier) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    Column(modifier) {
        if (controls) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { zoom = (zoom - .5f).coerceAtLeast(1f); pan = Offset.Zero }) { Text("Zoom out") }
            TextButton(onClick = { zoom = (zoom + .5f).coerceAtMost(4f) }) { Text("Zoom in") }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Reset zoom") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(reader, state.ticket) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var swipe = 0f
                var vertical = 0f
                var transformed = zoom > 1f
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) transformed = true
                    val factor = event.calculateZoom()
                    val delta = event.calculatePan()
                    if (transformed || zoom > 1f) {
                        zoom = (zoom * factor).coerceIn(1f, 4f)
                        pan = Offset((pan.x + delta.x).coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2),
                            (pan.y + delta.y).coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2))
                    } else { swipe += delta.x; vertical += delta.y }
                    if (transformed || kotlin.math.abs(swipe) > threshold) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                if (!transformed && zoom == 1f && kotlin.math.abs(swipe) >= threshold && kotlin.math.abs(swipe) > kotlin.math.abs(vertical) * 1.2f) reader.swipe(swipe > 0)
            }
        }, contentAlignment = Alignment.Center) {
            PageArtwork(bitmap, state.frames[state.position.index], state.position.index, reader.document.pages.size,
                Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
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
        LazyColumn(Modifier.fillMaxSize(), state = list, userScrollEnabled = restored,
            verticalArrangement = Arrangement.spacedBy(if (state.settings.mode == PageReadingMode.WEBTOON) 0.dp else 8.dp)) {
            itemsIndexed(reader.document.pages, key = { _, page -> page.key }) { index, page ->
                // Exact same geometry for loading, errors and decoded images: stable in both directions.
                Box(Modifier.fillMaxWidth().height(width / PagePolicy.aspectRatio(page)), contentAlignment = Alignment.Center) {
                    PageArtwork(bitmaps[index], state.frames[index], index, reader.document.pages.size, Modifier.fillMaxSize())
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
