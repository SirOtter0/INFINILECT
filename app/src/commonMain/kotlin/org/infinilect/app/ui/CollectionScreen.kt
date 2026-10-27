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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
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
    val strings = LocalAppStrings.current
    val controller = application.collections
    val library by controller.library.collectAsState()
    val history by controller.history.collectAsState()
    val confirmation by controller.confirmClear.collectAsState()
    val busy by controller.busy.collectAsState()
    val error by controller.error.collectAsState()
    val membership by controller.membership.collectAsState()
    val selected by controller.selection.collectAsState()
    val browser = application.libraryBrowser
    val browserEntries by browser.result.collectAsState()
    val query by browser.query.collectAsState()
    val working by browser.working.collectAsState()
    val tags by controller.tags.collectAsState()
    val organizationResult by controller.organizationResult.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(organizationResult,strings.localeTag) {
        if (organizationResult == true) {
            snackbar.showSnackbar(strings[UiText.LIB_SAVED],duration=SnackbarDuration.Short)
            controller.dismissOrganizationSuccess()
        }
    }
    val isLibrary = destination == Destination.LIBRARY
    LaunchedEffect(isLibrary) { if(isLibrary) browser.activate() }
    LaunchedEffect(isLibrary, progressRecords) { if(isLibrary) browser.mergeProgress(progressRecords) }
    LaunchedEffect(isLibrary,tags) {
        if(isLibrary) {
            val current = browser.query.value
            val retained = current.tags.intersect(tags.map {it.id}.toSet())
            if(retained != current.tags) browser.change(current.copy(tags = retained))
        }
    }
    val selecting = isLibrary && selected.isNotEmpty()
    val entries = remember(isLibrary, browserEntries, history.entries) {
        if (isLibrary) browserEntries.map { it.publication } else history.entries.map { it.publication }
    }
    val progress = remember(progressRecords) { indexPublicationProgress(progressRecords) }
    val loading = if (isLibrary) library.loading else history.loading
    val failed = if (isLibrary) library.failed else history.failed
    var detail by remember(destination) { mutableStateOf<PublicationSnapshot?>(null) }
    var detailFormat by remember(destination) { mutableStateOf<PublicationFormat?>(null) }
    var remove by remember(destination) { mutableStateOf<CollectionRemoval?>(null) }
    var organize by remember { mutableStateOf<Set<PublicationId>?>(null) }
    var batch by remember(destination) { mutableStateOf<Set<PublicationId>?>(null) }
    val now = Clock.System.now().toEpochMilliseconds()
    val interfaceLanguage = LocalInterfaceLanguage.current
    val historyGroups = remember(history.entries, isLibrary, historyDay(now, now).key, interfaceLanguage) {
        if (isLibrary) emptyMap() else history.entries.groupBy { historyDay(it.lastOpenedAtEpochMillis, now) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = if (isLibrary) strings[UiText.LIBRARY] else strings[UiText.HISTORY] }.onPreviewKeyEvent {
        if (selecting && it.type == KeyEventType.KeyDown && it.key == Key.Escape) { controller.clearSelection(); true } else false
    }) {
        val barMaxHeight = maxHeight / 2
        val columns = if (isLibrary) libraryColumns(maxWidth.value, LocalDensity.current.fontScale) else 1
        val artworkHeight=minOf(((maxWidth-24.dp-8.dp*(columns-1))/columns).coerceAtLeast(0.dp)*1.5f,(maxHeight*.65f).coerceAtLeast(72.dp))
        Column(Modifier.fillMaxSize()) {
            if (selecting) Surface(color = MaterialTheme.colors.primary.copy(alpha = .10f)) {
                Column(Modifier.fillMaxWidth().heightIn(max = barMaxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
                        LibraryIconButton(strings[UiText.UI_CLEAR_SELECTION],LibraryGlyph.CLOSE) {controller.clearSelection()}
                        Text(strings.selectedCount(selected.size), style = MaterialTheme.typography.subtitle2,
                            modifier = Modifier.padding(vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite; heading() })
                        TextButton({ controller.selectAll(browserEntries.map { it.publication.id }.toSet()) }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text(if(browserEntries.size > 1000) strings.format(UiText.LIB_SELECT_LIMIT,strings.number(1000)) else strings[UiText.SELECT_ALL]) }
                        LibraryIconButton(strings[UiText.LIB_ORGANIZE],LibraryGlyph.TAG,enabled=!busy) {organize=selected.toSet()}
                        LibraryIconButton(strings[UiText.LIB_FAVORITE],LibraryGlyph.HEART,enabled=!busy,
                            active=library.entries.filter {it.publication.id in selected}.all {it.favorite}) {
                            val allFavorite=library.entries.filter {it.publication.id in selected}.all {it.favorite}
                            controller.organize(selected.toSet(),LibraryOrganizationChange(favorite=!allFavorite))
                        }
                        LibraryIconButton(strings[UiText.UI_REMOVE_FROM_LIBRARY],LibraryGlyph.DELETE,enabled=!busy && membership.mutating.isEmpty()) {batch=selected.toSet()}
                    }
                }
            }
            LazyVerticalGrid(GridCells.Fixed(columns), Modifier.weight(1f).fillMaxWidth(), state = position,
                contentPadding = PaddingValues(if(isLibrary) 12.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(if (isLibrary) 10.dp else 4.dp)) {
                if (isLibrary && !selecting) item(key = "library-tools", span = { GridItemSpan(maxLineSpan) }) { LibraryTools(browser,tags,controller,browserEntries.size) }
                if (loading && entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { FeedbackCard(strings[UiText.UI_LOADING], strings[UiText.UI_READING_YOUR_SAVED_PUBLICATIONS], busy = true) }
                if (failed) item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedbackCard(strings[UiText.UI_LOCAL_STORAGE_IS_UNAVAILABLE], strings[UiText.UI_YOUR_SAVED_PUBLICATIONS_HAVE_NOT_BEEN_REMOVED_PLEASE_TRY_AGAIN], error = true,
                        action = strings[UiText.UI_TRY_AGAIN], onAction = { if (isLibrary) controller.refreshLibrary() else controller.refreshHistory() })
                }
                error?.let { item(span = { GridItemSpan(maxLineSpan) }) { FeedbackCard(strings[UiText.UI_CHANGE_COULD_NOT_BE_SAVED], strings.status(it), error = true) } }
                if (isLibrary && organizationResult == false) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(strings[UiText.LIB_SAVE_ERROR],color = MaterialTheme.colors.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (isLibrary && entries.isEmpty() && library.entries.isNotEmpty() && !working && !loading && !failed) item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedbackCard(strings[UiText.LIB_NO_MATCHES],strings[UiText.LIB_NO_MATCHES_HINT],
                        action = strings[if(query.filtered) UiText.LIB_RESET_FILTERS else UiText.LIB_CLEAR_SEARCH],
                        onAction = { if(query.filtered) browser.resetFilters() else browser.change(query.copy(text = "")) })
                }
                if (entries.isEmpty() && !loading && !failed && (!isLibrary || library.entries.isEmpty())) item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedbackCard(if (isLibrary) strings[UiText.UI_YOUR_LIBRARY_IS_EMPTY] else strings[UiText.UI_NO_READING_HISTORY_YET],
                        if (isLibrary) strings[UiText.UI_IMPORT_A_LOCAL_FILE_OR_SAVE_A_PUBLICATION_FROM_SEARCH_YOUR_READING_POSITIONS_ARE] else strings[UiText.UI_OPEN_A_PUBLICATION_TO_START_READING_RECENT_READS_WILL_APPEAR_HERE],
                        action = strings[UiText.UI_BROWSE_SEARCH], onAction = { application.navigate(Destination.SEARCH) })
                }
                if (isLibrary) items(browserEntries, key = { it.publication.id.resultKey() }) { entry ->
                    val publication=entry.publication
                    LibraryCover(remember(publication) { publication.displayPublication() }, application.covers, progress[publication.id],
                        selected = publication.id in selected, selecting = selecting, enabled = membership.forPublication(publication.id).enabled && !busy,
                        open = { application.openSaved(publication) }, details = { detail = publication; detailFormat = it },
                        select = { controller.select(publication.id) }, toggle = { controller.toggleSelection(publication.id) },
                        remove = { remove = CollectionRemoval(publication, history = false) },
                        favorite=entry.favorite,completed=entry.readingState==LibraryReadingState.COMPLETED,artworkHeight=artworkHeight)
                } else historyGroups.forEach { (day, group) ->
                    item(key = "history-day:${day.key}") { Text(if (day.label == "Today" || day.label == "Yesterday") strings.status(day.label) else day.label, style = MaterialTheme.typography.subtitle2,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() }) }
                    items(group, key = { it.publication.id.resultKey() }) { entry ->
                        val publication = entry.publication
                        HistoryPublication(remember(publication) { publication.displayPublication() }, application.covers, progress[publication.id], !busy,
                            open = { application.openSaved(publication) }, details = { detail = publication; detailFormat = it },
                            remove = { remove = CollectionRemoval(publication, history = true) })
                    }
                }
                if (!isLibrary) item {
                    TextButton(controller::requestClearHistory, Modifier.heightIn(min = 48.dp), enabled = !busy && entries.isNotEmpty()) { Text(strings[UiText.UI_CLEAR_HISTORY]) }
                }
            }
        }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(12.dp).semantics {liveRegion=LiveRegionMode.Polite})
    }
    detail?.let { publication ->
        val progress = detailsProgress(application, publication.id, progress[publication.id])
        val action = membership.forPublication(publication.id)
        PublicationDetails(publication.displayPublication(), application.sourceName(publication.id.sourceId),
            formats = progress?.let { listOf(it.id.format) } ?: detailFormat?.let { listOf(it) } ?: emptyList(),
            progress = progress, close = { detail = null }, descriptions = application.descriptions, cover = { DetailsCover(publication.displayPublication(), application.covers) }) {
            val entry=library.entries.firstOrNull {it.publication.id==publication.id}
            LibraryDetailsActions(progress,action.inLibrary==true,entry?.favorite,action.enabled && !busy,
                read={detail=null;application.openSaved(publication)},
                membership={if(action.inLibrary==true) {detail=null;remove=CollectionRemoval(publication,false)} else controller.toggleCatalogLibrary(publication.displayPublication())},
                organize={detail=null;organize=setOf(publication.id)},
                favoriteAction={if(entry!=null) controller.organize(setOf(publication.id),LibraryOrganizationChange(favorite=!entry.favorite))})
            error?.let { Text(strings.status(it), color = MaterialTheme.colors.error) }
        }
    }
    organize?.let { ids -> LibraryOrganizer(library.entries.filter { it.publication.id in ids },tags,controller,application.covers) { organize = null } }
    remove?.let { removal ->
        AlertDialog(onDismissRequest = { remove = null },
            title = { Text(if (removal.history) strings[UiText.UI_REMOVE_FROM_HISTORY] else strings[UiText.UI_REMOVE_FROM_LIBRARY_CONFIRM]) },
            text = { Text(if (removal.history) strings.format(UiText.UI_1_S_WILL_BE_REMOVED_FROM_THIS_LIST_ONLY_FILES_AND_SAVED_READING_POSITIONS_ARE_KE, removal.publication.title) else strings.format(UiText.UI_1_S_WILL_BE_REMOVED_FROM_THIS_LIST_ONLY_FILES_AND_SAVED_READING_POSITIONS_ARE_KE_NOTICE, removal.publication.title)) },
            confirmButton = { TextButton({
                remove = null
                if (removal.history) controller.removeHistory(removal.publication.id) else controller.removeLibrary(removal.publication.id)
            }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text(strings[UiText.UI_REMOVE]) } },
            dismissButton = { TextButton({ remove = null }, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.CANCEL]) } })
    }
    batch?.let { ids ->
        AlertDialog(onDismissRequest = { batch = null }, title = { Text(strings.format(UiText.UI_REMOVE_1_S_FROM_LIBRARY, ids.size)) },
            text = { Text(strings[UiText.UI_ONLY_LIBRARY_MEMBERSHIP_IS_REMOVED_ORIGINAL_FILES_IMPORTED_COPIES_HISTORY_AND_SA]) },
            confirmButton = { TextButton({ batch = null; controller.removeSelectedLibrary(ids) }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text(strings[UiText.UI_REMOVE]) } },
            dismissButton = { TextButton({ batch = null }, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.CANCEL]) } })
    }
    if (confirmation) AlertDialog(onDismissRequest = controller::dismissClearHistory, title = { Text(strings[UiText.UI_CLEAR_READING_HISTORY]) },
        text = { Text(strings[UiText.UI_LIBRARY_ENTRIES_AND_READING_POSITIONS_WILL_BE_KEPT]) },
        confirmButton = { TextButton(controller::confirmClearHistory, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.UI_CLEAR_HISTORY]) } },
        dismissButton = { TextButton(controller::dismissClearHistory, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.CANCEL]) } }, shape = MaterialTheme.shapes.medium)
}
