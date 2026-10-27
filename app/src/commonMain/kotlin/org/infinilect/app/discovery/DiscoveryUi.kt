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
    val strings = LocalAppStrings.current
    val controller = application.discovery
    val state by controller.state.collectAsState()
    LaunchedEffect(state.request) { if (viewport.changed(state.request)) position.scrollToItem(0) }
    LaunchedEffect(controller) { controller.resume() }
    var detail by remember { mutableStateOf<DiscoveryEntry?>(null) }
    var filters by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.pause() } }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = strings[UiText.UI_SEARCH_DISCOVER] }.padding(horizontal = 16.dp)) {
        val headerLimit = maxHeight / 2
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Bubble only unhandled keys: focused trailing buttons must own Enter.
                    OutlinedTextField(state.query, controller::edit, Modifier.weight(1f).onKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { controller.submit(); true } else false
                    }, label = { Text(strings[UiText.UI_TITLE_AUTHOR_OR_KEYWORD]) }, singleLine = true,
                        trailingIcon = if (state.query.isNotEmpty()) ({
                            IconButton({ controller.edit("") }, Modifier.size(48.dp).semantics { contentDescription = strings[UiText.UI_CLEAR_SEARCH] }) { CloseSearchIcon() }
                        }) else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { controller.submit() }))
                    IconButton(controller::submit, Modifier.size(48.dp).semantics { contentDescription = strings[UiText.SEARCH] }) {
                        DestinationIcon(Destination.SEARCH, MaterialTheme.colors.onSurface)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ filters = true }, Modifier.heightIn(min = 48.dp).semantics { stateDescription = strings.sourceCount(state.enabled.size) }) { Text(strings[UiText.UI_SOURCES_FILTERS]) }
                    Text(strings.number(state.enabled.size), style = MaterialTheme.typography.caption, modifier = Modifier.weight(1f))
                    IconButton({ help = true }, Modifier.size(48.dp).semantics { contentDescription = strings[UiText.UI_ABOUT_CATALOG_SEARCH] }) { Text("?", style = MaterialTheme.typography.h6) }
                }
                if (state.genre != null || state.language != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.genre?.let { genre -> FilterChoice(strings.status(genre.label), true) { controller.genre(null) } }
                    state.language?.let { language -> FilterChoice(strings.status(DISCOVERY_LANGUAGES.getValue(language)), true) { controller.language(null) } }
                    TextButton(controller::resetFilters, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.UI_RESET_FILTERS]) }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(148.dp), Modifier.weight(1f).fillMaxWidth(), state = position,
                contentPadding = PaddingValues(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "status", contentType = "status") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                        if (state.invalidQuery) Text(strings[UiText.UI_USE_AT_MOST_256_CHARACTERS_AND_NO_CONTROL_CHARACTERS], color = MaterialTheme.colors.error)
                        if (state.request == null) Text(strings[UiText.UI_SEARCH_ACROSS_CATALOGS_OR_EXPLORE_A_SUBJECT_ABOVE_NOTHING_IS_DOWNLOADED_BY_BROWS])
                        else if (state.catalogs.isEmpty()) Text(strings[UiText.UI_SELECT_AT_LEAST_ONE_SOURCE_SUBJECT_BROWSING_CURRENTLY_REQUIRES_INTERNET_ARCHIVE])
                        else if (!state.loading && state.entries.isEmpty() && state.catalogs.none { it.failed }) Text(if (state.genre == null) strings[UiText.UI_NO_MATCHES_ON_LOADED_PAGES_LOAD_MORE_OR_TRY_ANOTHER_QUERY_SOURCE_OR_LANGUAGE] else strings[UiText.UI_NO_MATCHES_IN_THE_LOADED_CC0_CATALOG_PAGES_LOAD_MORE_CHANGE_THE_LANGUAGE_OR_TRY])
                        state.catalogs.forEach { source ->
                            if (source.failed) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(strings.status(catalogFailureMessage(application.sourceName(source.id), source.failure)), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                                TextButton({ controller.retry(source.id) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = strings.format(UiText.UI_RETRY_1_S, strings.sourceName(application.sourceName(source.id))) }) { Text(strings[UiText.RETRY]) }
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
                            if (result.pages < 4) TextButton({ controller.more(result.id) }, Modifier.heightIn(min = 48.dp), enabled = !result.loading && !result.failed) { Text(strings.format(UiText.UI_MORE_FROM_1_S, strings.sourceName(application.sourceName(result.id)))) }
                            else Text(strings[UiText.UI_SHOWING_UP_TO_100_RESULTS_PER_SOURCE_REFINE_YOUR_SEARCH_FOR_MORE], style = MaterialTheme.typography.caption)
                        }
                        Text(strings[UiText.UI_CATALOG_PRESENCE_IS_NOT_PERMISSION_TO_DOWNLOAD_OR_PROOF_OF_RIGHTS_IN_YOUR_COUNTR], style = MaterialTheme.typography.caption)
                    }
                }
            }
        }
        if (filters) AlertDialog(onDismissRequest = { filters = false }, title = { Text(strings[UiText.UI_FILTERS]) },
            text = {
                Column(Modifier.heightIn(max = maxHeight * .6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings[UiText.UI_SOURCES], style = MaterialTheme.typography.subtitle2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        application.catalogOptions.forEach { option -> FilterChoice(strings.sourceName(option.name), option.source.id in state.enabled) { controller.filter(option.source.id) } }
                    }
                    Text(strings[UiText.UI_LANGUAGE], style = MaterialTheme.typography.subtitle2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChoice(strings[UiText.UI_ALL_LANGUAGES], state.language == null) { controller.language(null) }
                        DISCOVERY_LANGUAGES.forEach { (code, label) -> FilterChoice(strings.status(label), state.language == code) { controller.language(code) } }
                    }
                    Text(strings[UiText.UI_SUBJECT_INTERNET_ARCHIVE], style = MaterialTheme.typography.subtitle2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Genre.entries.forEach { genre -> FilterChoice(strings.status(genre.label), state.genre == genre) { controller.genre(genre.takeUnless { it == state.genre }) } }
                    }
                }
            }, confirmButton = { TextButton({ filters = false }, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.DONE]) } },
            dismissButton = { TextButton(controller::resetFilters, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.UI_RESET_FILTERS]) } })
        if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(strings[UiText.UI_ABOUT_CATALOG_SEARCH]) },
            text = { Column(Modifier.heightIn(max = maxHeight * .6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings[UiText.UI_SUBJECTS_SEARCH_INTERNET_ARCHIVE_S_LIMITED_CC0_TEXT_CATALOG_NOT_THE_WHOLE_ARCHIV]); Text(strings[UiText.UI_LANGUAGES_FILTER_RETURNED_METADATA_MISSING_LANGUAGE_IS_EXCLUDED]); Text(strings[UiText.UI_CATALOG_PRESENCE_IS_NOT_PERMISSION_TO_DOWNLOAD_OR_PROOF_OF_RIGHTS_IN_YOUR_COUNTR]); Text(strings[UiText.DISCOVERY_PRIVACY]); Text(strings[UiText.UI_ADVERTISED_GUTENBERG_THUMBNAILS_ARE_OPTIONAL_OTHER_OR_UNAVAILABLE_ARTWORK_USES_A])
            } }, confirmButton = { TextButton({ help = false }) { Text(strings[UiText.DONE]) } })
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
        HomeCover(entry.publication, if (entry.thumbnail != null || entry.publication.id.sourceId.value == "local-imports") application.covers else null,
            null, false, Modifier.align(Alignment.CenterHorizontally)) { open() }
        // LazyRow measures only its active children. Reserve the same scalable text slots
        // so missing metadata or a wrapped attribution cannot rebase the vertical Home list
        // as different cards enter the horizontal viewport. Empty slots invent no metadata.
        Text(entry.publication.authors.joinToString("; "), minLines = 1, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption)
        Text(LocalAppStrings.current.sourceName(application.sourceName(entry.publication.id.sourceId)), minLines = 1, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption)
        Text(entry.publication.languages.joinToString(", "), minLines = 1, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption)
    }
}

