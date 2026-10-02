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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.infinilect.app.search.SearchController
import org.infinilect.app.search.SearchState

@Composable
fun App(controller: SearchController) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SearchScreen(controller)
        }
    }
}

@Composable
fun SearchScreen(controller: SearchController) {
    val state by controller.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
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
        Text("Search books on Project Gutenberg. Reading is coming later.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Title, author, or keyword") },
                singleLine = true,
                enabled = !loading,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (!loading && query.isNotBlank()) scope.launch { controller.search(query) }
                }),
            )
            Button(enabled = !loading && query.isNotBlank(), onClick = {
                scope.launch { controller.search(query) }
            }) { Text("Search") }
        }
        if (loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Searching…")
            }
        }
        when (val current = state) {
            SearchState.Idle -> Text("Enter a search to discover books.")
            is SearchState.Error -> Text(current.message, color = MaterialTheme.colors.error)
            else -> Unit
        }
        if (displayed != null) {
            if (displayed.page.publications.isEmpty()) Text("No books found for “${displayed.query}”.")
            else Text("Results for “${displayed.query}”")
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(displayed.page.publications, key = { it.id }) { publication ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(publication.title, style = MaterialTheme.typography.h6)
                        if (publication.authors.isNotEmpty()) Text(publication.authors.joinToString("; "))
                        if (publication.languages.isNotEmpty()) Text("Language: ${publication.languages.joinToString(", ")}")
                        publication.rights?.let { Text(it, style = MaterialTheme.typography.caption) }
                        Divider()
                    }
                }
            }
            if (displayed.page.nextPageToken != null) {
                Button(enabled = !loading, onClick = { scope.launch { controller.nextPage() } }) { Text("Next page") }
            }
        }
        Text("Gutenberg availability in the US does not establish rights in every country.", style = MaterialTheme.typography.caption)
    }
}
