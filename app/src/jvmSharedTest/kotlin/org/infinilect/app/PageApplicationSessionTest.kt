// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.collections.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.page.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class PageApplicationSessionTest {
    private class Source(override val id:SourceId=SourceId("test")):PublicationSource {
        val resource=PublicationResource(PublicationId(id,"comic"),"page-0",PublicationFormat.PAGES,"image/png",pageDimensions=PageDimensions(2,2))
        val publication=Publication(resource.publicationId,"Fresh original comic",PublicationType.COMIC,resources=listOf(resource,resource.copy(key="page-1")))
        var resolutions=0;var missing=false;var action:suspend ()->Unit={}
        override suspend fun search(query:String,pageToken:String?)=SearchPage(listOf(publication.copy(title="Catalog snapshot",resources=emptyList())))
        override suspend fun getPublication(publicationId:PublicationId):Publication?{assertEquals(publication.id,publicationId);resolutions++;action();return publication.takeUnless{missing}}
        override suspend fun loadResource(resource:PublicationResource):ResourceContent {
            require(resource in publication.resources)
            return object:ResourceContent{
                override val sizeBytes=pageTestBytes().size.toLong();var position=0;var closed=false
                override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int{check(!closed);currentCoroutineContext().ensureActive();val b=pageTestBytes();if(position==b.size)return -1;val n=minOf(length,b.size-position);b.copyInto(buffer,offset,position,position+n);position+=n;return n}
                override fun close(){closed=true}
            }
        }
    }
    private suspend fun TestScope.use(action:suspend (ApplicationSession,ApplicationSources,Source,FakeCollections)->Unit) {
        val source=Source();val fake=FakeCollections();val dispatcher=StandardTestDispatcher(testScheduler)
        val owner=ApplicationSources(listOf(SourceOption("Comic",source,pageReadingEnabled=true)),
            collections=ApplicationCollections(fake.library,fake.history,dispatcher),pagePreparer=defaultPagePreparer()){}
        val session=ApplicationSession(owner,this,dispatcher){10};owner.attach(session);session.navigate(Destination.SEARCH)
        try{action(session,owner,source,fake)}finally{owner.close();owner.awaitProgressClosed()}
    }
    @Test fun searchReaderBackPreservesResultsAndRecordsOnlyFreshMetadata()=runTest {use{session,_,source,fake->
        val search=session.searchSession.value;search.editQuery("original");search.submitSearch();advanceUntilIdle();val results=search.search.state.value
        session.openSearch(source.publication);advanceUntilIdle();val ready=assertIs<OpenPublicationState.PageReady>(session.opening.value)
        assertEquals(1,source.resolutions);assertEquals("Fresh original comic",fake.opened[source.publication.id]?.publication?.title)
        assertTrue(session.handlesBack());session.back();advanceUntilIdle();assertIs<OpenPublicationState.Idle>(session.opening.value)
        assertTrue(ready.reader.state.value.frames.isEmpty());assertEquals("original",search.query.value);assertSame(results,search.search.state.value);assertTrue(session.handlesBack());session.back();assertEquals(Destination.HOME,session.destination.value);assertFalse(session.handlesBack())
    }}
    @Test fun libraryAndHistoryOpenReResolveAndBackReturnsToPreviousDestination()=runTest {use{session,_,source,fake->
        val saved=PublicationSnapshot.from(source.publication).copy(title="Old snapshot",sourceUrl="https://untrusted.invalid")
        fake.saved[saved.id]=LibraryEntry(saved,1);fake.opened[saved.id]=HistoryEntry(saved,1)
        for(destination in listOf(Destination.LIBRARY,Destination.HISTORY)) {
            session.navigate(destination);session.openSaved(saved);advanceUntilIdle();val ready=assertIs<OpenPublicationState.PageReady>(session.opening.value)
            assertEquals("Fresh original comic",ready.reader.document.title);session.back();advanceUntilIdle();assertEquals(destination,session.destination.value)
        }
        assertEquals(2,source.resolutions);assertEquals(1,fake.saved.size);assertEquals(1,fake.opened.size)
    }}
    @Test fun unavailableSavedPublicationKeepsLibraryAndDoesNotRecordHistory()=runTest {use{session,_,source,fake->
        val saved=PublicationSnapshot.from(source.publication);fake.saved[saved.id]=LibraryEntry(saved,1);source.missing=true
        session.navigate(Destination.LIBRARY);session.openSaved(saved);advanceUntilIdle();assertIs<OpenPublicationState.Error>(session.opening.value)
        assertTrue(fake.opened.isEmpty());assertTrue(saved.id in fake.saved);session.back();advanceUntilIdle();assertEquals(Destination.LIBRARY,session.destination.value)
    }}
    @Test fun backDuringOpenCancelsAndLateMetadataCannotPublishReaderOrHistory()=runTest {use{session,_,source,fake->
        val gate=CompletableDeferred<Unit>();source.action={withContext(NonCancellable){gate.await()}}
        session.openSearch(source.publication);runCurrent();assertIs<OpenPublicationState.Loading>(session.opening.value)
        session.back();gate.complete(Unit);advanceUntilIdle();assertIs<OpenPublicationState.Idle>(session.opening.value);assertTrue(fake.opened.isEmpty())
    }}
    @Test fun sourceSwitchAfterBackRejectsLateOldPublicationAndRetainsNewSearch()=runTest {
        val first=Source();val second=Source(SourceId("other"));val fake=FakeCollections();val dispatcher=StandardTestDispatcher(testScheduler)
        val gate=CompletableDeferred<Unit>();first.action={withContext(NonCancellable){gate.await()}}
        val owner=ApplicationSources(listOf(SourceOption("First",first,pageReadingEnabled=true),SourceOption("Second",second,pageReadingEnabled=true)),
            collections=ApplicationCollections(fake.library,fake.history,dispatcher),pagePreparer=defaultPagePreparer()){}
        val session=ApplicationSession(owner,this,dispatcher){10};owner.attach(session);session.navigate(Destination.SEARCH)
        try {
            session.openSearch(first.publication);runCurrent();session.back();session.selectSource(1)
            val search=session.searchSession.value;search.editQuery("second");search.submitSearch();runCurrent();session.openSearch(second.publication);runCurrent()
            gate.complete(Unit);advanceUntilIdle();assertEquals(second.publication.id,assertIs<OpenPublicationState.PageReady>(session.opening.value).publication.id)
            assertFalse(first.publication.id in fake.opened);assertTrue(second.publication.id in fake.opened)
            session.back();advanceUntilIdle();assertEquals("second",search.query.value);assertTrue(session.handlesBack());session.back();assertEquals(Destination.HOME,session.destination.value);assertFalse(session.handlesBack())
        }finally{gate.complete(Unit);owner.close();owner.awaitProgressClosed()}
    }
}