@Composable
internal fun DiscoveryDetails(entry: DiscoveryEntry, application: ApplicationSession, close: () -> Unit) {
    val strings = LocalAppStrings.current
    val record = detailsProgress(application, entry.publication.id, null)
    PublicationDetails(entry.publication, application.sourceName(entry.publication.id.sourceId), entry.publication.resources.map { it.format },
        record, close, cover = { DetailsCover(entry.publication, if (entry.thumbnail != null || entry.publication.id.sourceId.value == "local-imports") application.covers else null) },
        descriptions = application.descriptions, suppliedSynopsis = entry.description, subjects = entry.subjects,collections=application.collections,downloads=application.downloads,organizationCovers=application.covers) {
        if (application.canRetry(entry.publication)) PublicationReadAction(record) { close(); application.openDiscovered(entry.publication) }
        else Text(strings[UiText.UI_CATALOG_METADATA_ONLY_ACQUISITION_IS_UNAVAILABLE_FOR_THIS_SOURCE], style = MaterialTheme.typography.body2)
        if (entry.publication.rights == null) Text(strings[UiText.UI_NO_SOURCE_RIGHTS_STATEMENT_WAS_SUPPLIED_LEGAL_AVAILABILITY_IS_UNDETERMINED], style = MaterialTheme.typography.caption)
    }

}

