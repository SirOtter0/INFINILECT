// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.ceil

@Composable
internal fun EpubReader(reader: EpubReaderController, saveFailed: Boolean, onBack: () -> Unit, backLabel: String) {
    DisposableEffect(reader) {
        // A new presentation restores the controller's live locator, not chapter entry.
        reader.presentationChanged()
        onDispose { reader.flush() }
    }
    val state by reader.state.collectAsState()
    val progression by reader.progression.collectAsState()
    val settings by reader.settings.collectAsState()
    val settingsSaveFailed by reader.settingsSaveFailed.collectAsState()
    val dark = when (settings.theme) { EpubReadingTheme.SYSTEM -> isSystemInDarkTheme(); EpubReadingTheme.LIGHT -> false; EpubReadingTheme.DARK -> true }
    var showToc by remember(reader) { mutableStateOf(false) }
    var showSettings by remember(reader) { mutableStateOf(false) }
    val density = LocalDensity.current
    var previousLayout by remember(reader) { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    // Density/font-scale can change without changing the outer pixel dimensions.
    LaunchedEffect(reader, density.density, density.fontScale) {
        previousLayout?.let { previous ->
            val layout = Triple(previous.first, density.density, density.fontScale)
            if (previous != layout) { reader.presentationChanged(); previousLayout = layout }
        }
    }
    MaterialTheme(colors = if (dark) darkColors() else lightColors()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(8.dp).onSizeChanged {
                val layout = Triple(it.width, density.density, density.fontScale)
                if (previousLayout != null && previousLayout != layout) reader.presentationChanged()
                previousLayout = layout
            }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(reader.document.metadata.title, style = MaterialTheme.typography.h6, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text(backLabel, maxLines = 2) }
                    Button(onClick = { showToc = true }, modifier = Modifier.weight(1f)) { Text("Contents") }
                    Button(onClick = { showSettings = true }, modifier = Modifier.weight(1f)) { Text("Settings") }
                }
                if (saveFailed) Text("Reading position could not be saved on this device.", color = MaterialTheme.colors.error)
                if (settingsSaveFailed) Text("Reading settings could not be saved on this device.", color = MaterialTheme.colors.error)
                when (val current = state) {
                    EpubReaderState.Loading -> CircularProgressIndicator()
                    is EpubReaderState.Error -> {
                        Text(current.message, color = MaterialTheme.colors.error)
                        Button(onClick = { reader.chapter(0) }) { Text("Return to first chapter") }
                    }
                    is EpubReaderState.Ready -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(enabled = current.spineIndex > 0, onClick = { reader.chapter(current.spineIndex - 1) }) { Text("Previous") }
                            Text("${current.spineIndex + 1} / ${reader.document.spine.size} · ${(progression * 100).toInt().coerceIn(0, 100)}%", modifier = Modifier.weight(1f))
                            TextButton(enabled = current.spineIndex + 1 < reader.document.spine.size, onClick = { reader.chapter(current.spineIndex + 1) }) { Text("Next") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            key(reader, current.ticket) { ChapterBody(reader, current, settings, Modifier.widthIn(max = 760.dp).fillMaxSize()) }
                        }
                    }
                }
            }
        }
        if (showToc) AlertDialog(onDismissRequest = { showToc = false }, title = { Text("Contents") }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(reader.toc) { _, entry ->
                    TextButton(onClick = { showToc = false; reader.navigate(entry.target) }, modifier = Modifier.fillMaxWidth().padding(start = (entry.depth.coerceAtMost(8) * 8).dp)) { Text(entry.label) }
                }
            }
        }, confirmButton = { TextButton(onClick = { showToc = false }) { Text("Close") } })
        if (showSettings) ReaderSettings(settings, reader::presentationChanged) { showSettings = false }
    }
}

