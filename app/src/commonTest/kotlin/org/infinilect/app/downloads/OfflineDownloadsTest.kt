// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.downloads

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDownloadsTest {
    private class Backend: DownloadBackend {
        val rows=linkedMapOf<PublicationId,DownloadEntry>();var revision=0L;var starts=0;var active=0;var peak=0;var authorizations=0;var closed=false
        var pendingGate: CompletableDeferred<Unit>?=null
        var gate=CompletableDeferred<Unit>();var nonCooperative=false;var failure: DownloadFailure?=null;var writeFailure=false
        var completing: suspend ()->Unit={}
        fun offer(key: String="a"): DownloadOffer {
            val id=PublicationId(SourceId("fixture"),key);val r=PublicationResource(id,"original",PublicationFormat.TEXT,"text/plain")
            return DownloadOffer(Publication(id,key,PublicationType.BOOK,resources=listOf(r)),r,100)
        }
        fun save(row: DownloadEntry)=row.copy(revision=++revision).also {rows[row.id]=it}
        override fun capability(id: PublicationId)=DownloadCapability.AUTHORIZED_SOURCE
        override suspend fun load()=snapshot()
        override suspend fun snapshot()=DownloadSnapshot(rows.values.toList(),revision=revision)
        override suspend fun offers(id: PublicationId): List<DownloadOffer> {authorizations++;return listOf(offer(id.localId))}
        override suspend fun pending(offer: DownloadOffer): DownloadEntry {pendingGate?.await();if(writeFailure) throw DownloadException(DownloadFailure.STORAGE);return save(DownloadEntry(offer.publication,offer.resource.format,DownloadStatus.PENDING,offer.resource))}
        override suspend fun download(entry: DownloadEntry,started: suspend (DownloadEntry)->Unit,progress: suspend (DownloadProgress)->Unit): DownloadEntry {
            starts++;active++;peak=maxOf(peak,active)
            try {
                started(save(entry.copy(status=DownloadStatus.DOWNLOADING)))
                repeat(100) {progress(DownloadProgress(it.toLong(),100))}
                if(nonCooperative) withContext(NonCancellable) {gate.await()} else gate.await()
                currentCoroutineContext().ensureActive();failure?.let {throw DownloadException(it)}
                val result=save(rows.getValue(entry.id).copy(status=DownloadStatus.DOWNLOADED));completing();return result
            } finally {active--}
        }
        override suspend fun settle(id: PublicationId,status: DownloadStatus,failure: DownloadFailure?): DownloadEntry? = rows[id]?.let {if(it.status==DownloadStatus.DOWNLOADED) it else save(it.copy(status=status,failure=failure))}
        override suspend fun remove(id: PublicationId) {rows.remove(id);revision++}
        override suspend fun acquire(id: PublicationId): OfflineReadingCopy?=null
        override suspend fun quota(bytes: Long)=Unit
        override suspend fun close() {closed=true}
    }
    private fun OfflineDownloads.status(key: String="a")=catalogue.value.entries.firstOrNull {it.id.localId==key}?.status
    @Test fun duplicatesAreRejectedAndOnlyOneTransferRunsWithEightAcceptedIntents()=runTest {
        val backend=Backend();val owner=OfflineDownloads(backend,StandardTestDispatcher(testScheduler));runCurrent()
        repeat(10) {owner.enqueue(backend.offer("a"))};runCurrent();assertEquals(1,backend.starts)
        repeat(20) {owner.enqueue(backend.offer("b$it"))};runCurrent()
        assertEquals(8,backend.rows.size);assertEquals(1,backend.peak);assertEquals(DownloadFailure.LIMIT,owner.catalogue.value.failure)
        backend.gate.complete(Unit);runCurrent();assertEquals(8,backend.starts);assertEquals(1,backend.peak)
        assertTrue(owner.catalogue.value.entries.all {it.status==DownloadStatus.DOWNLOADED})
        owner.close();owner.awaitClosed();assertTrue(backend.closed)
    }
    @Test fun progressDoesNotRebuildCatalogueAndNavigationDoesNotOwnTransfer()=runTest {
        val backend=Backend();val owner=OfflineDownloads(backend,StandardTestDispatcher(testScheduler));runCurrent()
        var changes=0;val observer=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {owner.catalogue.collect {changes++}}
        owner.enqueue(backend.offer());runCurrent();assertEquals(99L,owner.progress(backend.offer().publication.id).value.bytes)
        assertTrue(changes<10,"Chunk progress must use the per-publication flow")
        // A UI collector may leave and return without cancelling the application-owned transfer.
        observer.cancel();assertEquals(DownloadStatus.DOWNLOADING,owner.status());backend.gate.complete(Unit);runCurrent()
        assertEquals(DownloadStatus.DOWNLOADED,owner.status());assertEquals(setOf(backend.offer().publication.id),owner.available.value)
        owner.close();owner.awaitClosed()
    }
    @Test fun backgroundCancelsPendingAndActiveWithoutAutomaticResumeAndRetryReauthorizes()=runTest {
        val backend=Backend();val owner=OfflineDownloads(backend,StandardTestDispatcher(testScheduler));runCurrent()
        owner.enqueue(backend.offer());owner.enqueue(backend.offer("b"));runCurrent();owner.pause();runCurrent()
        assertTrue(owner.catalogue.value.entries.all {it.status==DownloadStatus.CANCELLED});assertEquals(1,backend.starts)
        owner.resume();runCurrent();assertEquals(1,backend.starts)
        backend.gate.complete(Unit);owner.retry(backend.offer().publication.id);runCurrent()
        assertEquals(1,backend.authorizations);assertEquals(DownloadStatus.DOWNLOADED,owner.status())
        owner.close();owner.awaitClosed()
    }
    @Test fun nonCooperativeCancelledRequestCannotPublishReadyAndCloseWaitsForRelease()=runTest {
        val backend=Backend().also {it.nonCooperative=true};val owner=OfflineDownloads(backend,StandardTestDispatcher(testScheduler));runCurrent()
        owner.enqueue(backend.offer());runCurrent();owner.cancel(backend.offer().publication.id);runCurrent()
        assertEquals(DownloadStatus.DOWNLOADING,owner.status());owner.close();runCurrent();assertFalse(backend.closed)
        backend.gate.complete(Unit);owner.awaitClosed();assertTrue(backend.closed);assertEquals(DownloadStatus.CANCELLED,backend.rows.values.single().status)
        assertTrue(owner.available.value.isEmpty())
    }
    @Test fun durableCompletionWinsLateCancelAndPersistenceErrorsDoNotInventMembership()=runTest {
        val backend=Backend();val owner=OfflineDownloads(backend,StandardTestDispatcher(testScheduler));runCurrent()
        backend.writeFailure=true;owner.enqueue(backend.offer());runCurrent();assertTrue(backend.rows.isEmpty());assertEquals(DownloadFailure.STORAGE,owner.catalogue.value.failure)
        backend.writeFailure=false;val saved=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        backend.completing={saved.complete(Unit);withContext(NonCancellable) {release.await()}}
        owner.enqueue(backend.offer());runCurrent();backend.gate.complete(Unit);runCurrent();saved.await()
        owner.cancel(backend.offer().publication.id);release.complete(Unit);runCurrent()
        assertEquals(DownloadStatus.DOWNLOADED,owner.status());owner.close();owner.awaitClosed()
    }
    @Test fun recoverableProviderFailureIsIsolatedAndExplicitRetryCanSucceed()=runTest {
        val backend=Backend().also {it.failure=DownloadFailure.CONNECTION;it.gate.complete(Unit)}
        val owner=OfflineDownloads(backend,StandardTestDispatcher(testScheduler));runCurrent();owner.enqueue(backend.offer());runCurrent()
        assertEquals(DownloadStatus.ERROR,owner.status());assertTrue(owner.available.value.isEmpty())
        backend.failure=null;owner.retry(backend.offer().publication.id);runCurrent();assertEquals(DownloadStatus.DOWNLOADED,owner.status())
        owner.remove(backend.offer().publication.id);runCurrent();assertNull(owner.status());assertTrue(owner.available.value.isEmpty())
        owner.close();owner.awaitClosed()
    }

    @Test fun backgroundDuringPendingRecordWritePublishesCancelledInsteadOfHidingTheRecord()=runTest {
        val backend=Backend().also {it.pendingGate=CompletableDeferred()};val owner=OfflineDownloads(backend,StandardTestDispatcher(testScheduler));runCurrent()
        owner.enqueue(backend.offer());runCurrent();owner.pause();backend.pendingGate!!.complete(Unit);runCurrent()
        assertEquals(DownloadStatus.CANCELLED,owner.status());assertEquals(0,backend.starts);assertTrue(owner.catalogue.value.busy.isEmpty())
        owner.resume();runCurrent();assertEquals(0,backend.starts);owner.close();owner.awaitClosed()
    }
}
