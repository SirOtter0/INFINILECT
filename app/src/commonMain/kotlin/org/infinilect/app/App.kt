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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.reader.handlesBack
import org.infinilect.app.reader.TextReader
import org.infinilect.app.search.SearchState
import org.infinilect.core.PublicationSource

internal data class SourceOption(val name: String, val source: PublicationSource, val textReadingEnabled: Boolean = false)

@Composable
fun App(
    applicationSources: ApplicationSources,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    val sources = applicationSources.options
    var selected by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val option = sources[selected]
    val session = remember(option) {
        ReadingSession(option.source, scope, option.textReadingEnabled, applicationSources.loaderFor(option.source))
    }
    DisposableEffect(applicationSources, session) {
        applicationSources.attach(session)
        onDispose { applicationSources.detach(session) }
    }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // Reset collectors when source changes; never render a previous source's state for one frame.
            key(session) {
                val opening by session.opening.state.collectAsState()
                backHandler(opening.handlesBack(), session::back)
                when (val current = opening) {
                    OpenPublicationState.Idle -> SearchScreen(session, sources, selected) { index ->
                        if (index != selected) { session.close(); selected = index }
                    }
                    is OpenPublicationState.Ready -> TextReader(current.document, session::back)
                    is OpenPublicationState.Loading -> Column(Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(current.publication.title, style = MaterialTheme.typography.h6)
                        CircularProgressIndicator()
                        Text("Opening text…")
                        Button(onClick = session::back) { Text("Back to results") }
                    }
                    is OpenPublicationState.Error -> Column(Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(current.publication.title, style = MaterialTheme.typography.h6)
                        Text(current.userMessage, color = MaterialTheme.colors.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = session::back) { Text("Back to results") }
                            Button(onClick = { session.open(current.publication) }) { Text("Try again") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchScreen(session: ReadingSession, sources: List<SourceOption>, selected: Int, onSource: (Int) -> Unit) {
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
                            Button(enabled = !loading, onClick = { session.open(publication) }) { Text("Open text") }
                        }
                        Divider()
                    }
                }
            }
            if (displayed.page.nextPageToken != null) {
                Button(enabled = !loading, onClick = session::nextPage) { Text("Next page") }
            }
        }
        Text(if (session.textReadingEnabled) "Only public CC0 items with an eligible text file can be opened. No cache or saved reading position."
            else "Gutenberg availability in the US does not establish rights in every country.", style = MaterialTheme.typography.caption)
    }
}
