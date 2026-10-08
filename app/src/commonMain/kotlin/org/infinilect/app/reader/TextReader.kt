// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlin.math.ceil

/** Only visible bounded windows are composed. Filesystem IO stays behind document.window().
 * Persist the visible line's global code-point position, never LazyList/layout coordinates.
 */
@Composable
internal fun TextReader(
    document: TextDocument, reading: TextReadingProgress?, saveFailed: Boolean,
    onBack: () -> Unit, backLabel: String = "Back to results",
) {
    // Remembered viewport, jobs and layouts belong to this document, never to a reused Ready slot.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Pixel offsets belong to one text geometry. Reflow restores the live code point.
        key(document, constraints.maxWidth, density.density, density.fontScale, MaterialTheme.typography.body1) {
            TextReaderContent(document, reading, saveFailed, onBack, backLabel)
        }
    }
}

@Composable
private fun TextReaderContent(
    document: TextDocument, reading: TextReadingProgress?, saveFailed: Boolean,
    onBack: () -> Unit, backLabel: String,
) {
    val scope = rememberCoroutineScope()
    val loader = remember { TextWindowLoader(document, scope) }
    DisposableEffect(loader) { onDispose { loader.close() } }
    val readFailed by loader.failed.collectAsState()
    val initialOffset = remember(document) { reading?.codePointOffset?.value ?: 0 }
    val initialWindow = remember(document) { document.windowFor(initialOffset) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialWindow)
    val layouts = remember(document) { mutableStateMapOf<Int, Pair<TextWindow, TextLayoutResult>>() }
    var restored by remember(document) { mutableStateOf(false) }
    val offset by (reading?.codePointOffset ?: remember(document) { kotlinx.coroutines.flow.MutableStateFlow(0) }).collectAsState()
    LaunchedEffect(document) {
        val (window, layout) = snapshotFlow { layouts[initialWindow] }.first { it != null }!!
        val local = window.locations.utf16Offset((initialOffset - window.startCodePoint).toLong())
        val line = layout.getLineForOffset(local)
        // Integer offsets at a rounded shared boundary can select the previous line.
        // Restore just inside the semantic line (at most two pixels past its top).
        val lineTop = ceil(layout.getLineTop(line)).toInt().coerceAtLeast(0)
        list.scrollToItem(initialWindow, lineTop + if (line > 0) 1 else 0)
        restored = true
    }
    LaunchedEffect(loader, list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect(loader::focus)
    }
    LaunchedEffect(document, restored) {
        if (!restored) return@LaunchedEffect
        var moved = false
        snapshotFlow {
            val index = list.firstVisibleItemIndex
            ViewportReading(list.isScrollInProgress, list.firstVisibleItemScrollOffset,
                layouts[index], !list.canScrollForward,
                list.layoutInfo.visibleItemsInfo.any { it.index == document.windowCount - 1 } &&
                    layouts[document.windowCount - 1] != null)
        }.collect { (scrolling, y, visible, atBottom, finalReady) ->
            if (scrolling) moved = true
            if (!moved || visible == null) return@collect
            val (window, layout) = visible
            visibleWindowCodePoint(document, window,
                layout.getLineStart(layout.getLineForVerticalPosition(y.toFloat())), atBottom, finalReady)
                ?.let { reading?.report(it) }
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(document.title, style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
            Button(onClick = onBack) { Text(backLabel) }
        }
        if (reading != null) Text("${(document.progression(offset) * 100).toInt()}% · approximate position", style = MaterialTheme.typography.caption)
        if (saveFailed) Text("Reading position could not be saved on this device.", style = MaterialTheme.typography.caption)
        if (readFailed) Text(TextFailure.STORAGE.userMessage)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val style = MaterialTheme.typography.body1
            // Height is only comparable for the same layout constraints/font/density.
            val geometry = remember(document, constraints.maxWidth, density.density, density.fontScale, style) {
                TextViewportGeometry(document.windowCount)
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), userScrollEnabled = restored) {
                items(document.windowCount, key = { document.windowStart(it) }) { index ->
                    val state = remember(loader, index) { kotlinx.coroutines.flow.MutableStateFlow(loader.available(index)) }
                    val window by state.collectAsState()
                    DisposableEffect(loader, index) {
                        loader.attach(index, state)
                        onDispose { loader.detach(index); layouts.remove(index) }
                    }
                    val current = window
                    if (current == null) {
                        val height = with(density) { geometry.pendingHeight(index, constraints.maxHeight).toDp() }
                        Box(Modifier.fillMaxWidth().height(height)) {
                            if (!readFailed) Text("Loading text…", style = style)
                        }
                    } else Text(current.text, modifier = Modifier.fillMaxWidth(), style = style,
                        onTextLayout = {
                            geometry.measured(index, it.size.height.coerceAtLeast(1))
                            layouts[index] = current to it
                        })
                }
            }
        }
    }
}

private data class ViewportReading(
    val scrolling: Boolean, val y: Int, val visible: Pair<TextWindow, TextLayoutResult>?,
    val atBottom: Boolean, val finalReady: Boolean,
)
