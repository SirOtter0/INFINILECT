// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package org.infinilect.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.infinilect.app.covers.*
import org.infinilect.core.*

internal const val COVER_TITLE_MIN_SCRIM = .72f
internal fun libraryColumns(widthDp: Float): Int = if (widthDp < 320) 1 else maxOf(2, ((widthDp - 20) / 180).toInt())
internal fun coverPaletteIndex(id: PublicationId): Int = (id.resultCoverKey().hashCode() and Int.MAX_VALUE) % 4
internal fun coverInitial(title: String): String {
    val text = title.trimStart()
    val length = if (text.length >= 2 && text[0].code in 0xd800..0xdbff && text[1].code in 0xdc00..0xdfff) 2 else 1
    return text.take(length).uppercase()
}
private fun PublicationId.resultCoverKey() = "${sourceId.value}\u0000$localId"
private val coverColors = listOf(Color(0xff294d55), Color(0xff51466c), Color(0xff6b493d), Color(0xff40563e))
private val coverTitleColors = listOf(Color.Black.copy(alpha = COVER_TITLE_MIN_SCRIM), Color.Black.copy(alpha = .94f))

@Composable
internal fun coverState(id: PublicationId, covers: PublicationCovers?, viewport: CoverViewport? = null): CoverState {
    val state by key(id, covers) { produceState(covers?.peek(id) ?: CoverState(), id, covers, viewport) {
        if (covers == null) return@produceState
        suspend fun present() {
            val lease = covers.acquire(id)
            try { lease.state.collect { value = it } }
            finally { withContext(NonCancellable) { covers.release(lease) } }
        }
        if (viewport == null) present() else {
            // One subscription, at most one child, no transformLatest channel or false-state job.
            // Join releases before reacquiring; the cache itself joins non-cooperative decodes.
            var presentation: Job? = null
            try { viewport.visible.collect { visible ->
                presentation?.cancelAndJoin()
                presentation = if (visible) launch { present() } else null
            } } finally { presentation?.cancel() }
        }
    } }
    return state
}

