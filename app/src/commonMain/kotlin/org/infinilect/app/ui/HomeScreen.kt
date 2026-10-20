// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.infinilect.app.*
import org.infinilect.core.*
import org.infinilect.app.discovery.*
import org.infinilect.app.diagnostics.*

internal fun homeRecentLibrary(entries: List<LibraryEntry>): List<PublicationSnapshot> = entries.sortedByDescending { it.addedAtEpochMillis }
    .distinctBy { it.publication.id }.take(8).map { it.publication }
internal fun homeContinue(history: List<HistoryEntry>, progress: List<ReadingProgress>): List<PublicationSnapshot> = history
    .sortedByDescending { it.lastOpenedAtEpochMillis }.distinctBy { it.publication.id }
    .filter { publicationProgress(progress,it.publication.id)!=null }.take(8).map { it.publication }

/** Local sections remain independent of optional bounded discovery; neither owns reader state. */
@Composable
internal fun HomeScreen(application: ApplicationSession, preferences: ApplicationAppearanceState,
    progress: List<ReadingProgress>, settings: ApplicationAppearancePreferences?, position: LazyListState,
    importAction: (() -> Unit)?, editProfile: () -> Unit, settingsFailed: Boolean = false) {
    val strings = LocalAppStrings.current
    val library by application.collections.library.collectAsState()
    val history by application.collections.history.collectAsState()
    val progressById = remember(progress) { indexPublicationProgress(progress) }
    val continued = remember(history.entries, progress) { homeContinue(history.entries,progress) }
    val added = remember(library.entries) { homeRecentLibrary(library.entries) }
    val online by application.homeDiscovery.state.collectAsState()
    // Home uses publication metadata, not Search's query/filter/loading state.
    val searchEntries by remember(application) {
        application.discovery.state.map { it.entries }.distinctUntilChanged()
    }.collectAsState(application.discovery.state.value.entries)
    var onlineDetail by remember { mutableStateOf<DiscoveryEntry?>(null) }
    LaunchedEffect(preferences.loaded, preferences.discovery) {
        if (preferences.loaded) application.homeDiscovery.refresh(preferences.discovery)
    }
    DisposableEffect(application) {
        application.technicalDiagnostics.event(TechnicalEvent.HOME_VISIBLE)
        onDispose {
            application.technicalDiagnostics.event(TechnicalEvent.HOME_DISPOSED)
            application.homeDiscovery.pause()
        }
    }
    val known = remember(online.rows, searchEntries) { (online.rows.flatMap { it.entries } + searchEntries).associateBy { it.publication.id } }
    val inferred = remember(preferences.discovery.personalized, preferences.discovery.inferenceAfter, history.entries, known) { if (preferences.discovery.personalized) history.entries
        .filter { it.lastOpenedAtEpochMillis > preferences.discovery.inferenceAfter }.take(20)
        .flatMap { known[it.publication.id]?.genres.orEmpty() }.groupingBy { it }.eachCount() else emptyMap() }
    val interests = if (preferences.discovery.personalized) preferences.discovery.interests else emptySet()
    val personalized = preferences.discovery.personalized && (interests.isNotEmpty() || inferred.isNotEmpty())
    val recommendations = remember(online.rows, interests, library.entries, inferred) { rankRecommendations(online.rows.flatMap { it.entries }, interests,
        library.entries.map { it.publication.id }.toSet(), inferred) }
    var detail by remember { mutableStateOf<PublicationSnapshot?>(null) }
    var confirmRemoval by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf<PublicationFormat?>(null) }
    val membership by application.collections.membership.collectAsState()
    val error by application.collections.error.collectAsState()
    LazyColumn(Modifier.fillMaxSize().semantics { paneTitle=strings[UiText.HOME] }, state=position, contentPadding=PaddingValues(horizontal=16.dp, vertical=8.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item(key="greeting") {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(preferences.profile.displayName?.let { strings.format(UiText.HELLO_NAME, it) } ?: strings[UiText.HELLO],style=MaterialTheme.typography.h5,modifier=Modifier.semantics { heading() })
                Text(strings[UiText.GREETING_SUBTITLE],style=MaterialTheme.typography.body1,color=MaterialTheme.colors.onBackground.copy(alpha=.75f))
            }
        }
        if (preferences.loaded && !preferences.profile.setupHandled && settings!=null) item(key="profile-setup") {
            Surface(shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(strings[UiText.UI_MAKE_THIS_SPACE_YOURS],style=MaterialTheme.typography.subtitle1)
                    Text(strings[UiText.UI_A_NAME_IS_OPTIONAL_CHOOSE_A_RESIDENCE_COUNTRY_FOR_FUTURE_DISCOVERY_NO_ONLINE_ACC])
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(editProfile,Modifier.heightIn(min=48.dp)) { Text(strings[UiText.UI_SET_UP_PROFILE]) }
                        TextButton({settings.changeProfile(preferences.profile.copy(setupHandled=true))},Modifier.heightIn(min=48.dp)) { Text(strings[UiText.SET_UP_LATER]) }
                    }
                }
            }
        }
        if (settingsFailed) item(key="settings-error") {
            FeedbackCard(strings[UiText.SETTINGS_SAVE_ERROR],strings[UiText.UI_YOUR_PROFILE_APPLIES_FOR_NOW_RETRY_TO_KEEP_IT_AFTER_RESTARTING],error=true,
                action=strings[UiText.RETRY_SETTINGS],onAction={settings?.retry()})
        }
        error?.let { message -> item(key="collection-error") { FeedbackCard(strings[UiText.UI_CHANGE_COULD_NOT_BE_SAVED],strings.status(message),error=true) } }
        if (library.failed || history.failed) item(key="storage-error") {
            FeedbackCard(strings[UiText.UI_LOCAL_COLLECTION_UNAVAILABLE],strings[UiText.UI_YOUR_PUBLICATIONS_AND_POSITIONS_HAVE_NOT_BEEN_REMOVED],error=true,
                action=strings[UiText.UI_TRY_AGAIN],onAction={application.collections.refreshLibrary();application.collections.refreshHistory()})
        }
        if (continued.isNotEmpty()) item(key="continue") {
            HomeSection(strings[UiText.CONTINUE_READING],strings[UiText.VIEW_HISTORY],{application.navigate(Destination.HISTORY)}) {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(continued,key={it.id.resultKey()},contentType={"publication"}) { publication ->
                        HomeCover(remember(publication) { publication.displayPublication() },application.covers,progressById[publication.id],resume=true) { application.openSaved(publication) }
                    }
                }
            }
        }
        if (preferences.discovery.homeEnabled && recommendations.isNotEmpty()) item(key="discovery-recommendations") {
            HomeSection(if (personalized) strings[UiText.UI_RECOMMENDED_FOR_YOU] else strings[UiText.UI_EXPLORE_THE_CATALOGS],strings[UiText.UI_VIEW_SEARCH],{application.navigate(Destination.SEARCH)}) {
                Text(if (personalized) strings[UiText.UI_BASED_ON_SELECTED_INTERESTS_AND_KNOWN_SUBJECTS_IN_LOCAL_READING_ACTIVITY] else strings[UiText.UI_GENERAL_CATALOG_DISCOVERY_NOT_PERSONALIZED],style=MaterialTheme.typography.caption)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(recommendations,key={it.publication.id.resultKey()},contentType={"publication"}) { entry -> DiscoveryTile(entry,application,Modifier.width(144.dp)) { onlineDetail=entry } }
                }
            }
        }
        if (added.isNotEmpty()) item(key="added") {
            HomeSection(strings[UiText.RECENTLY_ADDED],strings[UiText.VIEW_LIBRARY],{application.navigate(Destination.LIBRARY)}) {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(added,key={it.id.resultKey()},contentType={"publication"}) { publication ->
                        HomeCover(remember(publication) { publication.displayPublication() },application.covers,progressById[publication.id],resume=false) { detail=publication;format=it }
                    }
                }
            }
        }
        if (preferences.discovery.homeEnabled) {
            online.rows.filter { it.genre != null }.forEach { row -> item(key="discovery-${row.key}") {
                HomeSection(strings.status(row.title),strings[UiText.UI_VIEW_ALL],{application.discovery.browse(row.genre!!);application.navigate(Destination.SEARCH)}) {
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        items(row.entries,key={it.publication.id.resultKey()},contentType={"publication"}) { entry -> DiscoveryTile(entry,application,Modifier.width(144.dp)) {onlineDetail=entry} }
                    }
                }
            } }
            if (online.loading) item(key="discovery-loading") { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("home-discovery-loading")) }
            if (online.failed) item(key="discovery-error") { FeedbackCard(strings[UiText.UI_ONLINE_DISCOVERY_UNAVAILABLE],strings[UiText.UI_LOCAL_READING_IS_READY_RETRY_CATALOGS_WHEN_CONNECTED],
                action=strings[UiText.UI_RETRY_DISCOVERY],onAction={application.homeDiscovery.refresh(preferences.discovery,true)}) }
            item(key="discovery-notice") { Text(strings[UiText.UI_CATALOG_PRESENCE_IS_NOT_PERMISSION_TO_DOWNLOAD_OR_PROOF_OF_RIGHTS_IN_YOUR_COUNTR],style=MaterialTheme.typography.caption) }
        } else if (preferences.loaded && settings != null) item(key="discovery-opt-in") {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(strings[UiText.UI_EXPLORE_BEYOND_YOUR_LIBRARY],style=MaterialTheme.typography.subtitle1)
                Text(strings[UiText.UI_ONLINE_CATALOGS_ARE_OPTIONAL_YOUR_LOCAL_HOME_WORKS_OFFLINE],style=MaterialTheme.typography.body2)
                TextButton({settings.changeDiscovery(preferences.discovery.copy(homeEnabled=true))},Modifier.heightIn(min=48.dp)) {Text(strings[UiText.UI_EXPLORE_ONLINE_CATALOGS])}
            }
        }
        if (library.entries.isEmpty() && history.entries.isEmpty() && !library.loading && !history.loading && !library.failed && !history.failed) item(key="empty") {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(strings[UiText.UI_YOUR_NEXT_READ_STARTS_HERE],style=MaterialTheme.typography.h6)
                Text(strings[UiText.UI_IMPORT_A_LOCAL_EPUB_COMIC_PDF_OR_TEXT_FILE_YOUR_BOOKS_AND_READING_POSITIONS_STAY])
                if (importAction!=null) Button(importAction,Modifier.heightIn(min=48.dp)) { Text(strings[UiText.IMPORT_FILE]) }
            }
        }
        if ((library.loading || history.loading) && added.isEmpty() && continued.isEmpty()) item(key="loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        // History without a saved locator is not fabricated as a Continue tile.
        if (history.entries.isNotEmpty() && continued.isEmpty()) item(key="history-link") {
            TextButton({application.navigate(Destination.HISTORY)},Modifier.heightIn(min=48.dp)) { Text(strings[UiText.VIEW_HISTORY]) }
        }
    }
    onlineDetail?.let { DiscoveryDetails(it,application) { onlineDetail=null } }
    detail?.let { publication ->
        val record=detailsProgress(application,publication.id,publicationProgress(progress,publication.id))
        val action=membership.forPublication(publication.id)
        PublicationDetails(publication.displayPublication(),application.sourceName(publication.id.sourceId),
            formats=record?.let {listOf(it.id.format)}?:format?.let {listOf(it)}?:emptyList(),progress=record,close={detail=null},
            descriptions=application.descriptions, cover={DetailsCover(publication.displayPublication(),application.covers)}) {
            Button({detail=null;application.openSaved(publication)},Modifier.heightIn(min=48.dp)) { Text(strings.status(readingAction(record))) }
            OutlinedButton({ if(action.inLibrary==true) confirmRemoval=true else application.collections.toggleCatalogLibrary(publication.displayPublication()) },
                Modifier.heightIn(min=48.dp),enabled=action.enabled) { Text(strings.status(action.label)) }
            error?.let {Text(strings.status(it),color=MaterialTheme.colors.error)}
        }
        if (confirmRemoval) AlertDialog(onDismissRequest={confirmRemoval=false},title={Text(strings[UiText.UI_REMOVE_FROM_LIBRARY_CONFIRM])},
            text={Text(strings[UiText.UI_ONLY_LIBRARY_MEMBERSHIP_IS_REMOVED_FILES_HISTORY_AND_SAVED_READING_POSITIONS_ARE])},
            confirmButton={TextButton({confirmRemoval=false;application.collections.removeLibrary(publication.id);detail=null},Modifier.heightIn(min=48.dp),enabled=action.enabled){Text(strings[UiText.UI_REMOVE])}},
            dismissButton={TextButton({confirmRemoval=false},Modifier.heightIn(min=48.dp)){Text(strings[UiText.CANCEL])}})
    }
}

@Composable
private fun HomeSection(title: String, action: String, open: () -> Unit, content: @Composable () -> Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text(title,style=MaterialTheme.typography.h6,modifier=Modifier.weight(1f).semantics {heading()})
            TextButton(open,Modifier.heightIn(min=48.dp)) {Text(action)}
        }
        content()
    }
}
