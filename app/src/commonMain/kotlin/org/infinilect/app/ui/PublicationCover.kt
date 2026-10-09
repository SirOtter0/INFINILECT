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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

@Composable
private fun coverState(id: PublicationId, covers: PublicationCovers?): CoverState {
    val state by produceState(CoverState(), id, covers) {
        if (covers == null) return@produceState
        val lease = covers.acquire(id)
        try { lease.state.collect { value = it } }
        finally { withContext(NonCancellable) { covers.release(lease) } }
    }
    return state
}

/** Portrait artwork surface shared by Library and History. Fit, never stretch or crop. */
@Composable
private fun CoverArtwork(publication: Publication, state: CoverState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = listOf(Color(0xff294d55), Color(0xff51466c), Color(0xff6b493d), Color(0xff40563e))
    Box(modifier.clearAndSetSemantics {}.background(colors[coverPaletteIndex(publication.id)])) {
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
    val cover = coverState(publication.id, covers)
    val showDetails = { details(cover.format) }
    var menu by remember(publication.id) { mutableStateOf(false) }
    val interaction = Modifier.combinedClickable(
        onClick = { if (selecting) toggle() else showDetails() },
        onLongClick = select, onClickLabel = if (selecting) "Toggle selection" else "Publication details",
        onLongClickLabel = "Select publication")
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
            contentDescription = "Cover for ${publication.title}"
            stateDescription = listOfNotNull(if (selected) "Selected" else if (selecting) "Not selected" else null,
                progress?.let { "${(it.progression * 100).toInt()}% read" }).joinToString(" · ")
            customActions = listOf(CustomAccessibilityAction(if (selected) "Deselect publication" else "Select publication") { toggle(); true },
                CustomAccessibilityAction("Publication details") { showDetails(); true },
                CustomAccessibilityAction("Continue reading") { open(); true })
        }
    Surface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).then(interaction), color = MaterialTheme.colors.surface) {
        Box(Modifier.aspectRatio(2f / 3f)) {
            CoverArtwork(publication, cover, Modifier.fillMaxSize())
            Text(publication.title, color = Color.White, style = MaterialTheme.typography.subtitle2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = COVER_TITLE_MIN_SCRIM), Color.Black.copy(alpha = .94f))))
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

internal fun historyPosition(progress: ReadingProgress?): String? = progress?.let {
    val location = when (val locator = it.locator) {
        is ReadingLocator.Page -> "Page ${locator.pageIndex + 1} · "
        // No chapter label/ordinal is held in the persisted EPUB locator; never invent one.
        else -> ""
    }
    "$location${(it.progression * 100).toInt()}% read"
}

@Composable
internal fun HistoryPublication(publication: Publication, covers: PublicationCovers?, progress: ReadingProgress?,
    enabled: Boolean, open: () -> Unit, details: (PublicationFormat?) -> Unit, remove: () -> Unit) {
    val cover = coverState(publication.id, covers)
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(onClickLabel = "Continue reading", onClick = open)
        .semantics { contentDescription = "Resume ${publication.title}" }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(48.dp).height(72.dp).clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Publication details", onClick = { details(cover.format) })
            .semantics { contentDescription = "Details for ${publication.title}" }) {
            CoverArtwork(publication, cover, Modifier.fillMaxSize(), compact = true)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(publication.title, style = MaterialTheme.typography.subtitle2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "), style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            historyPosition(progress)?.let { Text(it, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = .75f)) }
        }
        IconButton(remove, Modifier.size(48.dp).semantics { contentDescription = "Remove ${publication.title} from History" }, enabled = enabled) {
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
    val focus = remember { FocusRequester() }
    LaunchedEffect(expanded) { if (expanded) { withFrameNanos {}; focus.requestFocus() } }
    DropdownMenu(expanded, dismiss, modifier = Modifier.onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { dismiss(); true } else false
    }, properties = PopupProperties(focusable = true)) {
        DropdownMenuItem({ dismiss(); select() }, Modifier.focusRequester(focus), enabled = enabled) { Text("Select publication") }
        DropdownMenuItem({ dismiss(); details() }) { Text("Publication details") }
        DropdownMenuItem({ dismiss(); open() }) { Text("Continue reading") }
        DropdownMenuItem({ dismiss(); remove() }, enabled = enabled) { Text("Remove from Library") }
    }
}
