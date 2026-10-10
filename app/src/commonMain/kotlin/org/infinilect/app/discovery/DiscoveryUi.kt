// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.infinilect.app.discovery

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.infinilect.app.*
import org.infinilect.app.ui.*
import org.infinilect.core.*

/** Owned above the tab composition; a remount is not a new search/scroll intent. */
internal class DiscoverySearchViewport {
    private var previous: DiscoveryRequest? = null
    fun changed(request: DiscoveryRequest?): Boolean {
        if (previous == request) return false
        previous = request
        return true
    }
}

@Composable
internal fun UnifiedSearchScreen(application: ApplicationSession, position: LazyGridState, viewport: DiscoverySearchViewport) {
    val controller = application.discovery
    val state by controller.state.collectAsState()
    LaunchedEffect(state.request) { if (viewport.changed(state.request)) position.scrollToItem(0) }
    LaunchedEffect(controller) { controller.resume() }
    var detail by remember { mutableStateOf<DiscoveryEntry?>(null) }
    var filters by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.pause() } }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = DiscoveryStrings.TITLE }.padding(horizontal = 16.dp)) {
        val headerLimit = maxHeight / 2
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Bubble only unhandled keys: focused trailing buttons must own Enter.
                    OutlinedTextField(state.query, controller::edit, Modifier.weight(1f).onKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { controller.submit(); true } else false
                    }, label = { Text(DiscoveryStrings.SEARCH_LABEL) }, singleLine = true,
                        trailingIcon = if (state.query.isNotEmpty()) ({
                            IconButton({ controller.edit("") }, Modifier.size(48.dp).semantics { contentDescription = DiscoveryStrings.CLEAR_SEARCH }) { CloseSearchIcon() }
                        }) else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { controller.submit() }))
                    IconButton(controller::submit, Modifier.size(48.dp).semantics { contentDescription = DiscoveryStrings.SEARCH }) {
                        DestinationIcon(Destination.SEARCH, MaterialTheme.colors.onSurface)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ filters = true }, Modifier.heightIn(min = 48.dp).semantics { stateDescription = DiscoveryStrings.selectedSources(state.enabled.size) }) { Text(DiscoveryStrings.SOURCES_FILTERS) }
                    Text("${state.enabled.size}", style = MaterialTheme.typography.caption, modifier = Modifier.weight(1f))
                    IconButton({ help = true }, Modifier.size(48.dp).semantics { contentDescription = DiscoveryStrings.SEARCH_HELP }) { Text("?", style = MaterialTheme.typography.h6) }
                }
                if (state.genre != null || state.language != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.genre?.let { genre -> FilterChoice(genre.label, true) { controller.genre(null) } }
                    state.language?.let { language -> FilterChoice(DISCOVERY_LANGUAGES.getValue(language), true) { controller.language(null) } }
                    TextButton(controller::resetFilters, Modifier.heightIn(min = 48.dp)) { Text(DiscoveryStrings.RESET_FILTERS) }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(148.dp), Modifier.weight(1f).fillMaxWidth(), state = position,
                contentPadding = PaddingValues(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "status", contentType = "status") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                        if (state.invalidQuery) Text(DiscoveryStrings.QUERY_ERROR, color = MaterialTheme.colors.error)
                        if (state.request == null) Text(DiscoveryStrings.SEARCH_INTRO)
                        else if (state.catalogs.isEmpty()) Text(DiscoveryStrings.NO_SOURCES)
                        else if (!state.loading && state.entries.isEmpty() && state.catalogs.none { it.failed }) Text(if (state.genre == null) DiscoveryStrings.NO_MATCHES else DiscoveryStrings.NO_GENRE_MATCHES)
                        state.catalogs.forEach { source ->
                            if (source.failed) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(DiscoveryStrings.sourceUnavailable(application.sourceName(source.id)), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                                TextButton({ controller.retry(source.id) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = DiscoveryStrings.retrySource(application.sourceName(source.id)) }) { Text(DiscoveryStrings.RETRY) }
                            }
                        }
                    }
                }
                items(state.entries, key = { it.publication.id.resultKey() }, contentType = { "publication" }) { entry ->
                    DiscoveryTile(entry, application, Modifier.fillMaxWidth()) { detail = entry }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "more", contentType = "status") {
                    Column {
                        state.catalogs.filter { it.nextToken != null }.forEach { result ->
                            if (result.pages < 4) TextButton({ controller.more(result.id) }, Modifier.heightIn(min = 48.dp), enabled = !result.loading && !result.failed) { Text(DiscoveryStrings.moreFrom(application.sourceName(result.id))) }
                            else Text(DiscoveryStrings.PAGE_LIMIT, style = MaterialTheme.typography.caption)
                        }
                        Text(DiscoveryStrings.RIGHTS, style = MaterialTheme.typography.caption)
                    }
                }
            }
        }
        if (filters) AlertDialog(onDismissRequest = { filters = false }, title = { Text(DiscoveryStrings.FILTERS) },
            text = {
                Column(Modifier.heightIn(max = maxHeight * .6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(DiscoveryStrings.SOURCE_SECTION, style = MaterialTheme.typography.subtitle2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        application.catalogOptions.forEach { option -> FilterChoice(option.name, option.source.id in state.enabled) { controller.filter(option.source.id) } }
                    }
                    Text(DiscoveryStrings.LANGUAGE_SECTION, style = MaterialTheme.typography.subtitle2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChoice(DiscoveryStrings.ALL_LANGUAGES, state.language == null) { controller.language(null) }
                        DISCOVERY_LANGUAGES.forEach { (code, label) -> FilterChoice(label, state.language == code) { controller.language(code) } }
                    }
                    Text(DiscoveryStrings.SUBJECT_SECTION, style = MaterialTheme.typography.subtitle2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Genre.entries.forEach { genre -> FilterChoice(genre.label, state.genre == genre) { controller.genre(genre.takeUnless { it == state.genre }) } }
                    }
                }
            }, confirmButton = { TextButton({ filters = false }, Modifier.heightIn(min = 48.dp)) { Text(DiscoveryStrings.DONE) } },
            dismissButton = { TextButton(controller::resetFilters, Modifier.heightIn(min = 48.dp)) { Text(DiscoveryStrings.RESET_FILTERS) } })
        if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(DiscoveryStrings.SEARCH_HELP) },
            text = { Column(Modifier.heightIn(max = maxHeight * .6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(DiscoveryStrings.GENRE_NOTICE); Text(DiscoveryStrings.LANGUAGE_NOTICE); Text(DiscoveryStrings.RIGHTS); Text(DiscoveryStrings.PRIVACY); Text(DiscoveryStrings.COVER_NOTE)
            } }, confirmButton = { TextButton({ help = false }) { Text(DiscoveryStrings.DONE) } })
    }
    detail?.let { DiscoveryDetails(it, application) { detail = null } }
}

@Composable
private fun CloseSearchIcon() {
    val color = MaterialTheme.colors.onSurface
    Canvas(Modifier.size(20.dp).clearAndSetSemantics {}) {
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .25f), androidx.compose.ui.geometry.Offset(size.width * .75f, size.height * .75f), 2.dp.toPx())
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .75f, size.height * .25f), androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .75f), 2.dp.toPx())
    }
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, click: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).toggleable(value = selected, role = Role.Checkbox, onValueChange = { click() })
        .semantics { this.selected = selected }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.body2, modifier = Modifier
            .background(if (selected) MaterialTheme.colors.primary.copy(alpha = .14f) else MaterialTheme.colors.onSurface.copy(alpha = .06f), MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp))
    }
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
