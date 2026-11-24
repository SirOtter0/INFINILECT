// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.infinilect.app.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.core.*
import org.infinilect.app.ui.*
import org.infinilect.app.resultKey

private fun PublicationFormat.downloadName()=if(this==PublicationFormat.TEXT) "TXT" else name

internal fun DownloadFailure.label()=when(this) {
    DownloadFailure.NOT_ALLOWED -> UiText.DL_NOT_ALLOWED
    DownloadFailure.CONNECTION -> UiText.DL_CONNECTION
    DownloadFailure.INCOMPLETE -> UiText.DL_INCOMPLETE
    DownloadFailure.INVALID -> UiText.DL_INVALID
    DownloadFailure.LIMIT -> UiText.DL_LIMIT
    DownloadFailure.SPACE -> UiText.DL_SPACE
    DownloadFailure.STORAGE -> UiText.DL_STORAGE
    DownloadFailure.MISSING -> UiText.DL_LOCAL_UNAVAILABLE
    DownloadFailure.INTERRUPTED -> UiText.DL_INTERRUPTED
    DownloadFailure.BUSY -> UiText.DL_BUSY
    DownloadFailure.HTTP -> UiText.DL_HTTP_ERROR
    DownloadFailure.RIGHTS_UNVERIFIED -> UiText.DL_RIGHTS_UNVERIFIED
    DownloadFailure.UNSUPPORTED_FORMAT -> UiText.DL_UNSUPPORTED_FORMAT
}
internal fun DownloadStatus.label()=when(this) {
    DownloadStatus.NOT_DOWNLOADED -> UiText.DL_NOT_DOWNLOADED
    DownloadStatus.PENDING -> UiText.DL_PENDING
    DownloadStatus.DOWNLOADING -> UiText.DL_DOWNLOADING
    DownloadStatus.DOWNLOADED -> UiText.DL_AVAILABLE
    DownloadStatus.ERROR -> UiText.DL_ERROR
    DownloadStatus.CANCELLED -> UiText.DL_CANCELLED
}

/** Collect only this entry, not every other transfer's progress/catalogue. */
@Composable
internal fun DownloadAction(publication: Publication,manager: OfflineDownloads) {
    val s=LocalAppStrings.current
    val id=publication.id
    val row by remember(manager,id) {manager.catalogue.map {it.entries.firstOrNull {r->r.id==id}}.distinctUntilChanged()}.collectAsState(null)
    val busy by remember(manager,id) {manager.catalogue.map {!it.loaded || id in it.busy}.distinctUntilChanged()}.collectAsState(true)
    val capability=manager.backend.capability(id)
    val scope=rememberCoroutineScope()
    var looking by remember(id) {mutableStateOf(false)}
    var offers by remember(id) {mutableStateOf<List<DownloadOffer>?>(null)}
    var chosen by remember(id) {mutableIntStateOf(0)}
    var failure by remember(id) {mutableStateOf<DownloadFailure?>(null)}
    var remove by remember(id) {mutableStateOf(false)}
    val status=row?.status ?: DownloadStatus.NOT_DOWNLOADED
    val active=status in setOf(DownloadStatus.PENDING,DownloadStatus.DOWNLOADING)
    val label=s[when {
        capability==DownloadCapability.IMPORTED -> UiText.DL_IMPORTED
        capability==DownloadCapability.UNSUPPORTED -> UiText.DL_NOT_ALLOWED
        active -> UiText.DL_CANCEL
        status==DownloadStatus.DOWNLOADED -> UiText.DL_AVAILABLE
        status in setOf(DownloadStatus.ERROR,DownloadStatus.CANCELLED) -> UiText.DL_RETRY
        else -> UiText.DL_DOWNLOAD
    }]
    LibraryIconButton(label,if(status==DownloadStatus.DOWNLOADED) LibraryGlyph.CHECK else if(status==DownloadStatus.ERROR) LibraryGlyph.ERROR else LibraryGlyph.DOWNLOAD,
        modifier=Modifier.semantics {row?.failure?.let {stateDescription=s[it.label()]}},
        enabled=!busy && !looking && capability==DownloadCapability.AUTHORIZED_SOURCE &&
            (active || status==DownloadStatus.DOWNLOADED || LocalCatalogActions.current.mayDownload),active=status==DownloadStatus.DOWNLOADED,
        icon=if(active) ({DownloadActionProgress(manager,id)}) else if(looking) ({CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)}) else null) {
        when {
            active -> manager.cancel(id)
            status==DownloadStatus.DOWNLOADED -> remove=true
            else -> scope.launch {
                looking=true;failure=null
                try {val values=manager.offers(id);if(values.isEmpty()) failure=DownloadFailure.NOT_ALLOWED else {chosen=0;offers=values}}
                catch(e: CancellationException) {throw e} catch(e: DownloadException) {failure=e.failure} catch(_: Exception) {failure=DownloadFailure.CONNECTION}
                finally {looking=false}
            }
        }
    }
    offers?.let {values -> LibraryPanel(s[UiText.DL_DOWNLOAD],{offers=null},actions={
        TextButton({val selected=values[chosen];offers=null;manager.enqueue(selected)},Modifier.heightIn(min=48.dp)) {Text(s[UiText.DL_DOWNLOAD])}
    }) {
        Text(publication.title,style=MaterialTheme.typography.subtitle1)
        Text(s[UiText.DL_CONSENT],style=MaterialTheme.typography.body2)
        values.forEachIndexed {i,offer ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(i==chosen,{chosen=i},Modifier.semantics {contentDescription=offer.resource.format.downloadName()})
                Text(offer.resource.format.downloadName()+ (offer.size?.let {" · "+s.format(UiText.DL_SIZE,s.number(it/1_048_576.0,1))} ?: ""),Modifier.weight(1f))
            }
        }
        values[chosen].publication.rights?.let {Text(it,style=MaterialTheme.typography.caption)}
    }}
    if(remove) RemoveDownloadConfirmation({remove=false}) {remove=false;manager.remove(id)}
    failure?.let {reason -> LibraryPanel(s[UiText.DL_ERROR],{failure=null}) {Text(s[reason.label()])}}
}
/** Lives below the compact action row, so a transfer failure stays readable without
 * changing the row's layout or reopening Downloads. No collector of chunk progress. */
