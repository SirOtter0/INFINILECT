// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import org.infinilect.app.ApplicationSession
import org.infinilect.app.Destination
import org.infinilect.app.resultKey
import org.infinilect.core.*

private data class CollectionRemoval(val publication: PublicationSnapshot, val history: Boolean)

@Composable
internal fun CollectionScreen(destination: Destination, application: ApplicationSession,
    position: LazyGridState = rememberLazyGridState(), progressRecords: List<ReadingProgress> = emptyList()) {
    val controller = application.collections
    val library by controller.library.collectAsState()
    val history by controller.history.collectAsState()
    val confirmation by controller.confirmClear.collectAsState()
    val busy by controller.busy.collectAsState()
    val error by controller.error.collectAsState()
    val membership by controller.membership.collectAsState()
    val selected by controller.selection.collectAsState()
    val isLibrary = destination == Destination.LIBRARY
    val selecting = isLibrary && selected.isNotEmpty()
    val entries = if (isLibrary) library.entries.map { it.publication } else history.entries.map { it.publication }
    val loading = if (isLibrary) library.loading else history.loading
    val failed = if (isLibrary) library.failed else history.failed
    var detail by remember(destination) { mutableStateOf<PublicationSnapshot?>(null) }
    var detailFormat by remember(destination) { mutableStateOf<PublicationFormat?>(null) }
    var remove by remember(destination) { mutableStateOf<CollectionRemoval?>(null) }
    var batch by remember(destination) { mutableStateOf<Set<PublicationId>?>(null) }
    val now = Clock.System.now().toEpochMilliseconds()
    val historyGroups = remember(history.entries, isLibrary, historyDay(now, now).key) {
        if (isLibrary) emptyMap() else history.entries.groupBy { historyDay(it.lastOpenedAtEpochMillis, now) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = if (isLibrary) "Library" else "History" }.onPreviewKeyEvent {
        if (selecting && it.type == KeyEventType.KeyDown && it.key == Key.Escape) { controller.clearSelection(); true } else false
    }) {
        val barMaxHeight = maxHeight / 2
        val columns = if (isLibrary) libraryColumns(maxWidth.value) else 1
        Column(Modifier.fillMaxSize()) {
            if (selecting) Surface(color = MaterialTheme.colors.primary.copy(alpha = .10f)) {
                Column(Modifier.fillMaxWidth().heightIn(max = barMaxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${selected.size} selected", style = MaterialTheme.typography.subtitle1,
                            modifier = Modifier.padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite; heading() })
                        TextButton(controller::clearSelection, Modifier.heightIn(min = 48.dp)) { Text("Clear selection") }
                        TextButton(controller::selectAll, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Select all") }
                        TextButton({ batch = selected.toSet() }, Modifier.heightIn(min = 48.dp), enabled = !busy && membership.mutating.isEmpty()) {
                            Text(if (busy) "Removing…" else "Remove from Library")
                        }
                    }
                }
            }
            LazyVerticalGrid(GridCells.Fixed(columns), Modifier.weight(1f).fillMaxWidth(), state = position,
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(if (isLibrary) 12.dp else 4.dp)) {
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) { FeedbackCard("Loading…", "Reading your saved publications.", busy = true) }
                if (failed) item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedbackCard("Local storage is unavailable", "Your saved publications have not been removed. Please try again.", error = true,
                        action = "Try again", onAction = { if (isLibrary) controller.refreshLibrary() else controller.refreshHistory() })
                }
                error?.let { item(span = { GridItemSpan(maxLineSpan) }) { FeedbackCard("Change could not be saved", it, error = true) } }
                if (entries.isEmpty() && !loading && !failed) item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedbackCard(if (isLibrary) "Your Library is empty" else "No reading history yet",
                        if (isLibrary) "Import a local file or save a publication from Search. Your reading positions are kept separately." else "Open a publication to start reading. Recent reads will appear here.",
                        action = "Browse Search", onAction = { application.navigate(Destination.SEARCH) })
                }
                if (isLibrary) items(entries, key = { it.id.resultKey() }) { publication ->
                    LibraryCover(publication.displayPublication(), application.covers, publicationProgress(progressRecords, publication.id),
                        selected = publication.id in selected, selecting = selecting, enabled = membership.forPublication(publication.id).enabled && !busy,
                        open = { application.openSaved(publication) }, details = { detail = publication; detailFormat = it },
                        select = { controller.select(publication.id) }, toggle = { controller.toggleSelection(publication.id) },
                        remove = { remove = CollectionRemoval(publication, history = false) })
                } else historyGroups.forEach { (day, group) ->
                    item(key = "history-day:${day.key}") { Text(day.label, style = MaterialTheme.typography.subtitle2,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() }) }
                    items(group, key = { it.publication.id.resultKey() }) { entry ->
                        val publication = entry.publication
                        HistoryPublication(publication.displayPublication(), application.covers, publicationProgress(progressRecords, publication.id), !busy,
                            open = { application.openSaved(publication) }, details = { detail = publication; detailFormat = it },
                            remove = { remove = CollectionRemoval(publication, history = true) })
                    }
                }
                if (!isLibrary) item {
                    TextButton(controller::requestClearHistory, Modifier.heightIn(min = 48.dp), enabled = !busy && entries.isNotEmpty()) { Text("Clear history") }
                }
            }
        }
    }
    detail?.let { publication ->
        val progress = detailsProgress(application, publication.id, publicationProgress(progressRecords, publication.id))
        val action = membership.forPublication(publication.id)
        PublicationDetails(publication.displayPublication(), application.sourceName(publication.id.sourceId),
            formats = progress?.let { listOf(it.id.format) } ?: detailFormat?.let { listOf(it) } ?: emptyList(),
            progress = progress, close = { detail = null }, descriptions = application.descriptions, cover = { DetailsCover(publication.displayPublication(), application.covers) }) {
            Button({ detail = null; application.openSaved(publication) }, Modifier.heightIn(min = 48.dp)) { Text(readingAction(progress)) }
            OutlinedButton({
                if (action.inLibrary == true) { detail = null; remove = CollectionRemoval(publication, history = false) }
                else controller.toggleCatalogLibrary(publication.displayPublication())
            }, Modifier.heightIn(min = 48.dp), enabled = action.enabled && !busy) { Text(action.label) }
            error?.let { Text(it, color = MaterialTheme.colors.error) }
        }
    }
    remove?.let { removal ->
        AlertDialog(onDismissRequest = { remove = null },
            title = { Text(if (removal.history) "Remove from History?" else "Remove from Library?") },
            text = { Text("${removal.publication.title} will be removed from this list only. Files and saved reading positions are kept." +
                if (removal.history) " Library membership is unchanged." else " History is unchanged.") },
            confirmButton = { TextButton({
                remove = null
                if (removal.history) controller.removeHistory(removal.publication.id) else controller.removeLibrary(removal.publication.id)
            }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Remove") } },
            dismissButton = { TextButton({ remove = null }, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
    }
    batch?.let { ids ->
        AlertDialog(onDismissRequest = { batch = null }, title = { Text("Remove ${ids.size} from Library?") },
            text = { Text("Only Library membership is removed. Original files, imported copies, History and saved reading positions are kept. Items that cannot be removed stay in Library.") },
            confirmButton = { TextButton({ batch = null; controller.removeSelectedLibrary(ids) }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Remove") } },
            dismissButton = { TextButton({ batch = null }, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
    }
    if (confirmation) AlertDialog(onDismissRequest = controller::dismissClearHistory, title = { Text("Clear reading history?") },
        text = { Text("Library entries and reading positions will be kept.") },
        confirmButton = { TextButton(controller::confirmClearHistory, Modifier.heightIn(min = 48.dp)) { Text("Clear history") } },
        dismissButton = { TextButton(controller::dismissClearHistory, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } }, shape = MaterialTheme.shapes.medium)
}
