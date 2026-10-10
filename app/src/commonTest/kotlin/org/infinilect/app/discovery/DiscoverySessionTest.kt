// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.*
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.collections.*
import org.infinilect.core.*
import kotlin.test.*

class DiscoverySessionTest {
    private class Source(override val id: SourceId): PublicationSource,DiscoverySource {
        val resource=PublicationResource(PublicationId(id,"1"),"text",PublicationFormat.TEXT,"text/plain")
        val publication=Publication(resource.publicationId,"Original current metadata",PublicationType.BOOK,resources=listOf(resource))
        var acquisitions=0;var resolutions=0
        override suspend fun discover(request:DiscoveryRequest,token:String?)=DiscoveryPage(listOf(DiscoveryEntry(publication.copy(resources=emptyList()))))
        override suspend fun search(query:String,pageToken:String?)=SearchPage(listOf(publication.copy(resources=emptyList())))
        override suspend fun getPublication(publicationId:PublicationId):Publication {assertEquals(publication.id,publicationId);resolutions++;return publication}
        override suspend fun loadResource(resource:PublicationResource):ResourceContent {
            assertEquals(this.resource,resource);acquisitions++
            return object:ResourceContent {var done=false;override val sizeBytes=10L
                override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {if(done)return -1;done=true;"0123456789".encodeToByteArray().copyInto(buffer,offset);return 10}
                override fun close(){}
            }
        }
    }
    @Test fun discoveredOpenUsesOwningSourceFreshMetadataAndExistingSavedLocator()=runTest {
        val a=Source(SourceId("first"));val b=Source(SourceId("second"));val fake=FakeCollections()
        val saved=ReadingProgress(ReadingProgressId(b.publication.id,"text",PublicationFormat.TEXT),ReadingLocator.Text(6,10),.6,1)
        val writes=mutableListOf<ReadingProgress>()
        val progress=ProgressPersistence(object:ReadingProgressStore {
            override suspend fun get(id:ReadingProgressId)=saved.takeIf{it.id==id}
            override suspend fun save(progress:ReadingProgress):Boolean{writes+=progress;return true}
            override suspend fun remove(id:ReadingProgressId)=true
        },dispatcher=StandardTestDispatcher(testScheduler))
        val owner=ApplicationSources(listOf(SourceOption("First",a,true),SourceOption("Second",b,true)),progress=progress,
            collections=ApplicationCollections(fake.library,fake.history,StandardTestDispatcher(testScheduler))) {}
        val app=ApplicationSession(owner,this,StandardTestDispatcher(testScheduler)){10}
        app.navigate(Destination.SEARCH);app.discovery.edit("q");advanceTimeBy(350);runCurrent()
        assertTrue(writes.isEmpty());assertTrue(fake.opened.isEmpty());assertEquals(0,a.acquisitions+b.acquisitions)
        app.openDiscovered(b.publication.copy(title="Search metadata",resources=emptyList()));advanceUntilIdle()
        val ready=assertIs<OpenPublicationState.Ready>(app.opening.value)
        assertEquals(6,assertNotNull(ready.reading).codePointOffset.value)
        assertEquals(0,a.resolutions);assertEquals(1,b.resolutions);assertEquals(1,b.acquisitions)
        assertEquals(b.publication.title,fake.opened[b.publication.id]?.publication?.title)
        app.back();advanceUntilIdle();assertEquals(Destination.SEARCH,app.destination.value);assertEquals("q",app.discovery.state.value.query)
        app.close();owner.close();owner.awaitProgressClosed()
    }
    @Test fun catalogOnlyMetadataAndLibraryActionsNeverCreateProgressOrHistory()=runTest {
        val source=Source(SourceId("catalog-only"));val fake=FakeCollections()
        val owner=ApplicationSources(listOf(SourceOption("Catalog",source)),collections=ApplicationCollections(fake.library,fake.history,StandardTestDispatcher(testScheduler))) {}
        val app=ApplicationSession(owner,this,StandardTestDispatcher(testScheduler)){10}
        app.discovery.edit("q");advanceTimeBy(350);runCurrent();app.collections.toggleCatalogLibrary(source.publication.copy(resources=emptyList()));advanceUntilIdle()
        assertEquals(1,fake.saved.size);assertTrue(fake.opened.isEmpty());assertEquals(0,source.acquisitions)
        app.openDiscovered(source.publication);advanceUntilIdle();assertIs<OpenPublicationState.Error>(app.opening.value);assertEquals(0,source.acquisitions)
        assertEquals(1,fake.saved.size);assertTrue(fake.opened.isEmpty());app.close();owner.close();owner.awaitProgressClosed()
    }
}
