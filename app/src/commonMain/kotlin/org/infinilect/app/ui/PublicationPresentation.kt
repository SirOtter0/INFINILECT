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

internal fun publicationProgress(records: Collection<ReadingProgress>, id: PublicationId): ReadingProgress? {
    var newest: ReadingProgress? = null
    for (record in records) if (record.id.publicationId == id &&
        (newest == null || record.updatedAtEpochMillis > newest.updatedAtEpochMillis)) newest = record
    return newest
}

/** One index per immutable progress snapshot, rather than scanning it in every lazy item. */
internal fun indexPublicationProgress(records: Collection<ReadingProgress>): Map<PublicationId, ReadingProgress> = buildMap {
    for (record in records) {
        val id = record.id.publicationId
        if (get(id)?.let { it.updatedAtEpochMillis >= record.updatedAtEpochMillis } != true) put(id, record)
    }
}
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
    val strings = LocalAppStrings.current
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, elevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                PublicationMark(publication.type)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(publication.title, style = MaterialTheme.typography.h6, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "), style = MaterialTheme.typography.body2,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(strings.sourceName(source), style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = .72f))
                    if (progress != null) {
                        Text(strings.format(UiText.UI_1_S_READ, (progress.progression * 100).toInt()), style = MaterialTheme.typography.caption)
                        LinearProgressIndicator(progress.progression.toFloat(), Modifier.fillMaxWidth().height(3.dp),
                            backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = .08f))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
                TextButton(details, Modifier.heightIn(min = 48.dp).semantics { contentDescription = strings.format(UiText.UI_DETAILS_FOR_1_S, publication.title) }) { Text(strings[UiText.UI_DETAILS]) }
            }
        }
    }
}

@Composable
internal fun PublicationDetails(publication: Publication, source: String, formats: List<PublicationFormat> = emptyList(),
    progress: ReadingProgress? = null, close: () -> Unit, cover: (@Composable () -> Unit)? = null,
    descriptions: PublicationDescriptions? = null, suppliedSynopsis: String? = null, subjects: List<String> = emptyList(), dismissOverlay: (() -> Boolean)? = null, collections: org.infinilect.app.collections.CollectionsController? = null, organizationCovers: org.infinilect.app.covers.PublicationCovers? = null, downloads: org.infinilect.app.downloads.OfflineDownloads? = null, actions: @Composable () -> Unit) {
    val strings = LocalAppStrings.current
    val feedback = remember {SnackbarHostState()}
    val focus = remember { FocusRequester() }
    val synopsis by produceState<String?>(null, publication.id, descriptions) {
        try { value = descriptions?.get(publication.id) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Optional metadata must not prevent opening a publication. */ }
    }
    var expanded by remember(publication.id) { mutableStateOf(false) }
    val scroll = remember(publication.id) { androidx.compose.foundation.ScrollState(0) }
    val dismiss = { if(dismissOverlay?.invoke()!=true) close() }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            val compact = maxWidth < 600.dp
            val frame = if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = 600.dp).fillMaxWidth(.94f).heightIn(max = maxHeight - 24.dp)
            Surface(frame.onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { dismiss(); true } else false
            }.semantics { paneTitle = strings[UiText.UI_PUBLICATION_DETAILS] },
                shape = if (compact) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.large, elevation = 0.dp) {
                Box {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(strings[UiText.UI_PUBLICATION_DETAILS], style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f).semantics { heading() })
                        TextButton(close, Modifier.focusRequester(focus).heightIn(min = 48.dp)) { Text(strings[UiText.CLOSE]) }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal=16.dp,vertical=8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            cover?.invoke()
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(publication.title, style = MaterialTheme.typography.h5)
                                if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "),style=MaterialTheme.typography.body2)
                                if(formats.isNotEmpty()) Text(publicationFormat(formats),style=MaterialTheme.typography.caption)
                                if(publication.languages.isNotEmpty()) Text(publication.languages.joinToString(", "),style=MaterialTheme.typography.caption)
                                if(progress!=null) {
                                    Text(strings.format(UiText.UI_1_S_READ,(progress.progression*100).toInt()),style=MaterialTheme.typography.caption)
                                    LinearProgressIndicator(progress.progression.toFloat(),Modifier.fillMaxWidth().height(3.dp),backgroundColor=MaterialTheme.colors.onSurface.copy(alpha=.12f))
                                }
                            }
                        }
                        if(collections!=null) PublicationOrganizationActions(publication,collections,organizationCovers,feedback,downloads)
                        else downloads?.let {Row {org.infinilect.app.downloads.DownloadAction(publication,it)}}
                        downloads?.let {manager ->
                            org.infinilect.app.downloads.DownloadFailureMessage(publication.id,manager)
                            when(manager.backend.capability(publication.id)) {
                                org.infinilect.app.downloads.DownloadCapability.IMPORTED -> Text(strings[UiText.DL_IMPORTED],style=MaterialTheme.typography.caption)
                                org.infinilect.app.downloads.DownloadCapability.UNSUPPORTED -> Text(strings[UiText.DL_NOT_ALLOWED],style=MaterialTheme.typography.caption)
                                else -> Unit
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                        (suppliedSynopsis ?: synopsis)?.let { text ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(strings[UiText.UI_SYNOPSIS], style = MaterialTheme.typography.subtitle1, modifier = Modifier.semantics { heading() })
                                Text(if (expanded) text else descriptionExcerpt(text), style = MaterialTheme.typography.body1)
                                if (text.length > DescriptionPolicy.EXCERPT) TextButton({ expanded = !expanded },
                                    Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (expanded) strings[UiText.UI_EXPANDED] else strings[UiText.UI_COLLAPSED] }) {
                                    Text(if (expanded) strings[UiText.UI_SHOW_LESS] else strings[UiText.UI_READ_MORE])
                                }
                            }
                        }
                        if (subjects.isNotEmpty()) DetailLine(strings[UiText.UI_CATEGORIES], subjects.joinToString("; "))
                        DetailLine(strings[UiText.UI_SOURCE], strings.sourceName(source))
                        DetailLine(strings[UiText.UI_FORMAT], if(formats.isEmpty()) strings[UiText.UI_FORMAT_CHECKED_WHEN_OPENING] else publicationFormat(formats))
                        if (publication.languages.isNotEmpty()) DetailLine(strings[UiText.UI_LANGUAGES], publication.languages.joinToString(", "))
                        if (progress != null) DetailLine(strings[UiText.UI_READING_PROGRESS], strings.format(UiText.UI_1_S_READ, (progress.progression * 100).toInt()))
                        publication.rights?.let { DetailLine(strings[UiText.UI_SOURCE_RIGHTS_STATEMENT], it) }
                        publication.sourceUrl?.let { DetailLine(strings[UiText.UI_SOURCE_REFERENCE], it) }
                        Surface(color=MaterialTheme.colors.onSurface.copy(alpha=.05f),shape=MaterialTheme.shapes.small) {
                            Text(strings[UiText.UI_AVAILABILITY_DOES_NOT_ESTABLISH_RIGHTS_IN_EVERY_COUNTRY_SAVED_METADATA_DOES_NOT],Modifier.padding(12.dp),style = MaterialTheme.typography.caption)
                        }
                    }
                }
                SnackbarHost(feedback,Modifier.align(Alignment.BottomCenter).padding(12.dp).semantics {liveRegion=LiveRegionMode.Polite})
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.caption,color=MaterialTheme.colors.onSurface.copy(alpha=.72f))
        Text(value, style = MaterialTheme.typography.body2)
    }
}
