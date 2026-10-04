// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.collections.*
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.search.SearchResultsViewport
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationSessionTest {
    private class Source(override val id: SourceId=SourceId("internet-archive")) : PublicationSource {
        val resource=PublicationResource(PublicationId(id,"1"),"text",PublicationFormat.TEXT,"text/plain")
        val publication=Publication(resource.publicationId,"Fresh title",PublicationType.BOOK,listOf("Fresh author"),listOf(resource))
        var metadata=0;var acquisitions=0;var failedDetails=false;var failedBytes=false
        var acquireAction: suspend () -> Unit = {}
        override suspend fun search(query: String,pageToken: String?)=SearchPage(listOf(publication.copy(title="Search title",resources=emptyList())))
        override suspend fun getPublication(publicationId: PublicationId): Publication? {assertEquals(publication.id,publicationId);metadata++;return if(failedDetails)null else publication}
        override suspend fun loadResource(resource: PublicationResource): ResourceContent {
            assertEquals(this.resource,resource);acquisitions++;acquireAction();if(failedBytes) error("private transport details")
            return object : ResourceContent {
                override val sizeBytes=10L;var done=false
                override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                    if(done)return -1;done=true;"0123456789".encodeToByteArray().copyInto(buffer,offset);return 10
                }
                override fun close() {}
            }
        }
    }
    private fun TestScope.owner(source: Source, fake: FakeCollections, progress: ProgressPersistence?=null): ApplicationSources {
        val collections=ApplicationCollections(fake.library,fake.history,StandardTestDispatcher(testScheduler))
        return ApplicationSources(listOf(SourceOption("Archive",source,true)),progress=progress,collections=collections) {}
    }
    private fun TestScope.session(owner: ApplicationSources)=ApplicationSession(owner,this,StandardTestDispatcher(testScheduler)){10L}
    private suspend fun finish(session: ApplicationSession,owner: ApplicationSources) { session.close();owner.close();owner.awaitProgressClosed() }

    @Test fun successfulSearchOpenRecordsFreshMetadataAndBackPreservesSearch()=runTest {
        val source=Source();val fake=FakeCollections();val owner=owner(source,fake);val session=session(owner)
        val search=session.searchSession.value;search.editQuery("query");search.submitSearch();advanceUntilIdle();val results=search.search.state.value
        session.openSearch(source.publication);advanceUntilIdle();assertIs<OpenPublicationState.Ready>(session.opening.value)
        assertEquals("Fresh title",fake.opened[source.publication.id]?.publication?.title)
        session.back();advanceUntilIdle();assertEquals("query",search.query.value);assertSame(results,search.search.state.value)
        finish(session,owner)
    }
    @Test fun catalogLibraryMutationsAndReaderBackRetainTheSearchViewport()=runTest {
        val source=Source();val fake=FakeCollections();val owner=owner(source,fake);val session=session(owner)
        val search=session.searchSession.value
        val viewport=SearchResultsViewport { mutableListOf(0) }
        search.editQuery("query");search.submitSearch();advanceUntilIdle()
        val results=search.search.state.value
        val position=viewport.forState(results);position[0]=17
        repeat(2) { index ->
            session.collections.toggleCatalogLibrary(source.publication);advanceUntilIdle()
            assertEquals(index==0,source.publication.id in fake.saved)
            assertSame(results,search.search.state.value)
            assertSame(position,viewport.forState(search.search.state.value))
            assertEquals(17,position[0])
        }
        session.openSearch(source.publication);advanceUntilIdle()
        assertIs<OpenPublicationState.Ready>(session.opening.value)
        session.back();advanceUntilIdle()
        assertSame(search,session.searchSession.value)
        assertSame(results,search.search.state.value)
        assertSame(position,viewport.forState(search.search.state.value))
        assertEquals(17,position[0]);assertEquals("query",search.query.value)
        finish(session,owner)
    }
    @Test fun libraryOpenResolvesSourceAndResourcesRatherThanStoredMetadata()=runTest {
        val source=Source();val fake=FakeCollections();val snapshot=PublicationSnapshot.from(source.publication).copy(title="Stored old title",sourceUrl="https://untrusted.example/not-acquisition")
        fake.saved[snapshot.id]=LibraryEntry(snapshot,1);val owner=owner(source,fake);val session=session(owner)
        session.navigate(Destination.LIBRARY);session.openSaved(snapshot);advanceUntilIdle()
        val ready=assertIs<OpenPublicationState.Ready>(session.opening.value);assertEquals("Fresh title",ready.document.title)
        assertEquals(1,source.metadata);assertEquals(1,source.acquisitions);assertNull(source.resource.revision)
        session.back();advanceUntilIdle();assertEquals(Destination.LIBRARY,session.destination.value);assertEquals(1,fake.saved.size)
        finish(session,owner)
    }
    @Test fun historyOpenReResolvesAndReturnsToHistory()=runTest {
        val source=Source();val fake=FakeCollections();val snapshot=PublicationSnapshot.from(source.publication)
        fake.opened[snapshot.id]=HistoryEntry(snapshot,1);val owner=owner(source,fake);val session=session(owner)
        session.navigate(Destination.HISTORY);session.openSaved(snapshot);advanceUntilIdle();assertEquals(1,source.metadata);assertEquals(1,source.acquisitions)
        session.back();advanceUntilIdle();assertEquals(Destination.HISTORY,session.destination.value);assertEquals(1,fake.opened.size);assertEquals(10L,fake.opened[snapshot.id]?.lastOpenedAtEpochMillis)
        finish(session,owner)
    }
    @Test fun metadataFailureKeepsLibraryAndDoesNotRecordHistory()=runTest {
        val source=Source();source.failedDetails=true;val fake=FakeCollections();val snapshot=PublicationSnapshot.from(source.publication)
        fake.saved[snapshot.id]=LibraryEntry(snapshot,1);val owner=owner(source,fake);val session=session(owner)
        session.navigate(Destination.LIBRARY);session.openSaved(snapshot);advanceUntilIdle();assertIs<OpenPublicationState.Error>(session.opening.value)
        assertEquals(1,fake.saved.size);assertTrue(fake.opened.isEmpty());assertEquals(0,source.acquisitions);finish(session,owner)
    }
    @Test fun acquisitionFailureKeepsHistoryAndDoesNotUpdateItsTimestamp()=runTest {
        val source=Source();source.failedBytes=true;val fake=FakeCollections();val snapshot=PublicationSnapshot.from(source.publication)
        fake.opened[snapshot.id]=HistoryEntry(snapshot,1);val owner=owner(source,fake);val session=session(owner)
        session.navigate(Destination.HISTORY);session.openSaved(snapshot);advanceUntilIdle();val error=assertIs<OpenPublicationState.Error>(session.opening.value)
        assertFalse(error.userMessage.contains("private"));assertEquals(1L,fake.opened[snapshot.id]?.lastOpenedAtEpochMillis);finish(session,owner)
    }
    @Test fun savedOpenStillRestoresProgressAndFreshAcquiresEachTime()=runTest {
        val source=Source();val fake=FakeCollections();val snapshot=PublicationSnapshot.from(source.publication)
        val key=ReadingProgressId(snapshot.id,"text",PublicationFormat.TEXT)
        val position=ReadingProgress(key,ReadingLocator.Text(6,10),0.6,1)
        val progress=ProgressPersistence(object : ReadingProgressStore {
            override suspend fun get(id: ReadingProgressId)=position.takeIf{it.id==id}
            override suspend fun save(progress: ReadingProgress)=true
            override suspend fun remove(id: ReadingProgressId)=true
        },StandardTestDispatcher(testScheduler))
        val owner=owner(source,fake,progress);val session=session(owner);session.navigate(Destination.LIBRARY)
        repeat(2){session.openSaved(snapshot);advanceUntilIdle();assertEquals(6,assertNotNull(assertIs<OpenPublicationState.Ready>(session.opening.value).reading).codePointOffset.value);session.back();advanceUntilIdle()}
        assertEquals(2,source.metadata);assertEquals(2,source.acquisitions);assertNull(source.resource.cacheKey);finish(session,owner)
    }
    @Test fun cancellingSavedOpenRecordsNoHistoryAndReturnsToOrigin()=runTest {
        val source=Source();source.acquireAction={awaitCancellation()};val fake=FakeCollections();val owner=owner(source,fake);val session=session(owner)
        session.navigate(Destination.HISTORY);session.openSaved(PublicationSnapshot.from(source.publication));runCurrent()
        session.back();advanceUntilIdle();assertIs<OpenPublicationState.Idle>(session.opening.value);assertEquals(Destination.HISTORY,session.destination.value);assertTrue(fake.opened.isEmpty());finish(session,owner)
    }
    @Test fun unknownSourceIsControlledErrorWithoutAcquisition()=runTest {
        val source=Source();val fake=FakeCollections();val owner=owner(source,fake);val session=session(owner)
        session.navigate(Destination.LIBRARY);session.openSaved(PublicationSnapshot(PublicationId(SourceId("unknown"),"1"),"Title",PublicationType.BOOK));advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(session.opening.value);assertEquals(0,source.metadata);assertFalse(session.canRetry(source.publication.copy(id=PublicationId(SourceId("unknown"),"1"),resources=emptyList())))
        session.back();advanceUntilIdle();assertEquals(Destination.LIBRARY,session.destination.value);finish(session,owner)
    }
    @Test fun collectionBackReturnsSearchWithoutChangingSelectedSourceOrQuery()=runTest {
        val source=Source();val fake=FakeCollections();val owner=owner(source,fake);val session=session(owner)
        session.searchSession.value.editQuery("keep");session.navigate(Destination.LIBRARY);assertTrue(session.handlesBack());session.back()
        assertEquals(Destination.SEARCH,session.destination.value);assertEquals("keep",session.searchSession.value.query.value);assertFalse(session.handlesBack());finish(session,owner)
    }
    @Test fun unavailableLocalStorageDoesNotPreventReader()=runTest {
        val source=Source();val fake=FakeCollections();fake.fail=true;val owner=owner(source,fake);val session=session(owner)
        session.openSearch(source.publication);advanceUntilIdle();assertIs<OpenPublicationState.Ready>(session.opening.value)
        assertTrue(session.collections.reader.value.failed);assertTrue(assertNotNull(owner.collections).historyFailed.value);finish(session,owner)
    }
    @Test fun archiveLibraryOpenKeepsGutenbergSearchAndNeverAcquiresFromGutenberg()=runTest {
        val archive=Source();val gutenberg=Source(SourceId("gutenberg"));val fake=FakeCollections()
        val collections=ApplicationCollections(fake.library,fake.history,StandardTestDispatcher(testScheduler))
        val owner=ApplicationSources(listOf(SourceOption("Gutenberg",gutenberg,false),SourceOption("Archive",archive,true)),collections=collections) {}
        val session=session(owner);val search=session.searchSession.value
        search.editQuery("keep Gutenberg");search.submitSearch();advanceUntilIdle();val results=search.search.state.value
        session.openSearch(gutenberg.publication);advanceUntilIdle();assertIs<OpenPublicationState.Idle>(session.opening.value)
        session.navigate(Destination.LIBRARY);session.openSaved(PublicationSnapshot.from(archive.publication));advanceUntilIdle()
        assertIs<OpenPublicationState.Ready>(session.opening.value);assertEquals(1,archive.metadata);assertEquals(1,archive.acquisitions)
        assertEquals(0,gutenberg.metadata);assertEquals(0,gutenberg.acquisitions)
        session.back();advanceUntilIdle();session.back();assertEquals(0,session.selected.value)
        assertEquals("keep Gutenberg",search.query.value);assertSame(results,search.search.state.value)
        session.selectSource(1);assertEquals("",session.searchSession.value.query.value);finish(session,owner)
    }
    @Test fun lateCancelledAcquisitionCannotRecordHistory()=runTest {
        val source=Source();val gate=CompletableDeferred<Unit>();source.acquireAction={withContext(NonCancellable){gate.await()}}
        val fake=FakeCollections();val owner=owner(source,fake);val session=session(owner)
        session.navigate(Destination.HISTORY);session.openSaved(PublicationSnapshot.from(source.publication));runCurrent()
        session.back();gate.complete(Unit);advanceUntilIdle()
        assertIs<OpenPublicationState.Idle>(session.opening.value);assertTrue(fake.opened.isEmpty());finish(session,owner)
    }
    @Test fun duplicateSavedOpenDoesNotAcquireOrRecordTwice()=runTest {
        val source=Source();val gate=CompletableDeferred<Unit>();source.acquireAction={gate.await()}
        val fake=FakeCollections();val owner=owner(source,fake);val session=session(owner)
        session.navigate(Destination.LIBRARY);val snapshot=PublicationSnapshot.from(source.publication)
        session.openSaved(snapshot);session.openSaved(snapshot);runCurrent();assertEquals(1,source.acquisitions)
        gate.complete(Unit);advanceUntilIdle();assertEquals(1,fake.opened.size);assertEquals(1,source.metadata);finish(session,owner)
    }
}
