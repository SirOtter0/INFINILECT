// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.infinilect.app.discovery

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.infinilect.app.*
import org.infinilect.app.ui.*
import org.infinilect.core.*

@Composable
internal fun UnifiedSearchScreen(application: ApplicationSession, position: LazyGridState) {
    val controller = application.discovery
    val state by controller.state.collectAsState()
    LaunchedEffect(state.request) { position.scrollToItem(0) }
    var detail by remember { mutableStateOf<DiscoveryEntry?>(null) }
    var filters by remember { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.pause() } }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = DiscoveryStrings.TITLE }.padding(horizontal = 16.dp)) {
        val headerLimit = maxHeight / 2
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.query, controller::edit, Modifier.weight(1f).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { controller.submit(); true } else false
                    }, label = { Text(DiscoveryStrings.SEARCH_LABEL) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { controller.submit() }))
                    TextButton(controller::submit, Modifier.heightIn(min = 48.dp)) { Text(DiscoveryStrings.SEARCH) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ filters = !filters }, Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (filters) DiscoveryStrings.EXPANDED else DiscoveryStrings.COLLAPSED }) { Text(DiscoveryStrings.SOURCES_FILTERS) }
                    if (state.genre != null) TextButton({ controller.genre(null) }, Modifier.heightIn(min = 48.dp)) { Text(DiscoveryStrings.CLEAR_GENRE) }
                }
                if (filters) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        application.catalogOptions.forEach { option ->
                            FilterChoice(option.name, option.source.id in state.enabled) { controller.filter(option.source.id) }
                        }
                    }
                    Text(DiscoveryStrings.LANGUAGE_NOTICE, style = MaterialTheme.typography.caption)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChoice(DiscoveryStrings.ALL_LANGUAGES, state.language == null) { controller.language(null) } }
                        items(DISCOVERY_LANGUAGES.entries.toList()) { (code, label) -> FilterChoice(label, state.language == code) { controller.language(code) } }
                    }
                    Text(DiscoveryStrings.PRIVACY, style = MaterialTheme.typography.caption)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Genre.entries.toList()) { genre -> FilterChoice(genre.label, state.genre == genre) { controller.browse(genre) } }
                }
                if (state.genre != null) Text(DiscoveryStrings.GENRE_NOTICE, style = MaterialTheme.typography.caption)
            }
            LazyVerticalGrid(GridCells.Adaptive(148.dp), Modifier.weight(1f).fillMaxWidth(), state = position,
                contentPadding = PaddingValues(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                        if (state.invalidQuery) Text(DiscoveryStrings.QUERY_ERROR, color = MaterialTheme.colors.error)
                        if (state.request == null) Text(DiscoveryStrings.SEARCH_INTRO)
                        else if (state.catalogs.isEmpty()) Text(DiscoveryStrings.NO_SOURCES)
                        else if (!state.loading && state.entries.isEmpty() && state.catalogs.none { it.failed }) Text(DiscoveryStrings.NO_MATCHES)
                        state.catalogs.forEach { source ->
                            if (source.failed) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(DiscoveryStrings.sourceUnavailable(application.sourceName(source.id)), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                                TextButton({ controller.retry(source.id) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = DiscoveryStrings.retrySource(application.sourceName(source.id)) }) { Text(DiscoveryStrings.RETRY) }
                            }
                        }
                    }
                }
                items(state.entries, key = { it.publication.id.resultKey() }) { entry ->
                    DiscoveryTile(entry, application, Modifier.fillMaxWidth()) { detail = entry }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                    Column {
                        state.catalogs.filter { it.nextToken != null }.forEach { result ->
                            if (result.pages < 4) TextButton({ controller.more(result.id) }, Modifier.heightIn(min = 48.dp), enabled = !result.loading && !result.failed) { Text(DiscoveryStrings.moreFrom(application.sourceName(result.id))) }
                            else Text(DiscoveryStrings.PAGE_LIMIT, style = MaterialTheme.typography.caption)
                        }
                        Text(DiscoveryStrings.RIGHTS, style = MaterialTheme.typography.caption)
                        Text(DiscoveryStrings.COVER_NOTE, style = MaterialTheme.typography.caption)
                    }
                }
            }
        }
    }
    detail?.let { DiscoveryDetails(it, application) { detail = null } }
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, click: () -> Unit) {
    OutlinedButton(click, Modifier.heightIn(min = 48.dp).semantics { this.selected = selected },
        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = if (selected) MaterialTheme.colors.primary.copy(alpha = .14f) else MaterialTheme.colors.surface)) { Text(label) }
}

