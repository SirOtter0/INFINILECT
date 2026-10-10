// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.infinilect.app.*
import org.infinilect.core.*
import org.infinilect.app.discovery.*

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
    val library by application.collections.library.collectAsState()
    val history by application.collections.history.collectAsState()
    val continued = remember(history.entries, progress) { homeContinue(history.entries,progress) }
    val added = remember(library.entries) { homeRecentLibrary(library.entries) }
    val online by application.homeDiscovery.state.collectAsState()
    val search by application.discovery.state.collectAsState()
    var onlineDetail by remember { mutableStateOf<DiscoveryEntry?>(null) }
    LaunchedEffect(preferences.loaded, preferences.discovery) {
        if (preferences.loaded) application.homeDiscovery.refresh(preferences.discovery)
    }
    DisposableEffect(application) { onDispose { application.homeDiscovery.pause() } }
    val known = remember(online.rows, search.entries) { (online.rows.flatMap { it.entries } + search.entries).associateBy { it.publication.id } }
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
    LazyColumn(Modifier.fillMaxSize().semantics { paneTitle="Home" }, state=position, contentPadding=PaddingValues(horizontal=16.dp, vertical=8.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item(key="greeting") {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(welcomeGreeting(preferences.profile),style=MaterialTheme.typography.h5,modifier=Modifier.semantics { heading() })
                Text("What would you like to read today?",style=MaterialTheme.typography.body1,color=MaterialTheme.colors.onBackground.copy(alpha=.75f))
            }
        }
        if (preferences.loaded && !preferences.profile.setupHandled && settings!=null) item(key="profile-setup") {
            Surface(shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Make this space yours",style=MaterialTheme.typography.subtitle1)
                    Text("A name is optional. Choose a residence country for future discovery; no online account is created.")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(editProfile,Modifier.heightIn(min=48.dp)) { Text("Set up profile") }
                        TextButton({settings.changeProfile(preferences.profile.copy(setupHandled=true))},Modifier.heightIn(min=48.dp)) { Text("Set up later") }
                    }
                }
            }
        }
        if (settingsFailed) item(key="settings-error") {
            FeedbackCard("Settings could not be saved","Your profile applies for now. Retry to keep it after restarting.",error=true,
                action="Retry saving settings",onAction={settings?.retry()})
        }
        error?.let { message -> item(key="collection-error") { FeedbackCard("Change could not be saved",message,error=true) } }
        if (library.failed || history.failed) item(key="storage-error") {
            FeedbackCard("Local collection unavailable","Your publications and positions have not been removed.",error=true,
                action="Try again",onAction={application.collections.refreshLibrary();application.collections.refreshHistory()})
        }
        if (continued.isNotEmpty()) item(key="continue") {
            HomeSection("Continue reading","View History",{application.navigate(Destination.HISTORY)}) {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(continued,key={it.id.resultKey()},contentType={"publication"}) { publication ->
                        HomeCover(publication.displayPublication(),application.covers,publicationProgress(progress,publication.id),resume=true) { application.openSaved(publication) }
                    }
                }
            }
        }
        if (preferences.discovery.homeEnabled && recommendations.isNotEmpty()) item(key="discovery-recommendations") {
            HomeSection(if (personalized) DiscoveryStrings.RECOMMENDED else DiscoveryStrings.GENERAL,DiscoveryStrings.VIEW_SEARCH,{application.navigate(Destination.SEARCH)}) {
                Text(if (personalized) DiscoveryStrings.PERSONALIZED_EXPLANATION else DiscoveryStrings.GENERAL_EXPLANATION,style=MaterialTheme.typography.caption)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(recommendations,key={it.publication.id.resultKey()},contentType={"publication"}) { entry -> DiscoveryTile(entry,application,Modifier.width(144.dp)) { onlineDetail=entry } }
                }
            }
        }
        if (added.isNotEmpty()) item(key="added") {
            HomeSection("Recently added","View Library",{application.navigate(Destination.LIBRARY)}) {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(added,key={it.id.resultKey()},contentType={"publication"}) { publication ->
                        HomeCover(publication.displayPublication(),application.covers,publicationProgress(progress,publication.id),resume=false) { detail=publication;format=it }
                    }
                }
            }
        }
        if (preferences.discovery.homeEnabled) {
            online.rows.filter { it.genre != null }.forEach { row -> item(key="discovery-${row.key}") {
                HomeSection(row.title,DiscoveryStrings.VIEW_ALL,{application.discovery.browse(row.genre!!);application.navigate(Destination.SEARCH)}) {
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        items(row.entries,key={it.publication.id.resultKey()},contentType={"publication"}) { entry -> DiscoveryTile(entry,application,Modifier.width(144.dp)) {onlineDetail=entry} }
                    }
                }
            } }
            if (online.loading) item(key="discovery-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (online.failed) item(key="discovery-error") { FeedbackCard(DiscoveryStrings.HOME_UNAVAILABLE,DiscoveryStrings.HOME_RETRY_EXPLANATION,
                action=DiscoveryStrings.RETRY_DISCOVERY,onAction={application.homeDiscovery.refresh(preferences.discovery,true)}) }
            item(key="discovery-notice") { Text(DiscoveryStrings.RIGHTS,style=MaterialTheme.typography.caption) }
        } else if (preferences.loaded && settings != null) item(key="discovery-opt-in") {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(DiscoveryStrings.OPT_IN_TITLE,style=MaterialTheme.typography.subtitle1)
                Text(DiscoveryStrings.OPT_IN_EXPLANATION,style=MaterialTheme.typography.body2)
                TextButton({settings.changeDiscovery(preferences.discovery.copy(homeEnabled=true))},Modifier.heightIn(min=48.dp)) {Text(DiscoveryStrings.OPT_IN_ACTION)}
            }
        }
        if (library.entries.isEmpty() && history.entries.isEmpty() && !library.loading && !history.loading && !library.failed && !history.failed) item(key="empty") {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Your next read starts here",style=MaterialTheme.typography.h6)
                Text("Import a local EPUB, comic, PDF or text file. Your books and reading positions stay on this device.")
                if (importAction!=null) Button(importAction,Modifier.heightIn(min=48.dp)) { Text("Import local file") }
            }
        }
        if ((library.loading || history.loading) && added.isEmpty() && continued.isEmpty()) item(key="loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        // History without a saved locator is not fabricated as a Continue tile.
        if (history.entries.isNotEmpty() && continued.isEmpty()) item(key="history-link") {
            TextButton({application.navigate(Destination.HISTORY)},Modifier.heightIn(min=48.dp)) { Text("View History") }
        }
    }
    onlineDetail?.let { DiscoveryDetails(it,application) { onlineDetail=null } }
    detail?.let { publication ->
        val record=detailsProgress(application,publication.id,publicationProgress(progress,publication.id))
        val action=membership.forPublication(publication.id)
        PublicationDetails(publication.displayPublication(),application.sourceName(publication.id.sourceId),
            formats=record?.let {listOf(it.id.format)}?:format?.let {listOf(it)}?:emptyList(),progress=record,close={detail=null},
            descriptions=application.descriptions, cover={DetailsCover(publication.displayPublication(),application.covers)}) {
            Button({detail=null;application.openSaved(publication)},Modifier.heightIn(min=48.dp)) { Text(readingAction(record)) }
            OutlinedButton({ if(action.inLibrary==true) confirmRemoval=true else application.collections.toggleCatalogLibrary(publication.displayPublication()) },
                Modifier.heightIn(min=48.dp),enabled=action.enabled) { Text(action.label) }
            error?.let {Text(it,color=MaterialTheme.colors.error)}
        }
        if (confirmRemoval) AlertDialog(onDismissRequest={confirmRemoval=false},title={Text("Remove from Library?")},
            text={Text("Only Library membership is removed. Files, History and saved reading positions are kept.")},
            confirmButton={TextButton({confirmRemoval=false;application.collections.removeLibrary(publication.id);detail=null},Modifier.heightIn(min=48.dp),enabled=action.enabled){Text("Remove")}},
            dismissButton={TextButton({confirmRemoval=false},Modifier.heightIn(min=48.dp)){Text("Cancel")}})
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