@Composable
internal fun DownloadFailureMessage(id: PublicationId,manager: OfflineDownloads) {
    val s=LocalAppStrings.current
    val failure by remember(manager,id) {manager.catalogue.map {catalogue ->
        val row=catalogue.entries.firstOrNull {it.id==id}
        row?.failure?.takeIf {row.status==DownloadStatus.ERROR} ?: catalogue.failure
    }.distinctUntilChanged()}.collectAsState(null)
    failure?.let {Text(s[it.label()],style=MaterialTheme.typography.body2,color=MaterialTheme.colors.error,
        modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
}
@Composable
private fun RemoveDownloadConfirmation(close: ()->Unit,confirm: ()->Unit) {
    val s=LocalAppStrings.current
    AlertDialog(close,title={Text(s[UiText.DL_DELETE])},text={Text(s[UiText.DL_DELETE_NOTICE])},confirmButton={TextButton(confirm,Modifier.heightIn(min=48.dp)) {Text(s[UiText.DL_DELETE])}},dismissButton={TextButton(close,Modifier.heightIn(min=48.dp)) {Text(s[UiText.CANCEL])}})
}
@Composable
internal fun DownloadSettings(manager: OfflineDownloads,open: (Publication)->Unit) {
    val s=LocalAppStrings.current;var show by remember {mutableStateOf(false)}
    OutlinedButton({show=true},Modifier.fillMaxWidth().heightIn(min=56.dp)) {LibraryIcon(LibraryGlyph.DOWNLOAD);Spacer(Modifier.width(12.dp));Text(s[UiText.DL_TITLE])}
    if(show) DownloadPanel(manager,open) {show=false}
}
@Composable
internal fun DownloadPanel(manager: OfflineDownloads,open: (Publication)->Unit,close: ()->Unit) {
    val s=LocalAppStrings.current;val catalogue by manager.catalogue.collectAsState()
    var remove by remember {mutableStateOf<PublicationId?>(null)}
    Dialog(close,DialogProperties(usePlatformDefaultWidth=false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),contentAlignment=Alignment.Center) {
            Surface(Modifier.widthIn(max=620.dp).fillMaxWidth().heightIn(max=maxHeight),shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp)) {
                    Text(s[UiText.DL_TITLE],style=MaterialTheme.typography.h6,modifier=Modifier.semantics {heading()})
                    LazyColumn(Modifier.weight(1f,false),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item {
                            Text(s[UiText.DL_LIFETIME],style=MaterialTheme.typography.caption)
                            Text(s.format(UiText.DL_USED,s.number(catalogue.usedBytes/1_048_576.0,1),s.number(catalogue.quotaBytes/1_048_576.0,0)))
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf(128L,512L,1024L,2048L).forEach {mb -> LibraryChip(s.format(UiText.DL_SIZE,mb),catalogue.quotaBytes==mb*1024*1024,catalogue.loaded) {manager.setQuota(mb*1024*1024)} }
                            }
                            if(!catalogue.loaded && catalogue.failure==null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            catalogue.failure?.let {Text(s[it.label()],color=MaterialTheme.colors.error);TextButton(manager::reload,Modifier.heightIn(min=48.dp)) {Text(s[UiText.RETRY])}}
                            if(catalogue.loaded && catalogue.entries.isEmpty()) Text(s[UiText.DL_EMPTY])
                        }
                        items(catalogue.entries,key={it.id.resultKey()}) {row ->
                            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(row.publication.title,style=MaterialTheme.typography.subtitle1)
                                Text(row.format.downloadName()+" · "+s[row.status.label()],style=MaterialTheme.typography.caption)
                                if(row.status==DownloadStatus.DOWNLOADING) DownloadRowProgress(manager,row.id)
                                else row.size?.let {Text(s.format(UiText.DL_SIZE,s.number(it/1_048_576.0,1)),style=MaterialTheme.typography.caption)}
                                row.failure?.let {Text(s[it.label()],style=MaterialTheme.typography.caption,color=MaterialTheme.colors.error)}
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    when(row.status) {
                                        DownloadStatus.PENDING,DownloadStatus.DOWNLOADING -> TextButton({manager.cancel(row.id)},Modifier.heightIn(min=48.dp)) {Text(s[UiText.DL_CANCEL])}
                                        DownloadStatus.ERROR,DownloadStatus.CANCELLED -> TextButton({manager.retry(row.id)},Modifier.heightIn(min=48.dp),enabled=row.id !in catalogue.busy) {Text(s[UiText.DL_RETRY])}
                                        DownloadStatus.DOWNLOADED -> TextButton({close();open(row.publication)},Modifier.heightIn(min=48.dp)) {Text(s[UiText.DL_READ])}
                                        else -> Unit
                                    }
                                    if(row.status !in setOf(DownloadStatus.PENDING,DownloadStatus.DOWNLOADING)) TextButton({remove=row.id},Modifier.heightIn(min=48.dp),enabled=row.id !in catalogue.busy) {Text(s[UiText.DL_DELETE])}
                                };Divider()
                            }
                        }
                    }
                    TextButton(close,Modifier.align(Alignment.End).heightIn(min=48.dp)) {Text(s[UiText.CLOSE])}
                }
            }
        }
    }
    remove?.let {id -> RemoveDownloadConfirmation({remove=null}) {remove=null;manager.remove(id)}}
}
@Composable
private fun DownloadRowProgress(manager: OfflineDownloads,id: PublicationId) {
    val s=LocalAppStrings.current;val progress by remember(manager,id) {manager.progress(id)}.collectAsState()
    if(progress.total!=null) LinearProgressIndicator((progress.bytes.toFloat()/progress.total!!).coerceIn(0f,1f),Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(if(progress.total==null) s.format(UiText.DL_SIZE,s.number(progress.bytes/1_048_576.0,1)) else s.format(UiText.DL_PROGRESS,s.number(progress.bytes/1_048_576.0,1),s.number(progress.total!!/1_048_576.0,1)),style=MaterialTheme.typography.caption)
}

@Composable
private fun DownloadActionProgress(manager: OfflineDownloads,id: PublicationId) {
    val p by remember(manager,id) {manager.progress(id)}.collectAsState()
    if(p.total!=null) CircularProgressIndicator((p.bytes.toFloat()/p.total!!).coerceIn(0f,1f),Modifier.size(22.dp),strokeWidth=2.dp)
    else CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
}
