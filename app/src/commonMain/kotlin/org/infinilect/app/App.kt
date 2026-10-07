// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.reader.TextReader
import org.infinilect.app.search.SearchState
import org.infinilect.app.search.SearchResultsViewport
import org.infinilect.app.collections.CollectionsController
import org.infinilect.app.collections.LibraryActionState
import org.infinilect.core.PublicationSource
import androidx.compose.runtime.saveable.rememberSaveable

internal data class SourceOption(val name: String, val source: PublicationSource, val textReadingEnabled: Boolean = false, val epubReadingEnabled: Boolean = false, val pageReadingEnabled: Boolean = false, val pdfReadingEnabled: Boolean = false)

@Composable
fun App(
    applicationSources: ApplicationSources,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    localFilePicker: org.infinilect.app.imports.LocalFilePicker? = null,
) {
    val scope=rememberCoroutineScope()
    val application=remember(applicationSources) { ApplicationSession(applicationSources,scope) }
    // Save only owned identity for Android recreation; never an external acquisition URI.
    var savedPdfId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedPdfPage by rememberSaveable { mutableStateOf(0) }
    var savedPdfDestination by rememberSaveable { mutableStateOf(Destination.SEARCH.name) }
    val initialPdfId=remember(application) { savedPdfId }
    LaunchedEffect(application) {
        initialPdfId?.let { application.restoreLocalPdf(it,savedPdfDestination,savedPdfPage) }
    }
    DisposableEffect(applicationSources,application) {
        applicationSources.attach(application)
        onDispose { applicationSources.detach(application) }
    }
    val session by application.searchSession.collectAsState()
    val searchState by key(session) { session.search.state.collectAsState() }
    // App owns the viewport across Reader/Back; a successful page gets a fresh top position.
    val searchViewport = remember(session) { SearchResultsViewport { LazyListState() } }
    val resultsPosition = searchViewport.forState(searchState)
    val selected by application.selected.collectAsState()
    val destination by application.destination.collectAsState()
    val opening by application.opening.collectAsState()
    val importing by application.importing.collectAsState()
    SideEffect {
        when(val current=opening) {
            is OpenPublicationState.PdfReady -> {
                savedPdfId=current.publication.id.localId
                savedPdfDestination=destination.name
            }
            is OpenPublicationState.Loading -> Unit
            else -> savedPdfId=null
        }
    }
    val membership by application.collections.reader.collectAsState()
    val collectionError by application.collections.error.collectAsState()
    val historyFailed by (applicationSources.collections?.historyFailed
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val saveFailed by (applicationSources.progress?.saveFailed
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val backLabel=when(destination) { Destination.LIBRARY -> "Back to Library"; Destination.HISTORY -> "Back to History"; else -> "Back to results" }
    ApplicationBackHandler(application.opening,application.destination,application::back,backHandler,application.importing)
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when(val current=opening) {
                OpenPublicationState.Idle -> Column(Modifier.fillMaxSize()) {
                    if(localFilePicker != null && applicationSources.localImports != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=!importing.busy,onClick={ application.importLocal(localFilePicker) }) { Text("Import local file") }
                            if(importing.busy) { Text("Importing…",modifier=Modifier.weight(1f)); Button(onClick=application::back) { Text("Cancel") } }
                        }
                        importing.message?.let { Text(it,modifier=Modifier.padding(horizontal=24.dp),color=MaterialTheme.colors.error) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Destination.entries.forEach { target ->
                            Button(enabled=!importing.busy && destination!=target,onClick={ application.navigate(target) },modifier=Modifier.weight(1f)) {
                                Text(when(target) { Destination.SEARCH -> "Search"; Destination.LIBRARY -> "Library"; Destination.HISTORY -> "History" })
                            }
                        }
                    }
                    if(destination==Destination.SEARCH) key(session) {
                        SearchScreen(session,searchState,resultsPosition,applicationSources.options,selected,application::selectSource,
                            application.collections,application::openSearch,modifier=Modifier.weight(1f).fillMaxWidth())
                    } else CollectionScreen(destination,application)
                }
                is OpenPublicationState.Ready, is OpenPublicationState.EpubReady, is OpenPublicationState.PageReady, is OpenPublicationState.PdfReady -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        LibraryAction(LibraryActionState(membership.inLibrary,membership.busy,membership.unavailable),
                            application.collections::toggleLibrary)
                    }
                    if(membership.unavailable) {
                        Text("Library storage is unavailable on this device.",modifier=Modifier.padding(horizontal=24.dp))
                        Button(onClick=application.collections::refreshLibrary,modifier=Modifier.padding(horizontal=24.dp)) { Text("Retry Library") }
                    }
                    collectionError?.let { Text(it,modifier=Modifier.padding(horizontal=24.dp),color=MaterialTheme.colors.error) }
                    if(historyFailed) Text("Reading history could not be saved on this device.",modifier=Modifier.padding(horizontal=24.dp))
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        when (current) {
                            is OpenPublicationState.Ready -> TextReader(current.document,current.reading,saveFailed,application::back,backLabel)
                            is OpenPublicationState.PdfReady -> key(current.reader) { org.infinilect.app.reader.pdf.PdfReader(current.reader,saveFailed,application::back,backLabel) { savedPdfPage=it } }
                            is OpenPublicationState.PageReady -> key(current.reader) { org.infinilect.app.reader.page.PageReader(current.reader,saveFailed,application::back,backLabel) }
                            is OpenPublicationState.EpubReady -> org.infinilect.app.reader.epub.EpubReader(current.reader,saveFailed,application::back,backLabel)
                        }
                    }
                }
                is OpenPublicationState.Loading -> Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(current.publication.title,style=MaterialTheme.typography.h6)
                    CircularProgressIndicator(); Text("Opening publication…")
                    Button(onClick=application::back) { Text(backLabel) }
                }
                is OpenPublicationState.Error -> Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(current.publication.title,style=MaterialTheme.typography.h6)
                    Text(current.userMessage,color=MaterialTheme.colors.error)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Button(onClick=application::back) { Text(backLabel) }
                        Button(enabled=application.canRetry(current.publication),onClick={ application.retry(current.publication) }) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CollectionScreen(destination: Destination, application: ApplicationSession) {
    val controller=application.collections
    val library by controller.library.collectAsState()
    val history by controller.history.collectAsState()
    val confirmation by controller.confirmClear.collectAsState()
    val busy by controller.busy.collectAsState()
    val error by controller.error.collectAsState()
    val membership by controller.membership.collectAsState()
    val isLibrary=destination==Destination.LIBRARY
    val entries=if(isLibrary) library.entries.map { it.publication } else history.entries.map { it.publication }
    val loading=if(isLibrary) library.loading else history.loading
    val failed=if(isLibrary) library.failed else history.failed
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(if(isLibrary) "Library" else "History",style=MaterialTheme.typography.h4)
        Text(if(isLibrary) "Publications you've saved. Open an entry to read it from its source." else "Recently read · newest first.")
        if(loading) CircularProgressIndicator()
        if(failed) {
            Text("Local storage is unavailable. Please try again.",color=MaterialTheme.colors.error)
            Button(onClick={ if(isLibrary) controller.refreshLibrary() else controller.refreshHistory() }) { Text("Try again") }
        }
        error?.let { Text(it,color=MaterialTheme.colors.error) }
        if(entries.isEmpty() && !loading && !failed) Text(if(isLibrary) "Your Library is empty. Add publications directly from Search, or from the reader." else "No reading history yet. Open a text publication to start reading.")
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(entries,key={ it.id.resultKey() }) { publication ->
                Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(publication.title,style=MaterialTheme.typography.h6)
                    if(publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "),style=MaterialTheme.typography.body2)
                    Text("Source: ${application.sourceName(publication.id.sourceId)}",style=MaterialTheme.typography.caption)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={ application.openSaved(publication) }) { Text("Open") }
                        val action=membership.forPublication(publication.id)
                        Button(enabled=if(isLibrary) action.enabled else !busy,
                            onClick={ if(isLibrary) controller.removeLibrary(publication.id) else controller.removeHistory(publication.id) }) {
                            Text(if(isLibrary && action.busy) "Removing…" else "Remove")
                        }
                    }
                    Divider()
                }
            }
        }
        if(!isLibrary) Button(enabled=!busy && entries.isNotEmpty(),onClick=controller::requestClearHistory) { Text("Clear history") }
        Button(onClick=application::back) { Text("Back to Search") }
    }
    if(confirmation) androidx.compose.material.AlertDialog(
        onDismissRequest=controller::dismissClearHistory,
        title={ Text("Clear reading history?") },
        text={ Text("Library entries and reading positions will be kept.") },
        confirmButton={ Button(onClick=controller::confirmClearHistory) { Text("Clear history") } },
        dismissButton={ Button(onClick=controller::dismissClearHistory) { Text("Cancel") } },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SearchScreen(session: ReadingSession, state: SearchState, resultsPosition: LazyListState,
    sources: List<SourceOption>, selected: Int, onSource: (Int) -> Unit,
    collections: CollectionsController, onOpen: (org.infinilect.core.Publication) -> Unit = session::open,
    modifier: Modifier = Modifier) {
    val query by session.query.collectAsState()
    val membership by collections.membership.collectAsState()
    val libraryError by collections.error.collectAsState()
    val loading = state is SearchState.Loading
    val displayed = when (val current = state) {
        is SearchState.Results -> current.result
        is SearchState.Empty -> current.result
        is SearchState.Loading -> current.previous
        is SearchState.Error -> current.previous
        SearchState.Idle -> null
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(24.dp)) {
        val headerMaxHeight = maxHeight / 2
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Long headers/source rows may scroll, but can never consume the result viewport.
            Column(Modifier.fillMaxWidth().heightIn(max = headerMaxHeight)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("INFINILECT", style = MaterialTheme.typography.h4)
                Text("Open knowledge. Infinite reading.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEachIndexed { index, source ->
                        Button(enabled = index != selected, onClick = { onSource(index) }) { Text(source.name) }
                    }
                }
                Text("Source: ${sources[selected].name}")
                Text(if (session.textReadingEnabled && session.epubReadingEnabled && session.pageReadingEnabled) "Search your imported TEXT, EPUB, CBZ and PDF publications. Use * to list all imports."
                    else if (session.textReadingEnabled) "Search publications. Open compatible UTF-8 TEXT up to 16 MiB."
                    else if (session.pageReadingEnabled) "Original development comic. No production comic acquisition is enabled."
                    else if (session.epubReadingEnabled) "Original development EPUB. No production EPUB acquisition is enabled."
                    else "Experimental catalog only. TEXT opening is unavailable for this source.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = session::editQuery,
                        label = { Text("Title, author, or keyword") },
                        singleLine = true,
                        enabled = !loading,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (!loading && query.isNotBlank()) session.submitSearch()
                        }),
                    )
                    Button(enabled = !loading && query.isNotBlank(), onClick = {
                        session.submitSearch()
                    }) { Text("Search") }
                }
            }
            // Keep one bounded lazy viewport for results and every feedback/pagination state.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = resultsPosition,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "search-status") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (loading) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator()
                                Text("Searching…")
                            }
                        }
                        when (val current = state) {
                            SearchState.Idle -> Text("Enter a search to discover publications.")
                            is SearchState.Error -> Text(current.message, color = MaterialTheme.colors.error)
                            else -> Unit
                        }
                        if(membership.unavailable) {
                            Text("Library storage is unavailable on this device.",color=MaterialTheme.colors.error)
                            Button(onClick=collections::refreshLibrary) { Text("Retry Library") }
                        }
                        libraryError?.let { Text(it,color=MaterialTheme.colors.error) }
                        if (displayed != null) {
                            if (displayed.page.publications.isEmpty()) Text("No publications found for “${displayed.query}”.")
                            else Text("Results for “${displayed.query}”")
                        }
                    }
                }
                if (displayed != null) {
                    items(displayed.page.publications, key = { it.id.resultKey() }) { publication ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(publication.title, style = MaterialTheme.typography.h6)
                            if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "))
                            if (publication.languages.isNotEmpty()) Text("Language: ${publication.languages.joinToString(", ")}")
                            publication.rights?.let { Text(it, style = MaterialTheme.typography.caption) }
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                if (session.textReadingEnabled || session.epubReadingEnabled || session.pageReadingEnabled || session.pdfReadingEnabled) {
                                    Button(enabled = !loading, onClick = { onOpen(publication) }) { Text(if (session.pdfReadingEnabled) "Open" else if (session.pageReadingEnabled) "Open pages" else if (session.epubReadingEnabled && !session.textReadingEnabled) "Open EPUB" else "Open text") }
                                }
                                LibraryAction(membership.forPublication(publication.id),
                                    onToggle={ collections.toggleCatalogLibrary(publication) })
                            }
                            Divider()
                        }
                    }
                }
                if (displayed?.page?.nextPageToken != null) {
                    item(key = "search-pagination") {
                        Button(enabled = !loading, onClick = session::nextPage) { Text("Next page") }
                    }
                }
                item(key = "search-notice") {
                    Text("Availability does not establish rights in every country. Reading position is saved locally; reopening still checks the source.", style = MaterialTheme.typography.caption)
                }
            }
        }
    }
}

@Composable
private fun LibraryAction(state: LibraryActionState, onToggle: () -> Unit) {
    Button(enabled=state.enabled,onClick=onToggle) {
        Text(state.label)
    }
}
