// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.infinilect.core.*
import org.infinilect.app.page.DevelopmentComicSource
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.media.*
import kotlin.test.*

class ResourcePageDocumentTest {
    private val id=PublicationId(SourceId("test"),"comic")
    private val resource=PublicationResource(id,"p",PublicationFormat.PAGES,"image/png",pageDimensions=PageDimensions(2,2))
    private fun publication(resources:List<PublicationResource> = listOf(resource))=Publication(id,"Original",PublicationType.COMIC,resources=resources)
    private class Loader : ResourceLoader {
        val requests=mutableListOf<PublicationResource>();var closed=0
        var action:suspend ()->Unit={}
        override suspend fun load(resource:PublicationResource):ResourceContent {
            requests.add(resource);action()
            return object:ResourceContent{
                var done=false
                override val sizeBytes=pageTestBytes().size.toLong()
                var position=0
                override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {check(!done);val bytes=pageTestBytes();if(position==bytes.size)return -1;val n=minOf(length,bytes.size-position);bytes.copyInto(buffer,offset,position,position+n);position+=n;return n}
                override fun close(){if(!done){done=true;closed++}}
            }
        }
    }
    @Test fun orderedResourcesGoThroughLoaderWithoutInventingRevisions()=runBlocking {
        val loader=Loader();val doc=ResourcePageDocument(publication(listOf(resource.copy(key="z"),resource.copy(key="a"))),loader)
        try {assertEquals(listOf("z","a"),doc.pages.map{it.key});assertContentEquals(pageTestBytes(),doc.openPage(doc.pages[1]).readBytes(1024))
            assertEquals("a",loader.requests.single().key);assertNull(loader.requests.single().revision);assertEquals(1,loader.closed)}finally{doc.close()}
    }
    @Test fun undeclaredForeignOrAlteredResourceNeverCallsLoader()=runBlocking {
        val loader=Loader();val doc=ResourcePageDocument(publication(),loader)
        try {for(r in listOf(resource.copy(key="missing"),resource.copy(publicationId=id.copy(localId="other")),resource.copy(mediaType="image/jpeg"),resource.copy(revision="forged")))
            assertFailsWith<IllegalArgumentException>{doc.openPage(PageEntry(r))}
            assertTrue(loader.requests.isEmpty())}finally{doc.close()}
    }
    @Test fun missingGeometryEmptyAndTooManyPagesRejectBeforeAcquisition() {
        val loader=Loader()
        for(p in listOf(publication(emptyList()),publication(listOf(resource.copy(pageDimensions=null))),publication((0..512).map{resource.copy(key="$it")})))
            assertFailsWith<IllegalArgumentException>{ResourcePageDocument(p,loader)}
        assertTrue(loader.requests.isEmpty())
    }
    @Test fun unsupportedMimeAndAllocationBombMetadataAreNotAcquired()=runBlocking {
        val loader=Loader()
        for(r in listOf(resource.copy(mediaType="image/svg+xml"),resource.copy(mediaType="image/png; charset=utf-8"),resource.copy(pageDimensions=PageDimensions(16384,16384)),resource.copy(pageDimensions=PageDimensions(1,2048)))) {
            val doc=ResourcePageDocument(publication(listOf(r)),loader)
            try{assertFailsWith<IllegalArgumentException>{doc.openPage(doc.pages.single())}}finally{doc.close()}
        }
        assertTrue(loader.requests.isEmpty())
    }
    @Test fun closingDocumentClosesActiveHandlesAndIsIdempotent()=runBlocking<Unit> {
        val loader=Loader();val doc=ResourcePageDocument(publication(),loader);val handle=doc.openPage(doc.pages[0])
        doc.close();doc.close();handle.close();assertEquals(1,loader.closed)
        assertFailsWith<IllegalStateException>{handle.read(ByteArray(1))};assertFailsWith<IllegalStateException>{doc.openPage(doc.pages[0])}
    }
    @Test fun closeCancelsPendingAcquisitionAndDiscardsNoncooperativeHandle()=runBlocking {
        val gate=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>();val loader=Loader();loader.action={started.complete(Unit);withContext(NonCancellable){gate.await()}}
        val doc=ResourcePageDocument(publication(),loader);val request=async{doc.openPage(doc.pages[0])}
        started.await();doc.close();gate.complete(Unit)
        assertFailsWith<CancellationException>{request.await()};assertEquals(1,loader.closed)
    }
    @Test fun originalComicProvidesTwentyFourDifferentAspectPagesAndBothMimeTypes()=runBlocking {
        val source=DevelopmentComicSource();val result=source.search("original",null).publications.single()
        assertEquals(PublicationType.COMIC,result.type);assertEquals(24,result.resources.size)
        assertEquals(setOf("image/png","image/jpeg"),result.resources.map{it.mediaType}.toSet())
        assertTrue(result.resources.all{it.revision==null});assertTrue(result.resources.map{it.pageDimensions}.toSet().size>=4)
        val doc=defaultPagePreparer().prepare(result,DirectResourceLoader(source))
        try {for(page in doc.pages){val bytes=doc.openPage(page).readBytes(RasterPolicy.ENCODED_BYTES)
            assertEquals(page.dimensions.width to page.dimensions.height,inspectRaster(bytes,page.resource.mediaType))}}
        finally{doc.close()}
    }
    @Test fun developmentSourceRejectsOtherPublicationAndModifiedPage()=runBlocking<Unit> {
        val source=DevelopmentComicSource();val pub=source.search("demo",null).publications.single()
        assertNull(source.getPublication(pub.id.copy(localId="missing")))
        assertFailsWith<IllegalArgumentException>{source.getPublication(pub.id.copy(sourceId=SourceId("production")))}
        assertFailsWith<IllegalArgumentException>{source.loadResource(pub.resources[0].copy(key="undeclared"))}
        assertFailsWith<IllegalArgumentException>{source.loadResource(pub.resources[0].copy(pageDimensions=PageDimensions(1,1)))}
    }
    @Test fun declaredGeometryMismatchRejectedBeforeDecoderAllocation()=runBlocking {
        val loader=Loader();val doc=ResourcePageDocument(publication(listOf(resource.copy(pageDimensions=PageDimensions(3,3)))),loader)
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val decoder=TestRasterDecoder();val reader=PageReaderController(doc,scope,decoder=decoder)
        try{reader.initialize(null);withTimeout(5000){reader.state.first { it.frames[0] is PageFrame.Unavailable }};assertEquals(0,decoder.calls);assertEquals(1,loader.closed)}
        finally{reader.close();scope.cancel()}
    }
}
