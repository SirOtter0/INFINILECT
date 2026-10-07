// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import org.infinilect.app.media.*
import org.infinilect.app.progress.ProgressPersistence

internal fun pageTestBytes() = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFElEQVR4nGP4z8DAAMIM////ZwAAHu8E/KPItPcAAAAASUVORK5CYII=")
internal class TestPageDocument(count: Int = 100, keys: List<String>? = null,
    override val publicationId: PublicationId = PublicationId(SourceId("test"),"comic")) : PageDocument {
    override val title = "Original comic"
    override val progressId = ReadingProgressId(publicationId,"page-sequence",PublicationFormat.PAGES)
    override val pages = (0 until count).map { PageEntry(PublicationResource(publicationId,keys?.get(it) ?: "page-$it",PublicationFormat.PAGES,"image/png",pageDimensions=PageDimensions(2,2))) }
    var closes=0; val opened=mutableListOf<String>(); var closedHandles=0
    var bytes=pageTestBytes(); var declared: Long?=bytes.size.toLong()
    var readAction: suspend () -> Unit = {}
    override suspend fun openPage(page: PageEntry): ResourceContent {
        require(page in pages); opened.add(page.key)
        return object : ResourceContent {
            override val sizeBytes=declared
            var position=0;var closed=false
            override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                check(!closed); readAction(); currentCoroutineContext().ensureActive()
                if(position==bytes.size) return -1
                val n=minOf(length,bytes.size-position);bytes.copyInto(buffer,offset,position,position+n);position+=n;return n
            }
            override fun close() { if(!closed) {closed=true;closedHandles++} }
        }
    }
    override fun close() { closes++ }
}
internal class TestRasterDecoder : RasterDecoder {
    var action: suspend () -> Unit = {}; var calls=0
    override suspend fun decode(bytes: ByteArray, mediaType: String): Raster { calls++;action();return Raster(2,2,IntArray(4)) }
}
@OptIn(ExperimentalCoroutinesApi::class)
class PageReaderControllerTest {
    private suspend fun TestScope.reader(doc: TestPageDocument=TestPageDocument(), decoder: TestRasterDecoder=TestRasterDecoder(), action: suspend (PageReaderController) -> Unit) {
        val reader=PageReaderController(doc,this,decoder=decoder,decodeDispatcher=StandardTestDispatcher(testScheduler))
        try { reader.initialize(null);runCurrent();action(reader) } finally { reader.close();runCurrent() }
        assertEquals(1,doc.closes);assertEquals(doc.opened.size,doc.closedHandles)
    }
    @Test fun firstPageAndAdjacentPrefetchOnly()=runTest {
        val doc=TestPageDocument();reader(doc) { reader ->
            assertEquals(0,reader.state.value.position.index);assertEquals(listOf("page-0","page-1"),doc.opened)
            assertEquals(2,reader.retainedPages)
        }
    }
    @Test fun nextPreviousAndEdgesAreSafe()=runTest { reader(TestPageDocument(3)) {
        it.previous();assertEquals(0,it.state.value.position.index);it.next();it.next();it.next();assertEquals(0,it.state.value.position.index);runCurrent();it.settlePageTurn();assertEquals(2,it.state.value.position.index)
        it.previous();runCurrent();it.settlePageTurn();assertEquals(1,it.state.value.position.index)
    } }
    @Test fun rtlAndLtrSwipesUseLogicalOrder()=runTest { reader {
        it.mode(PageReadingMode.PAGED_RTL)
        it.swipe(true);runCurrent();it.settlePageTurn();assertEquals(1,it.state.value.position.index);it.swipe(false);runCurrent();it.settlePageTurn();assertEquals(0,it.state.value.position.index)
        it.mode(PageReadingMode.PAGED_LTR);it.swipe(false);runCurrent();it.settlePageTurn();assertEquals(1,it.state.value.position.index);it.swipe(true);runCurrent();it.settlePageTurn();assertEquals(0,it.state.value.position.index)
    } }
    @Test fun everyModePreservesPageAndIntraPageFractionAndInvalidatesOldCallbacks()=runTest { reader {
        it.report(it.state.value.ticket,57,.4)
        for(mode in PageReadingMode.entries) {val old=it.state.value.ticket;it.mode(mode);assertEquals(PagePosition(57,.4),it.state.value.position)
            if(old!=it.state.value.ticket) {it.report(old,0,0.0);assertEquals(57,it.state.value.position.index)} }
    } }
    @Test fun viewportResizeKeepsSemanticPositionAndRejectsOldLayoutCallbacks()=runTest { reader {
        it.report(it.state.value.ticket,42,.7);val old=it.state.value.ticket
        it.presentationChanged();it.report(old,0,0.0);assertEquals(PagePosition(42,.7),it.state.value.position)
    } }
    @Test fun distantNavigationAndReverseMovementHaveHardWorkingSetBound()=runTest {
        val doc=TestPageDocument();reader(doc) { reader ->
            for(index in listOf(50,99,2,51,1)) {reader.navigate(index);runCurrent();assertTrue(reader.retainedPages<=3);assertTrue(reader.state.value.frames.size<=3)
                assertIs<PageFrame.Ready>(reader.state.value.frames[index])}
            assertTrue(doc.opened.size<20) // a hundred pages were not decoded eagerly
        }
    }
    @Test fun rapidNavigationDiscardsWorkBeforeItStarts()=runTest {
        val doc=TestPageDocument();reader(doc) {it.navigate(10);it.navigate(20);it.navigate(90);runCurrent()
            assertEquals(90,it.state.value.position.index);assertFalse("page-10" in doc.opened);assertFalse("page-20" in doc.opened)}
    }
    @Test fun visiblePagesTakePriorityOverPrefetchAndRemainBounded()=runTest { reader {
        it.report(it.state.value.ticket,40,.2,listOf(40,41,42,43,44));runCurrent()
        assertEquals(setOf(40,41,42),it.state.value.frames.keys);assertEquals(3,it.retainedPages)
    } }
    @Test fun noncooperativeDecodeCannotPublishAfterBackOrStartParallelDecodes()=runTest {
        val gate=CompletableDeferred<Unit>();val decoder=TestRasterDecoder();decoder.action={withContext(NonCancellable){gate.await()}}
        val doc=TestPageDocument();val reader=PageReaderController(doc,this,decoder=decoder,decodeDispatcher=StandardTestDispatcher(testScheduler))
        reader.initialize(null);runCurrent();reader.navigate(80);runCurrent();assertEquals(1,decoder.calls)
        reader.close();gate.complete(Unit);runCurrent();assertTrue(reader.state.value.frames.isEmpty());assertEquals(0,reader.retainedPages)
        assertEquals(1,doc.closes);assertEquals(doc.opened.size,doc.closedHandles)
    }
    @Test fun decodeFailureIsPagePlaceholderAndNavigationContinues()=runTest {
        val decoder=TestRasterDecoder();decoder.action={error("private provider error")}
        reader(decoder=decoder) {assertIs<PageFrame.Unavailable>(it.state.value.frames[0]);decoder.action={};it.navigate(20);runCurrent();assertIs<PageFrame.Ready>(it.state.value.frames[20])}
    }
    @Test fun providerDimensionMismatchIsRejected()=runTest {
        val doc=TestPageDocument(1)
        val reader=PageReaderController(doc,this,decoder=object:RasterDecoder{override suspend fun decode(bytes:ByteArray,mediaType:String)=Raster(1,1,IntArray(1))},decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {reader.initialize(null);runCurrent();assertIs<PageFrame.Unavailable>(reader.state.value.frames[0])}finally{reader.close()}
    }
    @Test fun malformedBytesNeverReachPixelDecoder()=runTest {
        val doc=TestPageDocument(1);doc.bytes=byteArrayOf(1,2,3);doc.declared=3
        val decoder=TestRasterDecoder();reader(doc,decoder){assertIs<PageFrame.Unavailable>(it.state.value.frames[0]);assertEquals(0,decoder.calls)}
    }
    @Test fun unknownOversizeShortAndLongSizeFailAndAlwaysClose()=runTest {
        for(size in listOf(null,0L,2_097_153L,pageTestBytes().size-1L,pageTestBytes().size+1L)) {
            val doc=TestPageDocument(1);doc.declared=size;val decoder=TestRasterDecoder()
            reader(doc,decoder){assertIs<PageFrame.Unavailable>(it.state.value.frames[0]);assertEquals(0,decoder.calls)}
        }
    }
    @Test fun cancellationDuringAcquisitionClosesResource()=runTest {
        val doc=TestPageDocument(1);doc.readAction={awaitCancellation()}
        val reader=PageReaderController(doc,this,decodeDispatcher=StandardTestDispatcher(testScheduler))
        reader.initialize(null);runCurrent();reader.close();runCurrent();assertEquals(1,doc.closedHandles);assertTrue(reader.state.value.frames.isEmpty())
    }
    @Test fun decodeTimeoutIsSafeAndBounded()=runTest {
        val decoder=TestRasterDecoder();decoder.action={awaitCancellation()}
        reader(TestPageDocument(1),decoder){advanceTimeBy(10_000);runCurrent();assertIs<PageFrame.Unavailable>(it.state.value.frames[0])}
    }
    @Test fun stableKeyWinsOnReorderingAndRemovedKeyFallsBackToClampedIndex()=runTest {
        val doc=TestPageDocument(3,listOf("c","a","b"));val reader=PageReaderController(doc,this,decoder=TestRasterDecoder(),decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {reader.initialize(ReadingProgress(doc.progressId,ReadingLocator.Page("b",0,.7),.5,1));assertEquals(PagePosition(2,.7),reader.state.value.position)}finally{reader.close()}
        val second=TestPageDocument(3);val other=PageReaderController(second,this,decoder=TestRasterDecoder(),decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {other.initialize(ReadingProgress(second.progressId,ReadingLocator.Page("gone",99,.6),.5,1));assertEquals(PagePosition(2,.6),other.state.value.position)}finally{other.close()}
    }
    @Test fun anotherPublicationProgressCannotRestoreIntoThisDocument()=runTest {
        val doc=TestPageDocument(3);val reader=PageReaderController(doc,this,decoder=TestRasterDecoder(),decodeDispatcher=StandardTestDispatcher(testScheduler))
        try{reader.initialize(ReadingProgress(doc.progressId.copy(publicationId=doc.publicationId.copy(sourceId=SourceId("other"))),ReadingLocator.Page("page-2",2,.8),.8,1))
            assertEquals(PagePosition(0),reader.state.value.position)}finally{reader.close()}
    }
    @Test fun publicationReplacementDoesNotOverlapOldNativeWorkOrAcceptOldResults()=runTest {
        val gate=CompletableDeferred<Unit>();val oldDecoder=TestRasterDecoder();oldDecoder.action={withContext(NonCancellable){gate.await()}}
        val oldDoc=TestPageDocument();val old=PageReaderController(oldDoc,this,decoder=oldDecoder,decodeDispatcher=StandardTestDispatcher(testScheduler))
        val newDoc=TestPageDocument(publicationId=oldDoc.publicationId.copy(localId="another"));val newDecoder=TestRasterDecoder()
        val current=PageReaderController(newDoc,this,decoder=newDecoder,decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {
            old.initialize(null);runCurrent();val ticket=old.state.value.ticket;old.close()
            current.initialize(null);current.navigate(30);runCurrent();assertEquals(0,newDecoder.calls)
            gate.complete(Unit);runCurrent();assertIs<PageFrame.Ready>(current.state.value.frames[30]);assertTrue(old.state.value.frames.isEmpty())
            old.report(ticket,99,1.0);assertEquals(30,current.state.value.position.index)
        }finally{gate.complete(Unit);old.close();current.close();runCurrent()}
    }
    @Test fun wrongIdentityIsNeverRestoredAndMalformedCallbacksAreIgnored()=runTest { reader {
        it.report(it.state.value.ticket,-1,0.0);it.report(it.state.value.ticket,0,Double.NaN);assertEquals(PagePosition(0),it.state.value.position)
        it.report(it.state.value.ticket,1,2.0);assertEquals(1.0,it.state.value.position.fraction)
        it.navigate(3);val ticket=it.state.value.ticket;it.navigate(4);it.report(ticket,0,0.0);assertEquals(4,it.state.value.position.index)
    } }
    @Test fun latestProgressIsPeriodicAndQuickBackFlushesItWithoutStaleRegression()=runTest {
        val values=mutableMapOf<ReadingProgressId,ReadingProgress>()
        val persistence=ProgressPersistence(object:ReadingProgressStore{
            override suspend fun get(id:ReadingProgressId)=values[id]
            override suspend fun save(progress:ReadingProgress):Boolean{values[progress.id]=progress;return true}
            override suspend fun remove(id:ReadingProgressId)=true
        },StandardTestDispatcher(testScheduler)){10}
        val doc=TestPageDocument();val reader=PageReaderController(doc,this,persistence,decoder=TestRasterDecoder(),decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {
            reader.initialize(null);reader.report(reader.state.value.ticket,40,.6);runCurrent()
            reader.presented(reader.state.value.ticket,40,assertIs<PageFrame.Ready>(reader.state.value.frames[40]).stamp)
            advanceTimeBy(2000);runCurrent()
            assertEquals(40,(assertNotNull(values[doc.progressId]).locator as ReadingLocator.Page).pageIndex)
            val old=reader.state.value.ticket;reader.navigate(60);reader.report(old,1,0.0);runCurrent()
            reader.presented(reader.state.value.ticket,60,assertIs<PageFrame.Ready>(reader.state.value.frames[60]).stamp)
            reader.close();runCurrent()
            assertEquals(60,(assertNotNull(values[doc.progressId]).locator as ReadingLocator.Page).pageIndex)
        }finally{reader.close();persistence.close();persistence.awaitClosed()}
    }
    @Test fun repeatedCloseDropsAllPageStateAndIgnoresLaterCommands()=runTest { reader {
        it.close();it.close();it.next();it.mode(PageReadingMode.VERTICAL);assertTrue(it.state.value.frames.isEmpty());assertEquals(0,it.state.value.position.index)
    } }
    @Test fun quickReaderBackPersistsGlobalModeForAnotherPublication()=runTest {
        var stored=PageReaderPreferences()
        val preferences=PageSettingsPersistence(object:PageReaderSettingsStore{
            override suspend fun load()=stored
            override suspend fun save(preferences:PageReaderPreferences):Boolean{stored=preferences;return true}
        },StandardTestDispatcher(testScheduler)){1}
        val firstDoc=TestPageDocument();val first=PageReaderController(firstDoc,this,preferences=preferences,decoder=TestRasterDecoder(),decodeDispatcher=StandardTestDispatcher(testScheduler))
        val second=PageReaderController(TestPageDocument(publicationId=firstDoc.publicationId.copy(localId="another")),this,preferences=preferences,decoder=TestRasterDecoder(),decodeDispatcher=StandardTestDispatcher(testScheduler))
        try{first.initialize(null);first.mode(PageReadingMode.VERTICAL);first.close();runCurrent();assertEquals(PageReadingMode.VERTICAL,stored.settings.mode)
            second.initialize(null);assertEquals(PageReadingMode.VERTICAL,second.state.value.settings.mode);assertEquals(PagePosition(0),second.state.value.position)
            first.mode(PageReadingMode.PAGED_LTR);assertEquals(PageReadingMode.VERTICAL,preferences.settings.value.mode)}
        finally{first.close();second.close();preferences.close();preferences.awaitClosed()}
    }
}
