// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import org.infinilect.app.ReaderAppearance
import org.infinilect.app.ReaderAppearanceEffect
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

private const val EPUB_LOADING_DELAY_MILLIS = 180L

private enum class EpubKeyboardStep { LINE_UP, LINE_DOWN, VIEWPORT_UP, VIEWPORT_DOWN, TAP_PREVIOUS, TAP_NEXT }
/** One presentation callback, not another position owner or a navigation queue. */
private class EpubKeyboardScroll { var request: ((EpubKeyboardStep) -> Unit)? = null }

@Composable
internal fun EpubReader(
    reader: EpubReaderController, saveFailed: Boolean, onBack: () -> Unit, backLabel: String,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    publicationActions: (@Composable () -> Unit)? = null,
    notices: (@Composable () -> Unit)? = null,
    appearanceChanged: (ReaderAppearance?) -> Unit = {},
) {
    DisposableEffect(reader) {
        // Presentation replacement restores the live semantic locator, never chapter entry.
        reader.presentationChanged()
        onDispose { reader.flush() }
    }
    val state by reader.state.collectAsState()
    val displayed by reader.displayed.collectAsState()
    val loading by reader.loading.collectAsState()
    var showLoading by remember(reader) { mutableStateOf(false) }
    LaunchedEffect(loading) {
        showLoading = false
        if (loading) { delay(EPUB_LOADING_DELAY_MILLIS); showLoading = true }
    }
    val progression by reader.progression.collectAsState()
    val settings by reader.settings.collectAsState()
    val settingsSaveFailed by reader.settingsSaveFailed.collectAsState()
    val dark = epubReaderIsDark(settings.theme, isSystemInDarkTheme())
    val controls = remember(reader) { EpubReaderControls() }
    val readingFocus = remember(reader) { FocusRequester() }
    val keyboardScroll = remember(reader) { EpubKeyboardScroll() }
    var readingHasFocus by remember(reader) { mutableStateOf(false) }
    val contentsFocus = remember(reader) { FocusRequester() }
    val settingsFocus = remember(reader) { FocusRequester() }
    var returnFocus by remember(reader) { mutableStateOf<EpubReaderPanel?>(null) }
    val closePanel = { returnFocus = controls.panel; controls.closePanel() }
    LaunchedEffect(controls.panel) {
        if (controls.panel == null) {
            when (returnFocus) { EpubReaderPanel.CONTENTS -> contentsFocus.requestFocus(); EpubReaderPanel.SETTINGS -> settingsFocus.requestFocus(); null -> Unit }
            returnFocus = null
        }
    }
    backHandler(controls.visible || controls.panel != null) {
        if (controls.panel != null) closePanel() else controls.dismiss()
    }
    val current = (state as? EpubReaderState.Ready) ?: displayed
    val error = (state as? EpubReaderState.Error)?.message ?: current?.error
    LaunchedEffect(reader, current?.presentation, controls.visible) {
        if (current != null) {
            withFrameNanos { }
            if (controls.panel == null && !readingHasFocus) readingFocus.requestFocus()
        }
    }
    val density = LocalDensity.current
    var previousLayout by remember(reader) { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    LaunchedEffect(reader, density.density, density.fontScale) {
        previousLayout?.let { previous ->
            val layout = Triple(previous.first, density.density, density.fontScale)
            if (previous != layout) { reader.presentationChanged(); previousLayout = layout }
        }
    }
    val colors = epubReaderColors(dark)
    ReaderAppearanceEffect(ReaderAppearance(dark, colors.background), appearanceChanged)
    MaterialTheme(colors = colors) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().onSizeChanged {
                val layout = Triple(it.width, density.density, density.fontScale)
                if (previousLayout != null && previousLayout != layout) reader.presentationChanged()
                previousLayout = layout
            }.epubReaderTap { action ->
                if (controls.panel == null) when (action) {
                    EpubTapAction.CONTROLS -> controls.toggle()
                    EpubTapAction.PREVIOUS -> keyboardScroll.request?.invoke(EpubKeyboardStep.TAP_PREVIOUS)
                    EpubTapAction.NEXT -> keyboardScroll.request?.invoke(EpubKeyboardStep.TAP_NEXT)
                }
            }.semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Toggle reading controls") { controls.toggle(); true },
                    CustomAccessibilityAction("Previous reading viewport") { keyboardScroll.request?.invoke(EpubKeyboardStep.TAP_PREVIOUS); true },
                    CustomAccessibilityAction("Next reading viewport") { keyboardScroll.request?.invoke(EpubKeyboardStep.TAP_NEXT); true },
                )
            }.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when {
                    event.key == Key.F10 -> { controls.toggle(); true }
                    event.key == Key.Escape -> {
                        if (controls.panel != null) closePanel() else if (!controls.dismiss()) onBack()
                        true
                    }
                    controls.panel == null && event.isAltPressed && event.key == Key.DirectionLeft -> {
                        if (!loading) reader.previous(); true
                    }
                    controls.panel == null && event.isAltPressed && event.key == Key.DirectionRight -> {
                        if (!loading) reader.next(); true
                    }
                    controls.panel == null && !event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed &&
                        (event.key == Key.PageUp || event.key == Key.PageDown) -> {
                        keyboardScroll.request?.invoke(if (event.key == Key.PageUp) EpubKeyboardStep.VIEWPORT_UP else EpubKeyboardStep.VIEWPORT_DOWN)
                        keyboardScroll.request != null
                    }
                    else -> false
                }
            }.onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || controls.panel != null || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed) false
                else {
                    val step = when (event.key) {
                        Key.DirectionUp -> EpubKeyboardStep.LINE_UP
                        Key.DirectionDown -> EpubKeyboardStep.LINE_DOWN
                        Key.PageUp -> EpubKeyboardStep.VIEWPORT_UP
                        Key.PageDown -> EpubKeyboardStep.VIEWPORT_DOWN
                        else -> null
                    }
                    if (step == null || keyboardScroll.request == null) false
                    else { keyboardScroll.request?.invoke(step); true }
                }
            }.focusRequester(readingFocus).onFocusChanged { readingHasFocus = it.hasFocus }.focusable(), contentAlignment = Alignment.TopCenter) {
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (error == null) CircularProgressIndicator()
                    }
                } else key(reader, current.presentation) {
                    // Chrome is an overlay: opening/closing it never changes list geometry.
                    ChapterBody(reader, current, settings, keyboardScroll, Modifier.widthIn(max = 760.dp).fillMaxSize())
                }
                Column(Modifier.align(Alignment.TopCenter).widthIn(max = 760.dp).fillMaxWidth()) {
                    if (controls.visible) Surface(Modifier.epubTapBarrier(), elevation = 0.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            EpubIconButton(backLabel, EpubControlIcon.BACK, onBack)
                            Text(reader.document.metadata.title, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                style = MaterialTheme.typography.subtitle1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            EpubIconButton("Hide reading controls", EpubControlIcon.CLOSE, controls::toggle)
                        }
                    }
                    if (saveFailed || settingsSaveFailed || error != null || notices != null) Surface(Modifier.epubTapBarrier(), elevation = 0.dp) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            if (saveFailed) Text("Reading position could not be saved on this device.", color = MaterialTheme.colors.error)
                            if (settingsSaveFailed) Text("Reading settings could not be saved on this device.", color = MaterialTheme.colors.error)
                            error?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(it, color = MaterialTheme.colors.error, modifier = Modifier.weight(1f))
                                    if (reader.canRetry) TextButton(onClick = reader::retry) { Text("Retry") }
                                }
                            }
                            notices?.invoke()
                        }
                    }
                    if (showLoading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).epubTapBarrier().semantics { contentDescription = "Preparing EPUB passage" })
                }
                if (controls.visible) {
                    EpubNavigationBar((current?.spineIndex ?: 0) + 1, reader.document.spine.size, progression,
                        (current?.spineIndex ?: 0) > 0 && !loading, current != null && current.spineIndex + 1 < reader.document.spine.size && !loading,
                        { current?.let { reader.chapter(it.spineIndex - 1) } }, { current?.let { reader.chapter(it.spineIndex + 1) } },
                        { controls.open(EpubReaderPanel.CONTENTS) }, { controls.open(EpubReaderPanel.SETTINGS) }, contentsFocus, settingsFocus,
                        modifier = Modifier.align(Alignment.BottomCenter))
                }
                when (controls.panel) {
                    EpubReaderPanel.CONTENTS -> EpubContentsPanel(reader, current, maxHeight, closePanel)
                    EpubReaderPanel.SETTINGS -> EpubSettingsPanel(settings, reader::presentationChanged, maxHeight, publicationActions, closePanel)
                    null -> Unit
                }
            }
        }
    }
}