@Composable
private fun ReaderSettings(settings: EpubReaderSettings, change: (EpubReaderSettings) -> Unit, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Reading settings") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            SettingStep("Font size", "${settings.fontSize}", settings.fontSize > 14, settings.fontSize < 30,
                { change(settings.copy(fontSize = (settings.fontSize - 2).coerceAtLeast(14))) }, { change(settings.copy(fontSize = (settings.fontSize + 2).coerceAtMost(30))) })
            SettingStep("Line spacing", "${settings.lineSpacingPercent}%", settings.lineSpacingPercent > 120, settings.lineSpacingPercent < 200,
                { change(settings.copy(lineSpacingPercent = (settings.lineSpacingPercent - 10).coerceAtLeast(120))) }, { change(settings.copy(lineSpacingPercent = (settings.lineSpacingPercent + 10).coerceAtMost(200))) })
            SettingStep("Margins", "${settings.margin}", settings.margin > 8, settings.margin < 40,
                { change(settings.copy(margin = (settings.margin - 4).coerceAtLeast(8))) }, { change(settings.copy(margin = (settings.margin + 4).coerceAtMost(40))) })
            Text("Reading theme")
            EpubReadingTheme.entries.forEach { theme ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.theme == theme, onClick = { change(settings.copy(theme = theme)) }, modifier = Modifier.semantics { contentDescription = "Reading theme ${theme.name.lowercase()}" })
                    TextButton(onClick = { change(settings.copy(theme = theme)) }) { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }
            Text("Settings apply to this EPUB session.", style = MaterialTheme.typography.caption)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Done") } })
}
@Composable
private fun SettingStep(label: String, value: String, less: Boolean, more: Boolean, decrease: () -> Unit, increase: () -> Unit) {
    Text(label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(decrease, enabled = less, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Decrease $label" }) { Text("−") }
        Text(value, modifier = Modifier.weight(1f))
        TextButton(increase, enabled = more, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Increase $label" }) { Text("+") }
    }
}

internal fun epubListPrefix(block: EpubBlock): String = if (block.kind != EpubBlockKind.LIST_ITEM) "" else
    block.listMarker?.let { if (it.ordered) "${it.ordinal}. " else "• " } ?: "• "

@Composable
private fun ChapterBody(reader: EpubReaderController, ready: EpubReaderState.Ready, settings: EpubReaderSettings, modifier: Modifier) {
    val list = rememberLazyListState()
    val layouts = remember { mutableStateMapOf<Int, TextLayoutResult>() }
    val media by reader.media.state.collectAsState()
    val linkColor = MaterialTheme.colors.primary
    val bitmaps by produceState<Map<EpubImage, ImageBitmap>>(emptyMap(), media) {
        val readyImages = media.filterValues { it is EpubMediaState.Ready }
        value = value.filterKeys { it in readyImages }
        for ((image, state) in readyImages) {
            if (image in value) continue
            val bitmap = try { epubImageBitmap((state as EpubMediaState.Ready).raster) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { continue }
            currentCoroutineContext().ensureActive()
            value = value + (image to bitmap)
        }
    }
    var restored by remember { mutableStateOf(false) }
    // Reset visible media on replacement; do not retain image jobs from an old chapter/layout.
    DisposableEffect(ready.ticket) { onDispose { reader.visibleMedia(ready.ticket, emptyList()) } }
    LaunchedEffect(ready.ticket) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { ready.chapter.blocks.getOrNull(it.index)?.image }.distinct().take(EpubImagePolicy.RETAINED) }
            .collect { reader.visibleMedia(ready.ticket, it) }
    }
    LaunchedEffect(ready.ticket) {
        val (block, points) = ready.initialPosition
        list.scrollToItem(block)
        if (points > 0 && ready.chapter.blocks[block].image == null && ready.chapter.blocks[block].kind != EpubBlockKind.SEPARATOR) {
            val layout = snapshotFlow { layouts[block] }.filterNotNull().first()
            val utf16 = ready.chapter.blocks[block].text.epubUtf16(points) + epubListPrefix(ready.chapter.blocks[block]).length
            val line = layout.getLineForOffset(utf16.coerceAtMost(layout.layoutInput.text.length))
            // Restore inside the semantic line, not a rounded boundary shared with its predecessor.
            val lineTop = ceil(layout.getLineTop(line)).toInt().coerceAtLeast(0)
            list.scrollToItem(block, lineTop + if (line > 0) 1 else 0)
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
            val layout = layouts[index]
            // Loading presentation is never an authoritative saved position.
            if (block.image != null && (media[block.image] == null || (media[block.image] is EpubMediaState.Ready && bitmaps[block.image] == null))) return@collect
            val points = if (block.image != null || block.kind == EpubBlockKind.SEPARATOR) 0 else {
                if (layout == null) return@collect
                val line = layout.getLineForVerticalPosition(pixels.toFloat())
                block.text.epubPointAtUtf16((layout.getLineStart(line) - epubListPrefix(block).length).coerceAtLeast(0))
            }
            if (!list.canScrollForward && list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == ready.chapter.blocks.lastIndex) {
                reader.report(ready.ticket, ready.chapter.blocks.lastIndex, ready.chapter.blocks.last().codePoints)
            } else reader.report(ready.ticket, index, points)
        }
    }
    LazyColumn(modifier, state = list, userScrollEnabled = restored, contentPadding = PaddingValues(horizontal = settings.margin.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(ready.chapter.blocks, key = { index, _ -> index }) { index, block ->
            if (block.kind == EpubBlockKind.SEPARATOR) Divider(Modifier.padding(vertical = 12.dp))
            else if (block.image != null) LocalImage(block.image, bitmaps[block.image])
            else {
                val text = remember(block, ready.ticket, linkColor) {
                    buildAnnotatedString {
                        append(epubListPrefix(block))
                        for ((runIndex, run) in block.runs.withIndex()) {
                            val style = SpanStyle(fontWeight = if (run.strong) FontWeight.Bold else null,
                                fontStyle = if (run.emphasis) FontStyle.Italic else null)
                            withStyle(style) {
                                val target = run.target
                                if (target == null) append(run.text)
                                else withLink(LinkAnnotation.Clickable("internal-$runIndex", TextLinkStyles(style = SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, color = linkColor))) {
                                    reader.navigate(target)
                                }) { append(run.text) }
                            }
                        }
                    }
                }
                val size = settings.fontSize * when (block.kind) { EpubBlockKind.HEADING -> when (block.headingLevel) { 1 -> 1.5f; 2 -> 1.3f; 3 -> 1.15f; else -> 1f }; EpubBlockKind.CAPTION -> .9f; else -> 1f }
                Text(text, style = MaterialTheme.typography.body1.copy(fontSize = size.sp, lineHeight = (size * settings.lineSpacingPercent / 100).sp,
                    fontWeight = if (block.kind == EpubBlockKind.HEADING) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (block.kind == EpubBlockKind.QUOTE || block.kind == EpubBlockKind.CAPTION) FontStyle.Italic else FontStyle.Normal,
                    fontFamily = if (block.kind == EpubBlockKind.PREFORMATTED) FontFamily.Monospace else FontFamily.Default),
                    modifier = Modifier.fillMaxWidth().padding(start = (if (block.kind == EpubBlockKind.QUOTE) 16 else (block.listMarker?.depth ?: 0) * 12).dp), onTextLayout = {
                        layouts[index] = it
                        if (layouts.size > 12) layouts.keys.filter { key -> key != index }.maxByOrNull { key -> kotlin.math.abs(key - index) }?.let(layouts::remove)
                    })
            }
        }
    }
}

@Composable
private fun LocalImage(image: EpubImage, bitmap: ImageBitmap?) {
    // Reserved height is stable through loading/failure: no delayed height jump above the reader.
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, contentDescription = image.alt.ifBlank { "EPUB illustration" }, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(image.alt.ifBlank { "Image unavailable or unsupported" }, style = MaterialTheme.typography.body2)
        }
    }
}
