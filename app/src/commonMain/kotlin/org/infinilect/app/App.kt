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
import org.infinilect.app.ui.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.isSystemInDarkTheme

internal data class SourceOption(val name: String, val source: PublicationSource, val textReadingEnabled: Boolean = false, val epubReadingEnabled: Boolean = false, val pageReadingEnabled: Boolean = false, val pdfReadingEnabled: Boolean = false)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(
    applicationSources: ApplicationSources,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    localFilePicker: org.infinilect.app.imports.LocalFilePicker? = null,
    readerAppearance: (ReaderAppearance?) -> Unit = {},
    applicationAppearance: (ReaderAppearance) -> Unit = {},
) {
    val application=remember(applicationSources) { applicationSources.applicationSession() }
    val appearanceState by (applicationSources.appearance?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(ApplicationAppearanceState()) }).collectAsState()
    val mode = appearanceState.mode
    val appearanceFailed by (applicationSources.appearance?.saveFailed ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val systemDark = isSystemInDarkTheme()
    val appDark = mode.isDark(systemDark)
    SideEffect { applicationAppearance(ReaderAppearance(appDark, applicationColors(appDark).background)) }
    val libraryPosition = rememberLazyGridState()
    val historyPosition = rememberLazyGridState()
    val progressRecords by (applicationSources.progress?.recentProgress ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<org.infinilect.core.ReadingProgressId, org.infinilect.core.ReadingProgress>()) }).collectAsState()
    // Save only owned identity for Android recreation; never an external acquisition URI.
    var savedPdfId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedPdfPage by rememberSaveable { mutableStateOf(0) }
    var savedPdfDestination by rememberSaveable { mutableStateOf(Destination.SEARCH.name) }
    val initialPdfId=remember(application) { savedPdfId }
    LaunchedEffect(application) {
        initialPdfId?.let { application.restoreLocalPdf(it,savedPdfDestination,savedPdfPage) }
    }
    DisposableEffect(application) {
        // Presentation replacement flushes legitimate work, but does not close the session.
        onDispose { application.flushProgress() }
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
                OpenPublicationState.Idle -> ApplicationTheme(mode) {
                    ApplicationShell(destination, importing.busy, application::navigate,
                        onEscape = {
                            if (application.collections.selection.value.isNotEmpty()) { application.collections.clearSelection(); true } else false
                        },
                        importAction = if (localFilePicker != null && applicationSources.localImports != null) ({
                            androidx.compose.material.OutlinedButton(enabled = !importing.busy, onClick = { application.importLocal(localFilePicker) },
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Import local file") }
                        }) else null,
                    ) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val feedbackMaxHeight = maxHeight / 2
                            Column(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxWidth().heightIn(max = feedbackMaxHeight).verticalScroll(rememberScrollState())) {
                                    if (importing.busy) FeedbackCard("Importing…", "Copying and validating your publication in private storage.", busy = true,
                                        action = "Cancel", onAction = application::back, modifier = Modifier.padding(horizontal = 24.dp))
                                    importing.message?.let { FeedbackCard("Import could not finish", it, error = true, modifier = Modifier.padding(horizontal = 24.dp)) }
                                }
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                    when (destination) {
                                        Destination.SEARCH -> key(session) {
                                            SearchScreen(session, searchState, resultsPosition, applicationSources.options, selected, application::selectSource,
                                                application.collections, application::openSearch, progressRecords = progressRecords.values.toList())
                                        }
                                        Destination.LIBRARY, Destination.HISTORY -> CollectionScreen(destination, application,
                                            if (destination == Destination.LIBRARY) libraryPosition else historyPosition, progressRecords.values.toList())
                                        Destination.SETTINGS -> ApplicationSettingsScreen(mode, { applicationSources.appearance?.change(it) }, appearanceFailed)
                                    }
                                }
                            }
                        }
                    }
                }
                is OpenPublicationState.EpubReady -> org.infinilect.app.reader.epub.EpubReader(
                    current.reader, saveFailed, application::back, backLabel, backHandler,
                    appearanceChanged = readerAppearance,
                    publicationActions = {
                        LibraryAction(LibraryActionState(membership.inLibrary, membership.busy, membership.unavailable), application.collections::toggleLibrary)
                    },
                    notices = if (membership.unavailable || collectionError != null || historyFailed) ({
                        if (membership.unavailable) {
                            Text("Library storage is unavailable on this device.")
                            Button(onClick = application.collections::refreshLibrary) { Text("Retry Library") }
                        }
                        collectionError?.let { Text(it, color = MaterialTheme.colors.error) }
                        if (historyFailed) Text("Reading history could not be saved on this device.")
                    }) else null,
                )
                is OpenPublicationState.Ready, is OpenPublicationState.PageReady, is OpenPublicationState.PdfReady -> Column(Modifier.fillMaxSize()) {
                    ReaderAppearanceEffect(ReaderAppearance(false, androidx.compose.ui.graphics.Color.White), readerAppearance)
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
                        }
                    }
                }
                is OpenPublicationState.Loading -> ApplicationTheme(mode) {
                    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ScreenHeading(current.publication.title, "Preparing your reading session.")
                        FeedbackCard("Opening publication…", "Your saved reading position will be restored when the publication is ready.", busy = true,
                            action = backLabel, onAction = application::back)
                    }
                }
                is OpenPublicationState.Error -> ApplicationTheme(mode) {
                    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ScreenHeading(current.publication.title, "This publication could not be opened.")
                        FeedbackCard("Unable to open", current.userMessage, error = true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = application::back) { Text(backLabel) }
                            androidx.compose.material.OutlinedButton(enabled = application.canRetry(current.publication), onClick = { application.retry(current.publication) }) { Text("Try again") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SearchScreen(session: ReadingSession, state: SearchState, resultsPosition: LazyListState,
    sources: List<SourceOption>, selected: Int, onSource: (Int) -> Unit,
    collections: CollectionsController, onOpen: (org.infinilect.core.Publication) -> Unit = session::open,
    modifier: Modifier = Modifier, progressRecords: List<org.infinilect.core.ReadingProgress> = emptyList()) {
    var detail by remember(session) { mutableStateOf<org.infinilect.core.Publication?>(null) }
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
                ScreenHeading("Search", "Explore the existing catalogs or your imported files.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEachIndexed { index, source ->
                        androidx.compose.material.OutlinedButton(enabled = index != selected, onClick = { onSource(index) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(source.name) }
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
                            FeedbackCard("Searching…", "Your current results remain available while the source responds.", busy = true)
                        }
                        when (val current = state) {
                            SearchState.Idle -> FeedbackCard("Find your next read", "Enter a search to discover publications.")
                            is SearchState.Error -> FeedbackCard("Search could not finish", current.message, error = true, action = "Try again", onAction = session::submitSearch)
                            else -> Unit
                        }
                        if(membership.unavailable) {
                            Text("Library storage is unavailable on this device.",color=MaterialTheme.colors.error)
                            Button(onClick=collections::refreshLibrary) { Text("Retry Library") }
                        }
                        libraryError?.let { Text(it,color=MaterialTheme.colors.error) }
                        if (displayed != null) {
                            if (displayed.page.publications.isEmpty()) FeedbackCard("No results", "No publications found for “${displayed.query}”.")
                            else Text("Results for “${displayed.query}”")
                        }
                    }
                }
                if (displayed != null) {
                    items(displayed.page.publications, key = { it.id.resultKey() }) { publication ->
                        PublicationCard(publication, sources[selected].name, publicationProgress(progressRecords, publication.id), details = { detail = publication }) {
                            if (session.textReadingEnabled || session.epubReadingEnabled || session.pageReadingEnabled || session.pdfReadingEnabled) {
                                Button(enabled = !loading, onClick = { onOpen(publication) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(if (session.pdfReadingEnabled) "Open" else if (session.pageReadingEnabled) "Open pages" else if (session.epubReadingEnabled && !session.textReadingEnabled) "Open EPUB" else "Open text")
                                }
                            }
                            CatalogLibraryAction(membership.forPublication(publication.id), onToggle = { collections.toggleCatalogLibrary(publication) })
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
    detail?.let { publication ->
        PublicationDetails(publication, sources[selected].name, publication.resources.map { it.format },
            publicationProgress(progressRecords, publication.id), close = { detail = null }) {
            if (session.textReadingEnabled || session.epubReadingEnabled || session.pageReadingEnabled || session.pdfReadingEnabled) {
                Button(enabled = !loading, onClick = { detail = null; onOpen(publication) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") }
            }
            CatalogLibraryAction(membership.forPublication(publication.id), onToggle = { collections.toggleCatalogLibrary(publication) })
        }
    }
}

@Composable
private fun LibraryAction(state: LibraryActionState, onToggle: () -> Unit) {
    Button(enabled=state.enabled,onClick=onToggle) {
        Text(state.label)
    }
}

@Composable
private fun CatalogLibraryAction(state: LibraryActionState, onToggle: () -> Unit) {
    androidx.compose.material.OutlinedButton(enabled = state.enabled, onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(state.label)
    }
}
