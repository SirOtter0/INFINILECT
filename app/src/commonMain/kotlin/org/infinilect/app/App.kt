// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import org.infinilect.core.PublicationSource

internal data class SourceOption(val name: String, val source: PublicationSource, val textReadingEnabled: Boolean = false)

@Composable
fun App(
    applicationSources: ApplicationSources,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    val scope=rememberCoroutineScope()
    val application=remember(applicationSources) { ApplicationSession(applicationSources,scope) }
    DisposableEffect(applicationSources,application) {
        applicationSources.attach(application)
        onDispose { applicationSources.detach(application) }
    }
    val session by application.searchSession.collectAsState()
    val selected by application.selected.collectAsState()
    val destination by application.destination.collectAsState()
    val opening by application.opening.collectAsState()
    val membership by application.collections.reader.collectAsState()
    val collectionError by application.collections.error.collectAsState()
    val historyFailed by (applicationSources.collections?.historyFailed
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val saveFailed by (applicationSources.progress?.saveFailed
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val backLabel=when(destination) { Destination.LIBRARY -> "Back to Library"; Destination.HISTORY -> "Back to History"; else -> "Back to results" }
    backHandler(application.handlesBack(),application::back)
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when(val current=opening) {
                OpenPublicationState.Idle -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Destination.entries.forEach { target ->
                            Button(enabled=destination!=target,onClick={ application.navigate(target) },modifier=Modifier.weight(1f)) {
                                Text(when(target) { Destination.SEARCH -> "Search"; Destination.LIBRARY -> "Library"; Destination.HISTORY -> "History" })
                            }
                        }
                    }
                    if(destination==Destination.SEARCH) key(session) {
                        SearchScreen(session,applicationSources.options,selected,application::selectSource,application::openSearch)
                    } else CollectionScreen(destination,application)
                }
                is OpenPublicationState.Ready -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=membership.inLibrary!=null && !membership.busy && !membership.failed,
                            onClick=application.collections::toggleLibrary) {
                            Text(if(membership.inLibrary==true) "Remove from Library" else "Add to Library")
                        }
                        if(membership.busy) Text("Saving…")
                    }
                    if(membership.failed) Text("Library storage is unavailable on this device.",modifier=Modifier.padding(horizontal=24.dp))
                    collectionError?.let { Text(it,modifier=Modifier.padding(horizontal=24.dp),color=MaterialTheme.colors.error) }
                    if(historyFailed) Text("Reading history could not be saved on this device.",modifier=Modifier.padding(horizontal=24.dp))
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        TextReader(current.document,current.reading,saveFailed,application::back,backLabel)
                    }
                }
                is OpenPublicationState.Loading -> Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(current.publication.title,style=MaterialTheme.typography.h6)
                    CircularProgressIndicator(); Text("Opening text…")
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
    val isLibrary=destination==Destination.LIBRARY
    val entries=if(isLibrary) library.entries.map { it.publication } else history.entries.map { it.publication }
    val loading=if(isLibrary) library.loading else history.loading
    val failed=if(isLibrary) library.failed else history.failed
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(if(isLibrary) "Library" else "History",style=MaterialTheme.typography.h4)
        Text(if(isLibrary) "Saved publication metadata. Opening always checks the source." else "Successfully opened publications · 50 most recent.")
        if(loading) CircularProgressIndicator()
        if(failed) {
            Text("Local storage is unavailable. Please try again.",color=MaterialTheme.colors.error)
            Button(onClick={ if(isLibrary) controller.refreshLibrary() else controller.refreshHistory() }) { Text("Try again") }
        }
        error?.let { Text(it,color=MaterialTheme.colors.error) }
        if(entries.isEmpty() && !loading && !failed) Text(if(isLibrary) "Your library is empty. Add a publication from its reader." else "No reading history yet.")
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(entries,key={ it.id.resultKey() }) { publication ->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(publication.title,style=MaterialTheme.typography.h6)
                    if(publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "))
                    Text("Source: ${publication.id.sourceId.value}",style=MaterialTheme.typography.caption)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={ application.openSaved(publication) }) { Text("Open text") }
                        Button(enabled=!busy,onClick={ if(isLibrary) controller.removeLibrary(publication.id) else controller.removeHistory(publication.id) }) { Text("Remove") }
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
internal fun SearchScreen(session: ReadingSession, sources: List<SourceOption>, selected: Int, onSource: (Int) -> Unit, onOpen: (org.infinilect.core.Publication) -> Unit = session::open) {
    val controller = session.search
    val state by controller.state.collectAsState()
    val query by session.query.collectAsState()
    val loading = state is SearchState.Loading
    val displayed = when (val current = state) {
        is SearchState.Results -> current.result
        is SearchState.Empty -> current.result
        is SearchState.Loading -> current.previous
        is SearchState.Error -> current.previous
        SearchState.Idle -> null
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("INFINILECT", style = MaterialTheme.typography.h4)
        Text("Open knowledge. Infinite reading.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            sources.forEachIndexed { index, source ->
                Button(modifier = Modifier.weight(1f), enabled = index != selected, onClick = { onSource(index) }) { Text(source.name) }
            }
        }
        Text("Source: ${sources[selected].name}")
        Text(if (session.textReadingEnabled) "Search public CC0 text items. Open UTF-8 text up to 512 KiB."
            else "Search books on Project Gutenberg. Acquisition is not available yet.")
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
        if (displayed != null) {
            if (displayed.page.publications.isEmpty()) Text("No publications found for “${displayed.query}”.")
            else Text("Results for “${displayed.query}”")
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(displayed.page.publications, key = { it.id.resultKey() }) { publication ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(publication.title, style = MaterialTheme.typography.h6)
                        if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "))
                        if (publication.languages.isNotEmpty()) Text("Language: ${publication.languages.joinToString(", ")}")
                        publication.rights?.let { Text(it, style = MaterialTheme.typography.caption) }
                        if (session.textReadingEnabled) {
                            Button(enabled = !loading, onClick = { onOpen(publication) }) { Text("Open text") }
                        }
                        Divider()
                    }
                }
            }
            if (displayed.page.nextPageToken != null) {
                Button(enabled = !loading, onClick = session::nextPage) { Text("Next page") }
            }
        }
        Text(if (session.textReadingEnabled) "Only public CC0 items with an eligible text file can be opened. Reading position is saved locally; reopening still checks the source."
            else "Gutenberg availability in the US does not establish rights in every country.", style = MaterialTheme.typography.caption)
    }
}
