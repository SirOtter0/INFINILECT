// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Passive native Compose presentation. No URI handler, browser, filesystem or source. */
@Composable
internal fun EpubReader(reader: EpubReaderController, saveFailed: Boolean, onBack: () -> Unit, backLabel: String) {
    val state by reader.state.collectAsState()
    val progression by reader.progression.collectAsState()
    var showToc by remember(reader) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(reader.document.metadata.title, style = MaterialTheme.typography.h6, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text(backLabel) }
            Button(onClick = { showToc = true }) { Text("Contents") }
            Text("${(progression * 100).toInt().coerceIn(0, 100)}%", modifier = Modifier.padding(8.dp))
        }
        if (saveFailed) Text("Reading position could not be saved on this device.", color = MaterialTheme.colors.error)
        when (val current = state) {
            EpubReaderState.Loading -> CircularProgressIndicator()
            is EpubReaderState.Error -> {
                Text(current.message, color = MaterialTheme.colors.error)
                Button(onClick = { reader.chapter(0) }) { Text("Return to first chapter") }
            }
            is EpubReaderState.Ready -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = current.spineIndex > 0, onClick = { reader.chapter(current.spineIndex - 1) }) { Text("Previous") }
                    Text("Chapter ${current.spineIndex + 1} / ${reader.document.spine.size}", modifier = Modifier.padding(8.dp))
                    Button(enabled = current.spineIndex + 1 < reader.document.spine.size, onClick = { reader.chapter(current.spineIndex + 1) }) { Text("Next") }
                }
                key(reader, current.ticket) { ChapterBody(reader, current, Modifier.weight(1f)) }
            }
        }
    }
    if (showToc) AlertDialog(onDismissRequest = { showToc = false }, title = { Text("Contents") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(reader.toc) { _, entry ->
                    TextButton(onClick = { showToc = false; reader.navigate(entry.target) }, modifier = Modifier.padding(start = (entry.depth.coerceAtMost(8) * 8).dp)) { Text(entry.label) }
                }
            }
        }, confirmButton = { TextButton(onClick = { showToc = false }) { Text("Close") } })
}

@Composable
private fun ChapterBody(reader: EpubReaderController, ready: EpubReaderState.Ready, modifier: Modifier) {
    val list = rememberLazyListState()
    val layouts = remember { mutableStateMapOf<Int, TextLayoutResult>() }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(ready.ticket) {
        val (block, points) = ready.initialPosition
        list.scrollToItem(block)
        if (points > 0) {
            val layout = snapshotFlow { layouts[block] }.filterNotNull().first()
            val prefix = if (ready.chapter.blocks[block].kind == EpubBlockKind.LIST_ITEM) 2 else 0
            val utf16 = ready.chapter.blocks[block].text.epubUtf16(points) + prefix
            val line = layout.getLineForOffset(utf16.coerceAtMost(layout.layoutInput.text.length))
            list.scrollToItem(block, layout.getLineTop(line).toInt().coerceAtLeast(0))
        }
        restored = true
    }
    LaunchedEffect(ready.ticket, restored) {
        if (!restored) return@LaunchedEffect
        var userScrolled = false
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }.collect { (index, pixels, scrolling) ->
            if (scrolling) userScrolled = true
            if (!userScrolled) return@collect
            val block = ready.chapter.blocks.getOrNull(index) ?: return@collect
            val layout = layouts[index] ?: return@collect
            val line = layout.getLineForVerticalPosition(pixels.toFloat())
            val prefix = if (block.kind == EpubBlockKind.LIST_ITEM) 2 else 0
            val points = block.text.epubPointAtUtf16((layout.getLineStart(line) - prefix).coerceAtLeast(0))
            if (!list.canScrollForward && list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == ready.chapter.blocks.lastIndex) {
                reader.report(ready.ticket, ready.chapter.blocks.lastIndex, ready.chapter.blocks.last().codePoints)
            } else reader.report(ready.ticket, index, points)
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, userScrollEnabled = restored,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(ready.chapter.blocks, key = { index, _ -> index }) { index, block ->
            val text = remember(block, ready.ticket) {
                buildAnnotatedString {
                    if (block.kind == EpubBlockKind.LIST_ITEM) append("• ")
                    for ((runIndex, run) in block.runs.withIndex()) {
                        val style = SpanStyle(fontWeight = if (run.strong) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (run.emphasis) FontStyle.Italic else FontStyle.Normal)
                        withStyle(style) {
                            val target = run.target
                            if (target == null) append(run.text)
                            else withLink(LinkAnnotation.Clickable("internal-$runIndex", TextLinkStyles(style = SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))) {
                                reader.navigate(target)
                            }) { append(run.text) }
                        }
                    }
                }
            }
            Text(text, style = when (block.kind) {
                EpubBlockKind.HEADING -> MaterialTheme.typography.h5
                EpubBlockKind.QUOTE -> MaterialTheme.typography.body1.copy(fontStyle = FontStyle.Italic)
                else -> MaterialTheme.typography.body1
            }, modifier = Modifier.fillMaxWidth(), onTextLayout = {
                layouts[index] = it
                // Only a bounded set of transient layouts, never persisted coordinates.
                if (layouts.size > 12) layouts.keys.filter { key -> key != index }.maxByOrNull { key -> kotlin.math.abs(key - index) }?.let(layouts::remove)
            })
        }
    }
}
