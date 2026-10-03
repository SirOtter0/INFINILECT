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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Only visible bounded windows are composed. Filesystem IO stays behind document.window().
 * Persist the visible line's global code-point position, never LazyList/layout coordinates.
 */
@Composable
internal fun TextReader(
    document: TextDocument, reading: TextReadingProgress?, saveFailed: Boolean,
    onBack: () -> Unit, backLabel: String = "Back to results",
) {
    val initialOffset = remember(document) { reading?.codePointOffset?.value ?: 0 }
    val initialWindow = remember(document) { document.windowFor(initialOffset) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialWindow)
    val layouts = remember(document) { mutableStateMapOf<Int, Pair<TextWindow, TextLayoutResult>>() }
    var restored by remember(document) { mutableStateOf(false) }
    var readFailed by remember(document) { mutableStateOf(false) }
    val offset by (reading?.codePointOffset ?: remember(document) { kotlinx.coroutines.flow.MutableStateFlow(0) }).collectAsState()
    LaunchedEffect(document) {
        val (window, layout) = snapshotFlow { layouts[initialWindow] }.first { it != null }!!
        val local = window.locations.utf16Offset((initialOffset - window.startCodePoint).toLong())
        val lineTop = layout.getLineTop(layout.getLineForOffset(local)).toInt().coerceAtLeast(0)
        list.scrollToItem(initialWindow, lineTop)
        restored = true
    }
    LaunchedEffect(document, restored) {
        if (!restored) return@LaunchedEffect
        var moved = false
        snapshotFlow {
            val index = list.firstVisibleItemIndex
            Triple(list.isScrollInProgress, list.firstVisibleItemScrollOffset, layouts[index])
        }.collect { (scrolling, y, visible) ->
            if (scrolling) moved = true
            if (!moved || visible == null) return@collect
            val (window, layout) = visible
            val logical = if (!list.canScrollForward) document.codePoints else window.globalOffset(
                layout.getLineStart(layout.getLineForVerticalPosition(y.toFloat())))
            reading?.report(logical)
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
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(document.windowCount, key = { it }) { index ->
                val window by produceState<TextWindow?>(null, document, index) {
                    try { value = document.window(index) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { readFailed = true }
                }
                DisposableEffect(document, index) { onDispose { layouts.remove(index) } }
                val current = window
                if (current == null) Text(if (readFailed) "" else "Loading text…", modifier = Modifier.fillMaxWidth())
                else Text(current.text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.body1,
                    onTextLayout = { layouts[index] = current to it })
            }
        }
    }
}