@Composable
internal fun DiscoveryTile(entry: DiscoveryEntry, application: ApplicationSession, modifier: Modifier = Modifier, open: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Reuse owned local art or a source-advertised bounded thumbnail; never acquire book bytes.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            HomeCover(entry.publication, if (entry.thumbnail != null || entry.publication.id.sourceId.value == "local-imports") application.covers else null, null, false) { open() }
        }
        if (entry.publication.authors.isNotEmpty()) Text(entry.publication.authors.joinToString("; "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption)
        Text(application.sourceName(entry.publication.id.sourceId), style = MaterialTheme.typography.caption)
        if (entry.publication.languages.isNotEmpty()) Text(entry.publication.languages.joinToString(", "), style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun DiscoveryDetails(entry: DiscoveryEntry, application: ApplicationSession, close: () -> Unit) {
    val membership by application.collections.membership.collectAsState()
    val error by application.collections.error.collectAsState()
    var confirmRemoval by remember(entry.publication.id) { mutableStateOf(false) }
    val record = detailsProgress(application, entry.publication.id, null)
    PublicationDetails(entry.publication, application.sourceName(entry.publication.id.sourceId), entry.publication.resources.map { it.format },
        record, close, cover = { DetailsCover(entry.publication, if (entry.thumbnail != null || entry.publication.id.sourceId.value == "local-imports") application.covers else null) },
        descriptions = application.descriptions, suppliedSynopsis = entry.description, subjects = entry.subjects) {
        if (application.canRetry(entry.publication)) Button({ close(); application.openDiscovered(entry.publication) }, Modifier.heightIn(min = 48.dp)) { Text(readingAction(record)) }
        else Text(DiscoveryStrings.CATALOG_ONLY, style = MaterialTheme.typography.body2)
        if (entry.publication.rights == null) Text(DiscoveryStrings.RIGHTS_UNKNOWN, style = MaterialTheme.typography.caption)
        val action = membership.forPublication(entry.publication.id)
        OutlinedButton({ if (action.inLibrary == true) confirmRemoval = true else application.collections.toggleCatalogLibrary(entry.publication) }, Modifier.heightIn(min = 48.dp), enabled = action.enabled) { Text(action.label) }
        error?.let { Text(it, color = MaterialTheme.colors.error) }
    }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false }, title = { Text(DiscoveryStrings.REMOVE_FROM_LIBRARY) },
        text = { Text(DiscoveryStrings.REMOVAL_NOTICE) },
        confirmButton = { TextButton({ confirmRemoval = false; application.collections.removeLibrary(entry.publication.id) }) { Text(DiscoveryStrings.REMOVE) } },
        dismissButton = { TextButton({ confirmRemoval = false }) { Text(DiscoveryStrings.CANCEL) } })
}

@Composable
internal fun DiscoveryPreferenceControls(preferences: DiscoveryPreferences, change: (DiscoveryPreferences) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(DiscoveryStrings.DISCOVERY, style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(DiscoveryStrings.SHOW_ONLINE_DISCOVERY_ON_HOME, Modifier.weight(1f)); Switch(preferences.homeEnabled, { change(preferences.copy(homeEnabled = it)) }, Modifier.semantics { contentDescription = DiscoveryStrings.SHOW_ONLINE_DISCOVERY_ON_HOME })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(DiscoveryStrings.PERSONALIZE_WITH_LOCAL_INTERESTS, Modifier.weight(1f)); Switch(preferences.personalized, { change(preferences.copy(personalized = it)) }, Modifier.semantics { contentDescription = DiscoveryStrings.PERSONALIZE_WITH_LOCAL_INTERESTS })
        }
        Text(DiscoveryStrings.RECOMMENDATION_NOTICE, style = MaterialTheme.typography.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Genre.entries.forEach { genre -> FilterChoice(genre.label, genre in preferences.interests) {
                change(preferences.copy(interests = preferences.interests.toMutableSet().apply { if (!add(genre)) remove(genre) }))
            } }
        }
        TextButton({ change(preferences.copy(interests = emptySet(), inferenceAfter = kotlin.time.Clock.System.now().toEpochMilliseconds())) }, Modifier.heightIn(min = 48.dp)) { Text(DiscoveryStrings.RESET_RECOMMENDATION_PREFERENCES) }
        Text(DiscoveryStrings.PRIVACY, style = MaterialTheme.typography.caption)
        Text(DiscoveryStrings.COUNTRY_NOTICE, style = MaterialTheme.typography.caption)
    }
}
