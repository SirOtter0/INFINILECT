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
internal fun CollectionPublication(publication: Publication, covers: PublicationCovers?, progress: ReadingProgress?,
    history: Boolean, lastOpened: String? = null, enabled: Boolean = true,
    open: () -> Unit, details: (PublicationFormat?) -> Unit, remove: () -> Unit) {
    val cover = coverState(publication.id, covers)
    val showDetails = { details(cover.format) }
    var menu by remember(publication.id) { mutableStateOf(false) }
    val interaction = Modifier.combinedClickable(onClick = open, onLongClick = { menu = true },
        onClickLabel = "Continue reading", onLongClickLabel = "Publication actions")
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
            customActions = listOf(CustomAccessibilityAction("Publication details") { showDetails(); true },
                CustomAccessibilityAction(if (history) "Remove from History" else "Remove from Library") { if (enabled) remove(); enabled })
        }
    Surface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).then(interaction), color = MaterialTheme.colors.surface) {
        if (history) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoverArtwork(publication, cover, Modifier.width(56.dp).height(84.dp).clip(MaterialTheme.shapes.small), compact = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(publication.title, style = MaterialTheme.typography.subtitle1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "), style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                lastOpened?.let { Text(it, style = MaterialTheme.typography.caption) }
                progress?.let { Text("${(it.progression * 100).toInt()}% read", style = MaterialTheme.typography.caption) }
                TextButton(open, Modifier.heightIn(min = 48.dp)) { Text("Continue reading") }
            }
            PublicationMenu(publication.title, history, enabled, menu, { menu = it }, open, showDetails, remove)
        } else Box(Modifier.aspectRatio(2f / 3f)) {
            CoverArtwork(publication, cover, Modifier.fillMaxSize())
            Text(publication.title, color = Color.White, style = MaterialTheme.typography.subtitle2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = COVER_TITLE_MIN_SCRIM), Color.Black.copy(alpha = .94f))))
                    .padding(12.dp, 12.dp, 12.dp, 16.dp))
            cover.format?.let { Text(it.name, color = Color.White, style = MaterialTheme.typography.overline,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .72f), MaterialTheme.shapes.small).padding(4.dp)) }
            Box(Modifier.align(Alignment.TopEnd)) { PublicationMenu(publication.title, history, enabled, menu, { menu = it }, open, showDetails, remove) }
            progress?.let { LinearProgressIndicator(it.progression.toFloat(), Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = Color(0xffa8e6d5), backgroundColor = Color.Black.copy(alpha = .5f)) }
        }
    }
}

@Composable
private fun PublicationMenu(title: String, history: Boolean, enabled: Boolean, expanded: Boolean,
    expand: (Boolean) -> Unit, open: () -> Unit, details: () -> Unit, remove: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(expanded) {
        if (expanded) { withFrameNanos {}; focus.requestFocus() }
    }
    Box {
        IconButton({ expand(!expanded) }, Modifier.size(48.dp).semantics { contentDescription = "Actions for $title" }) {
            // A small scrim keeps the action discoverable over bright artwork in either theme.
            Box(Modifier.size(32.dp).background(Color.Black.copy(alpha = .65f), MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Text("⋮", color = Color.White, style = MaterialTheme.typography.h6, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        DropdownMenu(expanded, { expand(false) }, modifier = Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { expand(false); true } else false
        }, properties = PopupProperties(focusable = true)) {
            DropdownMenuItem({ expand(false); open() }, Modifier.focusRequester(focus)) { Text("Continue reading") }
            DropdownMenuItem({ expand(false); details() }, Modifier.semantics { contentDescription = "Details for $title" }) { Text("Publication details") }
            DropdownMenuItem({ expand(false); remove() }, enabled = enabled) { Text(if (history) "Remove from History" else "Remove from Library") }
        }
    }
}