/** Portrait artwork surface shared by Library and History. Fit, never stretch or crop. */
@Composable
private fun CoverArtwork(publication: Publication, state: CoverState, modifier: Modifier = Modifier, compact: Boolean = false) {
    Box(modifier.clearAndSetSemantics {}.background(coverColors[coverPaletteIndex(publication.id)])) {
        if (state.image != null) Image(state.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Column(Modifier.fillMaxSize().padding(if (compact) 6.dp else 12.dp), verticalArrangement = Arrangement.Center) {
            Text(coverInitial(publication.title), color = Color.White, style = if (compact) MaterialTheme.typography.h5 else MaterialTheme.typography.h2,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact && publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "), color = Color.White.copy(alpha = .85f),
                style = MaterialTheme.typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun LibraryCover(publication: Publication, covers: PublicationCovers?, progress: ReadingProgress?,
    selected: Boolean, selecting: Boolean, enabled: Boolean,
    open: () -> Unit, details: (PublicationFormat?) -> Unit, select: () -> Unit, toggle: () -> Unit, remove: () -> Unit) {
    val strings = LocalAppStrings.current
    val viewport = remember(publication.id, covers) { covers?.let { CoverViewport() } }
    val cover = coverState(publication.id, covers, viewport)
    val titleBrush = remember { Brush.verticalGradient(coverTitleColors) }
    val showDetails = { details(cover.format) }
    var menu by remember(publication.id) { mutableStateOf(false) }
    val interaction = Modifier.combinedClickable(
        onClick = { if (selecting) toggle() else showDetails() },
        onLongClick = select, onClickLabel = if (selecting) strings[UiText.UI_TOGGLE_SELECTION] else strings[UiText.UI_PUBLICATION_DETAILS],
        onLongClickLabel = strings[UiText.UI_SELECT_PUBLICATION])
        .pointerInput(publication.id) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        menu = true
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || it.key == Key.F10 && it.isShiftPressed)) { menu = true; true } else false
        }.semantics {
            this.selected = selected
            contentDescription = strings.format(UiText.UI_COVER_FOR_1_S, publication.title)
            stateDescription = listOfNotNull(if (selected) strings[UiText.UI_SELECTED] else if (selecting) strings[UiText.UI_NOT_SELECTED] else null,
                progress?.let { strings.format(UiText.UI_1_S_READ, (it.progression * 100).toInt()) }).joinToString(" · ")
            customActions = listOf(CustomAccessibilityAction(if (selected) strings[UiText.UI_DESELECT_PUBLICATION] else strings[UiText.UI_SELECT_PUBLICATION]) { toggle(); true },
                CustomAccessibilityAction(strings[UiText.UI_PUBLICATION_DETAILS]) { showDetails(); true },
                CustomAccessibilityAction(strings[UiText.CONTINUE_READING]) { open(); true })
        }
    Surface(Modifier.fillMaxWidth().then(viewport?.modifier ?: Modifier), shape = MaterialTheme.shapes.small, color = MaterialTheme.colors.surface) {
        Box(Modifier.aspectRatio(2f / 3f).then(interaction)) {
            CoverArtwork(publication, cover, Modifier.fillMaxSize())
            Text(publication.title, color = Color.White, style = MaterialTheme.typography.subtitle2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(titleBrush)
                    .padding(12.dp, 12.dp, 12.dp, 16.dp))
            progress?.let { LinearProgressIndicator(it.progression.toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = Color(0xffa8e6d5), backgroundColor = Color.Black.copy(alpha = .5f)) }
            if (selected) {
                Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colors.primary, MaterialTheme.shapes.small))
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).background(MaterialTheme.colors.primary, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                    Text("✓", color = MaterialTheme.colors.onPrimary, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            // Secondary-pointer/keyboard menu is available without permanent cover chrome.
            Box(Modifier.align(Alignment.TopEnd)) {
                CoverContextMenu(menu, { menu = false }, enabled, select, open, showDetails, remove)
            }
        }
    }
}

internal fun historyPosition(progress: ReadingProgress?, strings: LocalizedStrings = LocalizedStrings()): String? = progress?.let {
    val read = strings.format(UiText.UI_1_S_READ, (it.progression * 100).toInt())
    when (val locator = it.locator) {
        is ReadingLocator.Page -> strings.format(UiText.UI_PAGE_1_S_2_S, locator.pageIndex + 1, read)
        else -> read // The stored EPUB locator supplies no display chapter/page number.
    }
}

@Composable
internal fun HistoryPublication(publication: Publication, covers: PublicationCovers?, progress: ReadingProgress?,
    enabled: Boolean, open: () -> Unit, details: (PublicationFormat?) -> Unit, remove: () -> Unit) {
    val strings = LocalAppStrings.current
    val viewport = remember(publication.id, covers) { covers?.let { CoverViewport() } }
    val cover = coverState(publication.id, covers, viewport)
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(onClickLabel = strings[UiText.CONTINUE_READING], onClick = open)
        .semantics { contentDescription = strings.format(UiText.UI_RESUME_1_S, publication.title) }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(48.dp).height(72.dp).then(viewport?.modifier ?: Modifier).clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = strings[UiText.UI_PUBLICATION_DETAILS], onClick = { details(cover.format) })
            .semantics { contentDescription = strings.format(UiText.UI_DETAILS_FOR_1_S, publication.title) }) {
            CoverArtwork(publication, cover, Modifier.fillMaxSize(), compact = true)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(publication.title, style = MaterialTheme.typography.subtitle2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "), style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            historyPosition(progress, strings)?.let { Text(it, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = .75f)) }
        }
        IconButton(remove, Modifier.size(48.dp).semantics { contentDescription = strings.format(UiText.UI_REMOVE_1_S_FROM_HISTORY, publication.title) }, enabled = enabled) {
            val color = MaterialTheme.colors.onSurface.copy(alpha = .75f)
            Canvas(Modifier.size(22.dp).clearAndSetSemantics {}) {
                val stroke = 1.5.dp.toPx()
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width*.15f,size.height*.25f), androidx.compose.ui.geometry.Offset(size.width*.85f,size.height*.25f), stroke)
                drawRect(color, androidx.compose.ui.geometry.Offset(size.width*.27f,size.height*.3f), androidx.compose.ui.geometry.Size(size.width*.46f,size.height*.56f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width*.35f,size.height*.12f), androidx.compose.ui.geometry.Offset(size.width*.65f,size.height*.12f), stroke)
            }
        }
    }
}