internal fun epubListPrefix(block: EpubBlock): String = if (block.kind != EpubBlockKind.LIST_ITEM) "" else
    block.listMarker?.let { if (it.ordered) "${it.ordinal}. " else "• " } ?: "• "

private data class EpubScrollSample(val index: Int, val pixels: Int, val scrolling: Boolean, val key: Any?, val ticket: Long, val layout: TextLayoutResult?)

@Composable
private fun ChapterBody(reader: EpubReaderController, ready: EpubReaderState.Ready, settings: EpubReaderSettings, keyboard: EpubKeyboardScroll, modifier: Modifier) {
    val latestReady by rememberUpdatedState(ready)
    val list = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val scrollJob = remember { arrayOfNulls<Job>(1) }
    val density = LocalDensity.current
    val layouts = remember { mutableStateMapOf<Int, TextLayoutResult>() }
    fun retainLayout(global: Int, layout: TextLayoutResult) {
        layouts[global] = layout
        val first = latestReady.chapter.startBlock + list.firstVisibleItemIndex
        val initial = latestReady.chapter.startBlock + latestReady.initialPosition.first
        if (layouts.size > 12) layouts.keys.filter { it != first && it != initial }
            .maxByOrNull { kotlin.math.abs(it - first) }?.let(layouts::remove)
    }
    val bufferStart = remember { intArrayOf(ready.chapter.startBlock) }
    SideEffect {
        if (bufferStart[0] != ready.chapter.startBlock) {
            // LazyColumn's nearest-key lookup may miss a 128-item rebase. Rebase
            // its transient local index explicitly, preserving the exact visible pixel.
            val global = bufferStart[0] + list.firstVisibleItemIndex
            list.requestScrollToItem((global - ready.chapter.startBlock).coerceIn(ready.chapter.blocks.indices), list.firstVisibleItemScrollOffset)
            bufferStart[0] = ready.chapter.startBlock
        }
    }
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
    val latestMedia by rememberUpdatedState(media)
    val latestBitmaps by rememberUpdatedState(bitmaps)
    var restored by remember { mutableStateOf(false) }
    var userScrolled by remember { mutableStateOf(false) }
    DisposableEffect(keyboard, list, settings, density, ready.ticket) {
        val command: (EpubKeyboardStep) -> Unit = command@{ step ->
            if (restored) {
                val tap = step == EpubKeyboardStep.TAP_NEXT || step == EpubKeyboardStep.TAP_PREVIOUS
                if (tap && (reader.loading.value || scrollJob[0]?.isActive == true)) return@command
                // A rolling append can update the model before LazyColumn rebases.
                // Never interpret the previous local indices as a new chapter boundary.
                val key = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }?.key
                if (tap && key != latestReady.chapter.startBlock + list.firstVisibleItemIndex) return@command
                val forward = step == EpubKeyboardStep.TAP_NEXT
                if (tap && !(if (forward) list.canScrollForward else list.canScrollBackward)) {
                    reader.scrollBoundary(latestReady.ticket, forward)
                    return@command
                }
                val line = with(density) { (settings.fontSize * settings.lineSpacingPercent / 100f).sp.toPx() }
                val delta = when (step) {
                    EpubKeyboardStep.LINE_UP -> -line
                    EpubKeyboardStep.LINE_DOWN -> line
                    EpubKeyboardStep.VIEWPORT_UP, EpubKeyboardStep.TAP_PREVIOUS -> -list.layoutInfo.viewportSize.height * .85f
                    EpubKeyboardStep.VIEWPORT_DOWN, EpubKeyboardStep.TAP_NEXT -> list.layoutInfo.viewportSize.height * .85f
                }
                scrollJob[0]?.cancel()
                scrollJob[0] = scrollScope.launch { userScrolled = true; list.scrollBy(delta) }
            }
        }
        keyboard.request = command
        onDispose {
            if (keyboard.request === command) keyboard.request = null
            scrollJob[0]?.cancel()
        }
    }
    // Reset visible media on replacement; do not retain image jobs from an old chapter/layout.
    DisposableEffect(ready.ticket) { onDispose { reader.visibleMedia(ready.ticket, emptyList()) } }
    LaunchedEffect(ready.presentation) {
        snapshotFlow {
            val frame = latestReady
            frame.ticket to list.layoutInfo.visibleItemsInfo.mapNotNull {
                frame.chapter.blocks.getOrNull(it.index)?.image?.takeIf { _ -> it.key == frame.chapter.startBlock + it.index }
            }.distinct().take(EpubImagePolicy.RETAINED)
        }.collect { (ticket, images) -> reader.visibleMedia(ticket, images) }
    }
    LaunchedEffect(ready.presentation) {
        val (block, points) = ready.initialPosition
        list.scrollToItem(block)
        if (points > 0 && ready.chapter.blocks[block].image == null && ready.chapter.blocks[block].kind != EpubBlockKind.SEPARATOR) {
            val layout = snapshotFlow { layouts[ready.chapter.startBlock + block] }.filterNotNull().first()
            val utf16 = ready.chapter.blocks[block].text.epubUtf16(points) + epubListPrefix(ready.chapter.blocks[block]).length
            val line = layout.getLineForOffset(utf16.coerceAtMost(layout.layoutInput.text.length))
            // Restore inside the semantic line, not a rounded boundary shared with its predecessor.
            val lineTop = ceil(layout.getLineTop(line)).toInt().coerceAtLeast(0)
            list.scrollToItem(block, lineTop + if (line > 0) 1 else 0)
        }
        restored = true
    }
    var acknowledged by remember(ready.presentation) { mutableStateOf(false) }
    LaunchedEffect(ready.ticket, restored, media, bitmaps) {
        if (restored && !acknowledged) {
            val image = ready.chapter.blocks[ready.initialPosition.first].image
            if (image == null || media[image] is EpubMediaState.Unavailable || bitmaps[image] != null) {
                reader.presented(ready.ticket); acknowledged = true
            }
        }
    }
    LaunchedEffect(ready.presentation, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow {
            val key = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }?.key
            EpubScrollSample(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress, key, latestReady.ticket,
                layouts[latestReady.chapter.startBlock + list.firstVisibleItemIndex])
        }.collect { (index, pixels, scrolling, key, ticket, layout) ->
            val frame = latestReady
            // The first layout after a rolling rebase may still contain old local indices.
            // Global paragraph keys must agree before a callback can persist its locator.
            if (ticket != frame.ticket || key != frame.chapter.startBlock + index) return@collect
            if (scrolling) userScrolled = true
            if (!userScrolled) return@collect
            val block = frame.chapter.blocks.getOrNull(index) ?: return@collect
            // Loading presentation is never an authoritative saved position.
            if (block.image != null && (latestMedia[block.image] == null || (latestMedia[block.image] is EpubMediaState.Ready && latestBitmaps[block.image] == null))) return@collect
            val points = if (block.image != null || block.kind == EpubBlockKind.SEPARATOR) 0 else {
                if (layout == null) return@collect
                val line = layout.getLineForVerticalPosition(pixels.toFloat())
                block.text.epubPointAtUtf16((layout.getLineStart(line) - epubListPrefix(block).length).coerceAtLeast(0))
            }
            // A temporary buffer end is not the semantic chapter end. Reporting its
            // last paragraph makes the following rebase look like reverse movement
            // and can oscillate lookahead windows without any new user input.
            if (frame.chapter.endBlock == frame.chapter.totalBlocks && !list.canScrollForward && list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == frame.chapter.blocks.lastIndex) {
                reader.report(frame.ticket, frame.chapter.blocks.lastIndex, frame.chapter.blocks.last().codePoints)
            } else reader.report(frame.ticket, index, points)
        }
    }
    SelectionContainer {
    LazyColumn(modifier, state = list, userScrollEnabled = restored,
        contentPadding = PaddingValues(start = settings.margin.dp, end = settings.margin.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(ready.chapter.blocks, key = { index, _ -> ready.chapter.startBlock + index }) { index, block ->
            if (block.kind == EpubBlockKind.SEPARATOR) Divider(Modifier.padding(vertical = 12.dp))
            else if (block.image != null) LocalImage(block.image, bitmaps[block.image])
            else {
                // Borrow the result already owned by this composed text node. Placement
                // repins the actual first visible item after LazyColumn applies its new
                // indices; measurement can still observe the previous scroll position.
                val placedLayout = remember(block) { arrayOfNulls<TextLayoutResult>(1) }
                val text = remember(block, linkColor) {
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
                    fontFamily = when (block.kind) { EpubBlockKind.PREFORMATTED -> FontFamily.Monospace; EpubBlockKind.HEADING -> FontFamily.Default; else -> FontFamily.Serif }),
                    modifier = Modifier.fillMaxWidth().padding(start = (if (block.kind == EpubBlockKind.QUOTE) 16 else (block.listMarker?.depth ?: 0) * 12).dp)
                        .onGloballyPositioned {
                            if (index == list.firstVisibleItemIndex) placedLayout[0]?.let { retainLayout(ready.chapter.startBlock + index, it) }
                        }, onTextLayout = {
                        placedLayout[0] = it
                        retainLayout(ready.chapter.startBlock + index, it)
                    })
            }
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
