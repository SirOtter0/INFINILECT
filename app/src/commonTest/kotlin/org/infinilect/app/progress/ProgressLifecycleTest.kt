// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.*
import org.infinilect.app.reader.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressLifecycleTest {
    private val id=ReadingProgressId(PublicationId(SourceId("fixture"),"1"),"text",PublicationFormat.TEXT)
    private class Store : ReadingProgressStore {
        val values=mutableMapOf<ReadingProgressId,ReadingProgress>()
        val saved=mutableListOf<ReadingProgress>()
        var saveAction: suspend () -> Boolean = { true }
        var getAction: suspend () -> Unit = {}
        override suspend fun get(id: ReadingProgressId): ReadingProgress? { getAction(); return values[id] }
        override suspend fun save(progress: ReadingProgress): Boolean {
            if (!saveAction()) return false
            saved+=progress; values[progress.id]=progress; return true
        }
        override suspend fun remove(id: ReadingProgressId): Boolean { values.remove(id); return true }
    }
    private class Source(val idValue: PublicationId) : PublicationSource {
        override val id=idValue.sourceId
        val resource=PublicationResource(idValue,"text",PublicationFormat.TEXT,"text/plain")
        val publication=Publication(idValue,"Title",PublicationType.DOCUMENT,resources=listOf(resource))
        var metadataRequests=0; var acquisitions=0; var handlesClosed=0
        override suspend fun search(query: String,pageToken: String?)=SearchPage(listOf(publication))
        override suspend fun getPublication(publicationId: PublicationId): Publication {
            assertEquals(idValue,publicationId); metadataRequests++; return publication
        }
        override suspend fun loadResource(resource: PublicationResource): ResourceContent {
            assertEquals(this.resource,resource); acquisitions++
            return object : ResourceContent {
                val bytes="abcdefghij".encodeToByteArray(); var cursor=0
                override val sizeBytes=bytes.size.toLong()
                override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                    if(cursor==bytes.size) return -1
                    val n=minOf(length,bytes.size-cursor); bytes.copyInto(buffer,offset,cursor,cursor+n); cursor+=n; return n
                }
                override fun close() { handlesClosed++ }
            }
        }
    }
    private fun TestScope.persistence(store: Store)=ProgressPersistence(store,StandardTestDispatcher(testScheduler), { testScheduler.currentTime + 100 })
    private fun document(key: ReadingProgressId=id)=TextDocument(key.publicationId,"Title","abcdefghij",key)
    private fun TestScope.reading(persistence: ProgressPersistence,key: ReadingProgressId=id,restored: ReadingProgress?=null)=
        TextReadingProgress(document(key),restored,persistence,this)

    @Test fun fixedWindowSavesDuringContinuousMovementWithoutFinalClose() = runTest {
        val store=Store(); val p=persistence(store); val reading=reading(p)
        reading.report(2); advanceTimeBy(1000); reading.report(4)
        assertTrue(store.saved.isEmpty()); advanceTimeBy(1000); runCurrent()
        assertEquals(4L,(store.saved.single().locator as ReadingLocator.Text).codePointOffset)
        reading.report(5); advanceTimeBy(2000); runCurrent(); assertEquals(2,store.saved.size)
        reading.close(); p.close(); p.awaitClosed()
    }
    @Test fun leaveFlushesLatestMeaningfulPositionAndCloseIsIdempotent() = runTest {
        val store=Store(); val p=persistence(store); val reading=reading(p)
        reading.report(7); reading.close(); reading.close(); reading.report(9)
        p.close(); p.close(); p.awaitClosed()
        assertEquals(1,store.saved.size); assertEquals(0.7,store.saved.single().progression)
    }
    @Test fun restorationAndUnchangedPositionDoNotCauseWriteAmplification() = runTest {
        val store=Store(); val p=persistence(store)
        val stored=ReadingProgress(id,ReadingLocator.Text(4,10),0.4,1)
        val reading=reading(p,restored=stored)
        assertEquals(4,reading.codePointOffset.value)
        reading.report(4); reading.close(); p.close(); p.awaitClosed()
        assertTrue(store.saved.isEmpty())
    }
    @Test fun oldReaderCallbackCannotWriteUnderNewPublication() = runTest {
        val store=Store(); val p=persistence(store)
        val first=reading(p); first.report(3); first.close()
        val secondId=id.copy(publicationId=id.publicationId.copy(localId="2"))
        val second=reading(p,secondId); second.report(8); first.report(9); second.close()
        p.close(); p.awaitClosed()
        assertEquals(0.3,store.values[id]?.progression); assertEquals(0.8,store.values[secondId]?.progression)
    }
    @Test fun sourceNamespacesCannotMixProgress() = runTest {
        val store=Store(); val p=persistence(store)
        val other=id.copy(publicationId=id.publicationId.copy(sourceId=SourceId("other")))
        val first=reading(p); first.report(2); first.close()
        val second=reading(p,other); second.report(6); second.close(); p.close(); p.awaitClosed()
        assertEquals(0.2,store.values[id]?.progression); assertEquals(0.6,store.values[other]?.progression)
    }
    @Test fun immediateReopenUsesPendingPositionBeforeDiskSaveCompletes() = runTest {
        val store=Store(); val p=persistence(store); val reading=reading(p)
        reading.report(5); reading.close()
        assertEquals(0.5,p.get(id)?.progression)
        p.close(); p.awaitClosed()
    }
    @Test fun foreignStoredIdentityIsRejected() = runTest {
        val store=Store(); store.values[id]=ReadingProgress(id.copy(resourceKey="foreign"),ReadingLocator.Text(8,10),0.8,1)
        val p=persistence(store)
        assertNull(p.get(id)); p.close(); p.awaitClosed()
    }
    @Test fun failedSaveWarnsWithoutPreventingReadingAndLaterSaveCanRecover() = runTest {
        val store=Store(); store.saveAction={ false }; val p=persistence(store); val reading=reading(p)
        reading.report(2); reading.flush(); runCurrent(); assertTrue(p.saveFailed.value)
        store.saveAction={ true }; reading.report(3); reading.close(); p.close(); p.awaitClosed()
        assertFalse(p.saveFailed.value); assertEquals(0.3,store.values[id]?.progression)
    }
    @Test fun saveTimeoutDoesNotLeakWorkerAndCloseDrainsSafely() = runTest {
        val store=Store(); store.saveAction={ awaitCancellation() }; val p=persistence(store)
        val reading=reading(p); reading.report(4); reading.close(); p.close(); advanceUntilIdle(); p.awaitClosed()
        assertTrue(p.saveFailed.value); assertTrue(store.saved.isEmpty())
    }
    @Test fun pendingRecordsAreBoundedAndDifferentPublicationsRemainIndependent() = runTest {
        val store=Store(); val p=persistence(store)
        for (i in 1..33) p.submit(ReadingProgress(id.copy(resourceKey="$i"),ReadingLocator.Text(1,2),0.5,i.toLong()))
        assertTrue(p.saveFailed.value); p.close(); p.awaitClosed(); assertEquals(32,store.saved.size)
    }
    @Test fun sessionBackSavesAndRecreatedSessionRestoresAfterFreshAcquisition() = runTest {
        val store=Store(); val p=persistence(store); val source=Source(id.publicationId)
        fun session(persist: ProgressPersistence)=ReadingSession(source,this,true,
            decodingDispatcher=StandardTestDispatcher(testScheduler),progress=persist)
        val session=session(p); session.editQuery("query"); session.submitSearch(); advanceUntilIdle()
        val results=session.search.state.value
        session.open(source.publication); advanceUntilIdle()
        val first=assertNotNull(assertIs<OpenPublicationState.Ready>(session.opening.state.value).reading)
        first.report(6); session.back(); runCurrent()
        assertSame(results,session.search.state.value); assertEquals("query",session.query.value)
        session.close(); p.close(); p.awaitClosed()
        val newPersistence=persistence(store); val recreated=session(newPersistence)
        recreated.open(source.publication); advanceUntilIdle()
        val second=assertNotNull(assertIs<OpenPublicationState.Ready>(recreated.opening.state.value).reading)
        assertEquals(6,second.codePointOffset.value)
        assertEquals(2,source.metadataRequests); assertEquals(2,source.acquisitions); assertEquals(2,source.handlesClosed)
        assertNull(source.resource.revision); assertNull(source.resource.cacheKey)
        recreated.close(); newPersistence.close(); newPersistence.awaitClosed()
    }
    @Test fun cancellationDuringProgressLookupCannotPublishStaleReader() = runTest {
        val store=Store(); val entered=CompletableDeferred<Unit>(); val gate=CompletableDeferred<Unit>()
        store.getAction={ entered.complete(Unit); withContext(NonCancellable) { gate.await() } }
        val p=persistence(store); val source=Source(id.publicationId)
        val session=ReadingSession(source,this,true,decodingDispatcher=StandardTestDispatcher(testScheduler),progress=p)
        session.open(source.publication); entered.await(); session.back(); gate.complete(Unit); advanceUntilIdle()
        assertIs<OpenPublicationState.Idle>(session.opening.state.value); assertEquals(1,source.handlesClosed)
        session.close(); p.close(); p.awaitClosed()
    }
    @Test fun applicationCloseCapturesPendingStateBeforeReleasingSources() = runTest {
        val store=Store(); val p=persistence(store); val source=Source(id.publicationId)
        var releases=0
        val owner=ApplicationSources(listOf(SourceOption("Fixture",source,true)),progress=p) { releases++ }
        val session=ReadingSession(source,this,true,decodingDispatcher=StandardTestDispatcher(testScheduler),progress=p)
        owner.attach(session); session.open(source.publication); advanceUntilIdle()
        assertNotNull(assertIs<OpenPublicationState.Ready>(session.opening.state.value).reading).report(8)
        owner.close(); owner.close(); owner.awaitProgressClosed()
        assertEquals(1,releases); assertEquals(0.8,store.values[id]?.progression)
    }
    @Test fun staleSubmissionCannotReplaceNewerInMemoryOrCommittedPosition() = runTest {
        val store=Store(); val p=persistence(store)
        val newest=ReadingProgress(id,ReadingLocator.Text(8,10),0.8,3)
        p.submit(newest); p.submit(ReadingProgress(id,ReadingLocator.Text(2,10),0.2,2))
        assertEquals(newest,p.get(id)); p.close(); p.awaitClosed()
        assertEquals(newest,store.values[id])
    }
    @Test fun failedForeignOpenClosesPreviousReaderAndRejectsLateCallbacks() = runTest {
        val store=Store(); val p=persistence(store); val source=Source(id.publicationId)
        val opener=OpenPublicationController(source,object : ResourceLoader {
            override suspend fun load(resource: PublicationResource)=source.loadResource(resource)
        },this,StandardTestDispatcher(testScheduler),p)
        opener.open(source.publication); advanceUntilIdle()
        val reading=assertNotNull(assertIs<OpenPublicationState.Ready>(opener.state.value).reading)
        reading.report(4)
        opener.open(source.publication.copy(id=PublicationId(SourceId("other"),"1"),resources=emptyList()))
        assertIs<OpenPublicationState.Error>(opener.state.value); reading.report(9)
        opener.close(); p.close(); p.awaitClosed()
        assertEquals(0.4,store.values[id]?.progression)
    }
    @Test fun sourceSwitchClosesOldGenerationWhileNewSessionKeepsItsOwnIdentity() = runTest {
        val store=Store(); val p=persistence(store); val firstSource=Source(id.publicationId)
        val secondId=id.copy(publicationId=PublicationId(SourceId("other"),"2"))
        val secondSource=Source(secondId.publicationId)
        fun session(source: Source)=ReadingSession(source,this,true,
            decodingDispatcher=StandardTestDispatcher(testScheduler),progress=p)
        val first=session(firstSource); first.open(firstSource.publication); advanceUntilIdle()
        val old=assertNotNull(assertIs<OpenPublicationState.Ready>(first.opening.state.value).reading)
        old.report(3); first.close()
        val second=session(secondSource); second.open(secondSource.publication); advanceUntilIdle()
        assertNotNull(assertIs<OpenPublicationState.Ready>(second.opening.state.value).reading).report(7)
        old.report(9); second.close(); p.close(); p.awaitClosed()
        assertEquals(0.3,store.values[id]?.progression)
        assertEquals(0.7,store.values[secondId]?.progression)
    }
}
