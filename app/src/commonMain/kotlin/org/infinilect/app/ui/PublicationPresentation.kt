// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.infinilect.core.*
import kotlinx.coroutines.CancellationException

internal fun PublicationSnapshot.displayPublication() = Publication(id, title, type, authors, languages = languages, sourceUrl = sourceUrl, rights = rights)

internal fun publicationProgress(records: Collection<ReadingProgress>, id: PublicationId): ReadingProgress? =
    records.filter { it.id.publicationId == id }.maxByOrNull { it.updatedAtEpochMillis }
internal fun publicationFormat(formats: List<PublicationFormat>) = formats.distinct().joinToString(" · ") { it.name }.ifEmpty { "Format checked when opening" }

/** Live records win. A bounded, cancellable read-only lookup fills the restart-only gap. */
@Composable
internal fun detailsProgress(application: org.infinilect.app.ApplicationSession, id: PublicationId, known: ReadingProgress?): ReadingProgress? {
    val value by key(id) { produceState(known, application, known) {
        value = known ?: application.savedPublicationProgress(id)
    } }
    return value
}

/** Metadata placeholder only: the current publication contract exposes no cover resource.
 * No new network request or decoded image cache is introduced for catalog presentation. */
@Composable
private fun PublicationMark(type: PublicationType, modifier: Modifier = Modifier) {
    Surface(modifier.width(64.dp).height(88.dp), shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colors.primary.copy(alpha = .10f)) {
        Box(contentAlignment = Alignment.Center) {
            Text(when (type) { PublicationType.COMIC -> "CB"; PublicationType.DOCUMENT -> "DOC"; else -> "Aa" },
                style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
internal fun PublicationCard(publication: Publication, source: String, progress: ReadingProgress? = null,
    details: () -> Unit, actions: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, elevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                PublicationMark(publication.type)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(publication.title, style = MaterialTheme.typography.h6, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "), style = MaterialTheme.typography.body2,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(source, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = .72f))
                    if (progress != null) {
                        Text("${(progress.progression * 100).toInt()}% read", style = MaterialTheme.typography.caption)
                        LinearProgressIndicator(progress.progression.toFloat(), Modifier.fillMaxWidth().height(3.dp),
                            backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = .08f))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
                TextButton(details, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Details for ${publication.title}" }) { Text("Details") }
            }
        }
    }
}

@Composable
internal fun PublicationDetails(publication: Publication, source: String, formats: List<PublicationFormat> = emptyList(),
    progress: ReadingProgress? = null, close: () -> Unit, cover: (@Composable () -> Unit)? = null,
    descriptions: PublicationDescriptions? = null, suppliedSynopsis: String? = null, subjects: List<String> = emptyList(), actions: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    val synopsis by produceState<String?>(null, publication.id, descriptions) {
        try { value = descriptions?.get(publication.id) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Optional metadata must not prevent opening a publication. */ }
    }
    var expanded by remember(publication.id) { mutableStateOf(false) }
    val scroll = remember(publication.id) { androidx.compose.foundation.ScrollState(0) }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            val compact = maxWidth < 600.dp
            val frame = if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = 600.dp).fillMaxWidth(.94f).heightIn(max = maxHeight - 24.dp)
            Surface(frame.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false
            }.semantics { paneTitle = "Publication details" },
                shape = if (compact) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.large, elevation = 0.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Publication details", style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f).semantics { heading() })
                        TextButton(close, Modifier.focusRequester(focus).heightIn(min = 48.dp)) { Text("Close") }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            cover?.invoke()
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(publication.title, style = MaterialTheme.typography.h5)
                                if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "))
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                        (suppliedSynopsis ?: synopsis)?.let { text ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Synopsis", style = MaterialTheme.typography.subtitle1, modifier = Modifier.semantics { heading() })
                                Text(if (expanded) text else descriptionExcerpt(text), style = MaterialTheme.typography.body1)
                                if (text.length > DescriptionPolicy.EXCERPT) TextButton({ expanded = !expanded },
                                    Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
                                    Text(if (expanded) "Show less" else "Read more")
                                }
                            }
                        }
                        if (subjects.isNotEmpty()) DetailLine("Categories", subjects.joinToString("; "))
                        DetailLine("Source", source)
                        DetailLine("Format", publicationFormat(formats))
                        if (publication.languages.isNotEmpty()) DetailLine("Languages", publication.languages.joinToString(", "))
                        if (progress != null) DetailLine("Reading progress", "${(progress.progression * 100).toInt()}% read")
                        publication.rights?.let { DetailLine("Source rights statement", it) }
                        publication.sourceUrl?.let { DetailLine("Source reference", it) }
                        Text("Availability does not establish rights in every country. Saved metadata does not authorize a download; opening still checks the source.", style = MaterialTheme.typography.caption)
                    }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.subtitle2)
        Text(value, style = MaterialTheme.typography.body2)
    }
}
