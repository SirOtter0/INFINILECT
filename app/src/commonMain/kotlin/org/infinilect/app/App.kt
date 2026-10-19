// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.*
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.reader.TextReader
import org.infinilect.app.search.SearchState
import org.infinilect.app.collections.CollectionsController
import org.infinilect.app.collections.LibraryActionState
import org.infinilect.core.PublicationSource
import androidx.compose.runtime.saveable.rememberSaveable
import org.infinilect.app.ui.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.isSystemInDarkTheme

internal data class SourceOption(val name: String, val source: PublicationSource, val textReadingEnabled: Boolean = false, val epubReadingEnabled: Boolean = false, val pageReadingEnabled: Boolean = false, val pdfReadingEnabled: Boolean = false, val developmentOnly: Boolean = false)

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
    var profileEditor by remember { mutableStateOf(false) }
    val homePosition = rememberLazyListState()
    val screenState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val discoveryPosition = rememberLazyGridState()
    val discoveryViewport = remember(application) { org.infinilect.app.discovery.DiscoverySearchViewport() }
    val homeProgress by application.homeProgress.collectAsState()
    val mode = appearanceState.mode
    val appearanceFailed by (applicationSources.appearance?.saveFailed ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val systemDark = isSystemInDarkTheme()
    val appDark = mode.isDark(systemDark)
    SideEffect { applicationAppearance(ReaderAppearance(appDark, applicationColors(appDark).background)) }
    val libraryPosition = rememberLazyGridState()
    val historyPosition = rememberLazyGridState()
    val opening by application.opening.collectAsState()
    // Collection summaries are a shell concern. The active reader already owns its
    // locator flows; its saves must not recompose the entire application root.
    val collectionProgress = if (opening is OpenPublicationState.Idle) {
        val records by (applicationSources.progress?.recentProgress ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<org.infinilect.core.ReadingProgressId, org.infinilect.core.ReadingProgress>()) }).collectAsState()
        remember(records) { records.values.toList() }
    } else emptyList()
    val homeRecords = remember(collectionProgress, homeProgress) { collectionProgress + homeProgress }
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
    val destination by application.destination.collectAsState()
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
    ApplicationBackHandler(application.opening,application.destination,application::back,backHandler,application.importing)
    InterfaceLanguage(appearanceState.profile.interfaceLanguage) {
    val strings = LocalAppStrings.current
    val backLabel = strings[when(destination) { Destination.HOME -> UiText.BACK_HOME; Destination.LIBRARY -> UiText.BACK_LIBRARY; Destination.HISTORY -> UiText.BACK_HISTORY; else -> UiText.BACK_RESULTS }]
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when(val current=opening) {
                OpenPublicationState.Idle -> ApplicationTheme(mode) {
                    ApplicationShell(destination, importing.busy, application::navigate,
                        onEscape = {
                            if (application.collections.selection.value.isNotEmpty()) { application.collections.clearSelection(); true } else false
                        },
                        importAction = if (localFilePicker != null && applicationSources.localImports != null) ({
                            ImportActionTooltip {
                                androidx.compose.material.IconButton(enabled = !importing.busy, onClick = { application.importLocal(localFilePicker) },
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = strings[UiText.IMPORT_FILE] }) { ImportIcon() }
                            }
                        }) else null,
                    ) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val feedbackMaxHeight = maxHeight / 2
                            Column(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxWidth().heightIn(max = feedbackMaxHeight).verticalScroll(rememberScrollState())) {
                                    if (importing.busy) FeedbackCard(strings[UiText.UI_IMPORTING], strings[UiText.UI_COPYING_AND_VALIDATING_YOUR_PUBLICATION_IN_PRIVATE_STORAGE], busy = true,
                                        action = strings[UiText.CANCEL], onAction = application::back, modifier = Modifier.padding(horizontal = 24.dp))
                                    importing.message?.let { FeedbackCard(strings[UiText.UI_IMPORT_COULD_NOT_FINISH], strings.status(it), error = true, modifier = Modifier.padding(horizontal = 24.dp)) }
                                }
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                    screenState.SaveableStateProvider(destination.name) { when (destination) {
                                        Destination.HOME -> HomeScreen(application, appearanceState, homeRecords,
                                            applicationSources.appearance, homePosition,
                                            importAction = if (localFilePicker != null && applicationSources.localImports != null && !importing.busy) ({ application.importLocal(localFilePicker) }) else null,
                                            editProfile = { profileEditor = true }, settingsFailed = appearanceFailed)
                                        Destination.SEARCH -> org.infinilect.app.discovery.UnifiedSearchScreen(application, discoveryPosition, discoveryViewport)
                                        Destination.LIBRARY, Destination.HISTORY -> CollectionScreen(destination, application,
                                            if (destination == Destination.LIBRARY) libraryPosition else historyPosition, collectionProgress)
                                        Destination.SETTINGS -> ApplicationSettingsScreen(mode, { applicationSources.appearance?.change(it) }, appearanceFailed, appearanceState.profile, { profileEditor = true }, appearanceState.loaded && applicationSources.appearance != null, appearanceState.discovery, { applicationSources.appearance?.changeDiscovery(it) }, applicationSources.appearance, applicationSources.translation)
                                    } }
                                }
                            }
                        }
                    }
                    if (profileEditor && applicationSources.appearance != null) ProfileEditor(appearanceState.profile,
                        save = applicationSources.appearance::changeProfile, close = { profileEditor = false })
                }
                is OpenPublicationState.EpubReady -> org.infinilect.app.reader.epub.EpubReader(
                    current.reader, saveFailed, application::back, backLabel, backHandler,
                    appearanceChanged = readerAppearance, applicationDark = appDark,
                    publicationActions = {
                        LibraryAction(LibraryActionState(membership.inLibrary, membership.busy, membership.unavailable), application.collections::toggleLibrary)
                    },
                    notices = if (membership.unavailable || collectionError != null || historyFailed) ({
                        if (membership.unavailable) {
                            Text(strings[UiText.UI_LIBRARY_STORAGE_IS_UNAVAILABLE_ON_THIS_DEVICE])
                            Button(onClick = application.collections::refreshLibrary) { Text(strings[UiText.UI_RETRY_LIBRARY]) }
                        }
                        collectionError?.let { Text(strings.status(it), color = MaterialTheme.colors.error) }
                        if (historyFailed) Text(strings[UiText.UI_READING_HISTORY_COULD_NOT_BE_SAVED_ON_THIS_DEVICE])
                    }) else null,
                )
                is OpenPublicationState.PageReady -> key(current.reader) {
                    org.infinilect.app.reader.page.PageReader(current.reader, saveFailed, application::back, backLabel,
                        backHandler = backHandler, appearanceChanged = readerAppearance, controlsDark = appDark,
                        publicationActions = { LibraryAction(LibraryActionState(membership.inLibrary, membership.busy, membership.unavailable), application.collections::toggleLibrary) },
                        notices = {
                            if (membership.unavailable) {
                                Text(strings[UiText.UI_LIBRARY_STORAGE_IS_UNAVAILABLE_ON_THIS_DEVICE])
                                Button(onClick = application.collections::refreshLibrary) { Text(strings[UiText.UI_RETRY_LIBRARY]) }
                            }
                            collectionError?.let { Text(strings.status(it), color = MaterialTheme.colors.error) }
                            if (historyFailed) Text(strings[UiText.UI_READING_HISTORY_COULD_NOT_BE_SAVED_ON_THIS_DEVICE])
                        })
                }
                is OpenPublicationState.Ready, is OpenPublicationState.PdfReady -> Column(Modifier.fillMaxSize()) {
                    ReaderAppearanceEffect(ReaderAppearance(false, androidx.compose.ui.graphics.Color.White), readerAppearance)
                    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        LibraryAction(LibraryActionState(membership.inLibrary,membership.busy,membership.unavailable),
                            application.collections::toggleLibrary)
                    }
                    if(membership.unavailable) {
                        Text(strings[UiText.UI_LIBRARY_STORAGE_IS_UNAVAILABLE_ON_THIS_DEVICE],modifier=Modifier.padding(horizontal=24.dp))
                        Button(onClick=application.collections::refreshLibrary,modifier=Modifier.padding(horizontal=24.dp)) { Text(strings[UiText.UI_RETRY_LIBRARY]) }
                    }
                    collectionError?.let { Text(strings.status(it),modifier=Modifier.padding(horizontal=24.dp),color=MaterialTheme.colors.error) }
                    if(historyFailed) Text(strings[UiText.UI_READING_HISTORY_COULD_NOT_BE_SAVED_ON_THIS_DEVICE],modifier=Modifier.padding(horizontal=24.dp))
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        when (current) {
                            is OpenPublicationState.Ready -> TextReader(current.document,current.reading,saveFailed,application::back,backLabel)
                            is OpenPublicationState.PdfReady -> key(current.reader) { org.infinilect.app.reader.pdf.PdfReader(current.reader,saveFailed,application::back,backLabel) { savedPdfPage=it } }
                        }
                    }
                }
                is OpenPublicationState.Loading -> ApplicationTheme(mode) {
                    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ScreenHeading(current.publication.title, strings[UiText.UI_PREPARING_YOUR_READING_SESSION])
                        FeedbackCard(strings[UiText.UI_OPENING_PUBLICATION], strings[UiText.UI_YOUR_SAVED_READING_POSITION_WILL_BE_RESTORED_WHEN_THE_PUBLICATION_IS_READY], busy = true,
                            action = backLabel, onAction = application::back)
                    }
                }
                is OpenPublicationState.Error -> ApplicationTheme(mode) {
                    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ScreenHeading(current.publication.title, strings[UiText.UI_THIS_PUBLICATION_COULD_NOT_BE_OPENED])
                        FeedbackCard(strings[UiText.UI_UNABLE_TO_OPEN], strings.status(current.userMessage), error = true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = application::back) { Text(backLabel) }
                            androidx.compose.material.OutlinedButton(enabled = application.canRetry(current.publication), onClick = { application.retry(current.publication) }) { Text(strings[UiText.UI_TRY_AGAIN]) }
                        }
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
    modifier: Modifier = Modifier, progressRecords: List<org.infinilect.core.ReadingProgress> = emptyList(), descriptions: PublicationDescriptions? = null, applicationSession: ApplicationSession? = null) {
    val strings = LocalAppStrings.current
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
                ScreenHeading(strings[UiText.SEARCH], strings[UiText.UI_EXPLORE_THE_EXISTING_CATALOGS_OR_YOUR_IMPORTED_FILES])
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEachIndexed { index, source ->
                        androidx.compose.material.OutlinedButton(enabled = index != selected, onClick = { onSource(index) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.sourceName(source.name)) }
                    }
                }
                Text(strings.format(UiText.UI_SOURCE_1_S, strings.sourceName(sources[selected].name)))
                Text(if (session.textReadingEnabled && session.epubReadingEnabled && session.pageReadingEnabled) strings[UiText.UI_SEARCH_YOUR_IMPORTED_TEXT_EPUB_CBZ_AND_PDF_PUBLICATIONS_USE_TO_LIST_ALL_IMPORTS]
                    else if (session.textReadingEnabled) strings[UiText.UI_SEARCH_PUBLICATIONS_OPEN_COMPATIBLE_UTF_8_TEXT_UP_TO_16_MIB]
                    else if (session.pageReadingEnabled) strings[UiText.UI_ORIGINAL_DEVELOPMENT_COMIC_NO_PRODUCTION_COMIC_ACQUISITION_IS_ENABLED]
                    else if (session.epubReadingEnabled) strings[UiText.UI_ORIGINAL_DEVELOPMENT_EPUB_NO_PRODUCTION_EPUB_ACQUISITION_IS_ENABLED]
                    else strings[UiText.UI_EXPERIMENTAL_CATALOG_ONLY_TEXT_OPENING_IS_UNAVAILABLE_FOR_THIS_SOURCE])
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = session::editQuery,
                        label = { Text(strings[UiText.UI_TITLE_AUTHOR_OR_KEYWORD]) },
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
                    }) { Text(strings[UiText.SEARCH]) }
                }
            }
            // Keep one bounded lazy viewport for results and every feedback/pagination state.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = resultsPosition,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "search-status") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (loading) {
                            FeedbackCard(strings[UiText.UI_SEARCHING], strings[UiText.UI_YOUR_CURRENT_RESULTS_REMAIN_AVAILABLE_WHILE_THE_SOURCE_RESPONDS], busy = true)
                        }
                        when (val current = state) {
                            SearchState.Idle -> FeedbackCard(strings[UiText.UI_FIND_YOUR_NEXT_READ], strings[UiText.UI_ENTER_A_SEARCH_TO_DISCOVER_PUBLICATIONS])
                            is SearchState.Error -> FeedbackCard(strings[UiText.UI_SEARCH_COULD_NOT_FINISH], strings.status(current.message), error = true, action = strings[UiText.UI_TRY_AGAIN], onAction = session::submitSearch)
                            else -> Unit
                        }
                        if(membership.unavailable) {
                            Text(strings[UiText.UI_LIBRARY_STORAGE_IS_UNAVAILABLE_ON_THIS_DEVICE],color=MaterialTheme.colors.error)
                            Button(onClick=collections::refreshLibrary) { Text(strings[UiText.UI_RETRY_LIBRARY]) }
                        }
                        libraryError?.let { Text(strings.status(it),color=MaterialTheme.colors.error) }
                        if (displayed != null) {
                            if (displayed.page.publications.isEmpty()) FeedbackCard(strings[UiText.UI_NO_RESULTS], strings.format(UiText.UI_NO_PUBLICATIONS_FOUND_FOR_1_S, displayed.query))
                            else Text(strings.format(UiText.UI_RESULTS_FOR_1_S, displayed.query))
                        }
                    }
                }
                if (displayed != null) {
                    items(displayed.page.publications, key = { it.id.resultKey() }) { publication ->
                        PublicationCard(publication, sources[selected].name, publicationProgress(progressRecords, publication.id), details = { detail = publication }) {
                            if (session.textReadingEnabled || session.epubReadingEnabled || session.pageReadingEnabled || session.pdfReadingEnabled) {
                                Button(enabled = !loading, onClick = { onOpen(publication) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(if (session.pdfReadingEnabled) strings[UiText.UI_OPEN] else if (session.pageReadingEnabled) strings[UiText.UI_OPEN_PAGES] else if (session.epubReadingEnabled && !session.textReadingEnabled) strings[UiText.UI_OPEN_EPUB] else strings[UiText.UI_OPEN_TEXT])
                                }
                            }
                            CatalogLibraryAction(membership.forPublication(publication.id), onToggle = { collections.toggleCatalogLibrary(publication) })
                        }
                    }
                }
                if (displayed?.page?.nextPageToken != null) {
                    item(key = "search-pagination") {
                        Button(enabled = !loading, onClick = session::nextPage) { Text(strings[UiText.UI_NEXT_PAGE]) }
                    }
                }
                item(key = "search-notice") {
                    Text(strings[UiText.UI_AVAILABILITY_DOES_NOT_ESTABLISH_RIGHTS_IN_EVERY_COUNTRY_READING_POSITION_IS_SAVE], style = MaterialTheme.typography.caption)
                }
            }
        }
    }
    detail?.let { publication ->
        val known = publicationProgress(progressRecords, publication.id)
        val record = if (applicationSession != null) detailsProgress(applicationSession, publication.id, known) else known
        PublicationDetails(publication, sources[selected].name, publication.resources.map { it.format },
            record, close = { detail = null }, descriptions = descriptions) {
            if (session.textReadingEnabled || session.epubReadingEnabled || session.pageReadingEnabled || session.pdfReadingEnabled) {
                Button(enabled = !loading, onClick = { detail = null; onOpen(publication) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.status(readingAction(record))) }
            }
            CatalogLibraryAction(membership.forPublication(publication.id), onToggle = { collections.toggleCatalogLibrary(publication) })
        }
    }
}

@Composable
private fun LibraryAction(state: LibraryActionState, onToggle: () -> Unit) {
    Button(enabled=state.enabled,onClick=onToggle) {
        Text(LocalAppStrings.current.status(state.label))
    }
}

@Composable
private fun CatalogLibraryAction(state: LibraryActionState, onToggle: () -> Unit) {
    androidx.compose.material.OutlinedButton(enabled = state.enabled, onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(LocalAppStrings.current.status(state.label))
    }
}