@Composable
internal fun DetailsCover(publication: Publication, covers: PublicationCovers?) {
    CoverArtwork(publication, coverState(publication.id, covers), Modifier.size(96.dp, 144.dp).clip(MaterialTheme.shapes.small))
}

@Composable
private fun CoverContextMenu(expanded: Boolean, dismiss: () -> Unit, enabled: Boolean,
    select: () -> Unit, open: () -> Unit, details: () -> Unit, remove: () -> Unit) {
    val strings = LocalAppStrings.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(expanded) { if (expanded) { withFrameNanos {}; focus.requestFocus() } }
    DropdownMenu(expanded, dismiss, modifier = Modifier.onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { dismiss(); true } else false
    }, properties = PopupProperties(focusable = true)) {
        DropdownMenuItem({ dismiss(); select() }, Modifier.focusRequester(focus), enabled = enabled) { Text(strings[UiText.UI_SELECT_PUBLICATION]) }
        DropdownMenuItem({ dismiss(); details() }) { Text(strings[UiText.UI_PUBLICATION_DETAILS]) }
        DropdownMenuItem({ dismiss(); open() }) { Text(strings[UiText.CONTINUE_READING]) }
        DropdownMenuItem({ dismiss(); remove() }, enabled = enabled) { Text(strings[UiText.UI_REMOVE_FROM_LIBRARY]) }
    }
}

@Composable
internal fun HomeCover(publication: Publication, covers: PublicationCovers?, progress: ReadingProgress?,
    resume: Boolean, modifier: Modifier = Modifier, action: (PublicationFormat?) -> Unit) {
    val strings = LocalAppStrings.current
    val viewport = remember(publication.id, covers) { covers?.let { CoverViewport() } }
    val cover = coverState(publication.id, covers, viewport)
    val titleBrush = remember { Brush.verticalGradient(coverTitleColors) }
    Surface(modifier.width(144.dp).aspectRatio(2f/3f).then(viewport?.modifier ?: Modifier), shape = MaterialTheme.shapes.small) {
        Box(Modifier.fillMaxSize().clickable(onClickLabel = if (resume) strings[UiText.CONTINUE_READING] else strings[UiText.UI_PUBLICATION_DETAILS], onClick = { action(cover.format) })
        .semantics {
            contentDescription = if (resume) strings.format(UiText.UI_CONTINUE_1_S, publication.title) else strings.format(UiText.UI_DETAILS_FOR_1_S, publication.title)
            progress?.let { stateDescription = strings.format(UiText.UI_1_S_READ, (it.progression*100).toInt()) }
        }) {
            CoverArtwork(publication, cover, Modifier.fillMaxSize())
            Text(publication.title, color=Color.White, style=MaterialTheme.typography.subtitle2, maxLines=2, overflow=TextOverflow.Ellipsis,
                modifier=Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(titleBrush)
                    .padding(12.dp,12.dp,12.dp,16.dp))
            progress?.let { LinearProgressIndicator(it.progression.toFloat(),Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color=Color(0xffa8e6d5),backgroundColor=Color.Black.copy(alpha=.5f)) }
        }
    }
}