@Composable
internal fun DiscoveryPreferenceControls(preferences: DiscoveryPreferences, change: (DiscoveryPreferences) -> Unit) {
    val strings = org.infinilect.app.ui.LocalAppStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings[org.infinilect.app.ui.UiText.DISCOVERY], style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings[org.infinilect.app.ui.UiText.ONLINE_DISCOVERY], Modifier.weight(1f)); Switch(preferences.homeEnabled, { change(preferences.copy(homeEnabled = it)) }, Modifier.semantics { contentDescription = strings[org.infinilect.app.ui.UiText.ONLINE_DISCOVERY] })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings[org.infinilect.app.ui.UiText.LOCAL_INTERESTS], Modifier.weight(1f)); Switch(preferences.personalized, { change(preferences.copy(personalized = it)) }, Modifier.semantics { contentDescription = strings[org.infinilect.app.ui.UiText.LOCAL_INTERESTS] })
        }
        Text(strings[org.infinilect.app.ui.UiText.RECOMMENDATION_NOTICE], style = MaterialTheme.typography.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Genre.entries.forEach { genre -> FilterChoice(strings[when(genre) {
                Genre.SCIENCE_FICTION -> org.infinilect.app.ui.UiText.SCIENCE_FICTION
                Genre.FANTASY -> org.infinilect.app.ui.UiText.FANTASY
                Genre.MYSTERY -> org.infinilect.app.ui.UiText.MYSTERY
                Genre.HISTORY -> org.infinilect.app.ui.UiText.GENRE_HISTORY
                Genre.PHILOSOPHY -> org.infinilect.app.ui.UiText.PHILOSOPHY
                Genre.ADVENTURE -> org.infinilect.app.ui.UiText.ADVENTURE
                Genre.ROMANCE -> org.infinilect.app.ui.UiText.ROMANCE
            }], genre in preferences.interests) {
                change(preferences.copy(interests = preferences.interests.toMutableSet().apply { if (!add(genre)) remove(genre) }))
            } }
        }
        TextButton({ change(preferences.copy(interests = emptySet(), inferenceAfter = kotlin.time.Clock.System.now().toEpochMilliseconds())) }, Modifier.heightIn(min = 48.dp)) { Text(strings[org.infinilect.app.ui.UiText.RESET_RECOMMENDATIONS]) }
        Text(strings[org.infinilect.app.ui.UiText.DISCOVERY_PRIVACY], style = MaterialTheme.typography.caption)
        Text(strings[org.infinilect.app.ui.UiText.DISCOVERY_COUNTRY], style = MaterialTheme.typography.caption)
    }
}
