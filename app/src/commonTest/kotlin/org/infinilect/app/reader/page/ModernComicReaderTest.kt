// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ModernComicReaderTest {
    private val portrait = PageDimensions(600, 900)
    @Test fun defaultRetainsSingleLtrFitScreen() {
        assertEquals(PageReadingMode.PAGED_LTR, PageReaderSettings().mode)
        assertEquals(PageLayout.SINGLE, PageReaderSettings().layout)
        assertEquals(PageFit.SCREEN, PageReaderSettings().fit)
    }
    @Test fun allFitsPreserveAspectAndCommonPairScale() {
        for (fit in PageFit.entries) for (viewport in listOf(Size(360f,640f),Size(960f,540f),Size(1200f,800f))) {
            val pair=fitPageSpread(listOf(portrait,PageDimensions(500,800)),viewport,2f,fit)
            assertEquals(600f/900, pair.pages[0].width/pair.pages[0].height,.0001f)
            assertEquals(500f/800, pair.pages[1].width/pair.pages[1].height,.0001f)
            assertEquals(pair.pages[0].width/600,pair.pages[1].width/500,.0001f)
            assertEquals(2f,pair.gutter);assertEquals(pair.pages.sumOf {it.width.toDouble()}.toFloat()+2,pair.size.width)
        }
    }
    @Test fun fitScreenNeverCropsWideTallSmallOrPairedArtwork() {
        for (pages in listOf(listOf(portrait),listOf(PageDimensions(900,200)),listOf(PageDimensions(200,1500)),listOf(PageDimensions(2,2)),listOf(portrait,portrait))) {
            val result=fitPageSpread(pages,Size(640f,360f),2f)
            assertTrue(result.size.width<=640.01f);assertTrue(result.size.height<=360.01f)
        }
    }
    @Test fun widthAndHeightExposeRealPanRangeWithoutDecodeScaling() {
        val width=fitPageSpread(listOf(portrait),Size(600f,400f),2f,PageFit.WIDTH)
        assertEquals(Size(600f,900f),width.size)
        assertEquals(0f,pagePanBounds(width.size,Size(600f,400f),1f).x)
        assertEquals(250f,pagePanBounds(width.size,Size(600f,400f),1f).y)
        val height=fitPageSpread(listOf(PageDimensions(900,300)),Size(600f,400f),2f,PageFit.HEIGHT)
        assertEquals(Size(1200f,400f),height.size)
        assertEquals(300f,pagePanBounds(height.size,Size(600f,400f),1f).x)
    }
    @Test fun resizeRecomputesFitAndZeroViewportRemainsSafe() {
        assertNotEquals(fitPageSpread(listOf(portrait),Size(360f,640f),2f).size,fitPageSpread(listOf(portrait),Size(640f,360f),2f).size)
        for(fit in PageFit.entries)assertEquals(Size.Zero,fitPageSpread(listOf(portrait),Size.Zero,2f,fit).size)
    }
    private suspend fun TestScope.reader(action:suspend (PageReaderController,TestPageDocument,TestRasterDecoder)->Unit) {
        val doc=TestPageDocument(12);val decoder=TestRasterDecoder()
        val reader=PageReaderController(doc,backgroundScope,decoder=decoder,decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {reader.initialize(null);reader.navigate(6);runCurrent();action(reader,doc,decoder)}finally{reader.close();runCurrent()}
        assertEquals(1,doc.closes);assertEquals(doc.opened.size,doc.closedHandles)
    }
    @Test fun fitChangesReuseRasterIdentitiesAndDoNotReopenOrDecode()=runTest {reader {r,doc,decoder->
        val stamps=r.state.value.frames;val opens=doc.opened.size;val calls=decoder.calls
        for(fit in PageFit.entries) {r.fit(fit);runCurrent();assertEquals(6,r.state.value.position.index);assertEquals(stamps,r.state.value.frames)}
        assertEquals(opens,doc.opened.size);assertEquals(calls,decoder.calls)
    }}
    @Test fun exactPageSurvivesFitsDirectionsLayoutsAndResize()=runTest {reader {r,_,_->
        r.layout(PageLayout.DOUBLE);runCurrent();assertEquals(5,r.state.value.position.index)
        for(fit in PageFit.entries) {r.fit(fit);r.mode(PageReadingMode.PAGED_RTL);r.presentationChanged();runCurrent();assertEquals(5,r.state.value.position.index)}
        r.layout(PageLayout.SINGLE);runCurrent();assertEquals(6,r.state.value.position.index)
    }}
    @Test fun fitRetiresActiveTransitionAndStaleFinishCannotMovePosition()=runTest {reader {r,_,_->
        r.next();val old=assertNotNull(r.state.value.transition).ticket
        r.fit(PageFit.WIDTH);runCurrent();assertNull(r.state.value.transition)
        r.transitionOffset(old,-1f);r.finishTransition(old);assertEquals(6,r.state.value.position.index)
    }}
    @Test fun seekIsIntentUntilValidatedSettlementAndOnlyNewestTargetSurvives()=runTest {reader {r,_,_->
        repeat(100) {r.seek(if(it%2==0)2 else 9);assertEquals(6,r.state.value.position.index);assertTrue(r.state.value.frames.size<=3)}
        runCurrent();val t=assertNotNull(r.state.value.transition);assertEquals(9,t.target)
        val stamp=(r.state.value.frames[9] as PageFrame.Ready).stamp
        r.transitionReady(t.ticket,9,stamp);assertEquals(6,r.state.value.position.index)
        r.transitionOffset(t.ticket,-1f);r.finishTransition(t.ticket);assertEquals(9,r.state.value.position.index)
        assertTrue(r.retainedPages<=3)
    }}
    @Test fun doubleSeekNormalizesToContainingSpreadAndBoundsRemainFour()=runTest {reader {r,_,_->
        r.layout(PageLayout.DOUBLE);r.seek(8);runCurrent()
        val t=assertNotNull(r.state.value.transition);assertEquals(7,t.target);assertEquals(listOf(7,8),r.spread(t.target).indices)
        assertTrue(r.retainedPages<=4);r.seek(-1);r.seek(100);assertEquals(t,r.state.value.transition)
    }}
    @Test fun continuousModesIgnoreButRetainFitPreferenceAndFraction()=runTest {reader {r,_,_->
        for(mode in listOf(PageReadingMode.VERTICAL,PageReadingMode.WEBTOON)) {
            r.mode(mode);r.report(r.state.value.ticket,6,.4);r.fit(PageFit.HEIGHT);runCurrent()
            assertEquals(PagePosition(6,.4),r.state.value.position);assertEquals(PageFit.HEIGHT,r.state.value.settings.fit)
        }
    }}
    @Test fun hidingControlsDoesNotChangeTicketPositionOrFrameOwnership()=runTest {reader {r,_,_->
        r.toggleControls();val before=r.state.value;r.hideControls();r.hideControls()
        assertEquals(before.copy(controlsVisible=false),r.state.value)
    }}
    @Test fun scrubRequestDecodeSettlementAndCancelledOldTicketCannotSaveProgress()=runTest {
        val doc=TestPageDocument(12)
        val writes=mutableListOf<ReadingProgress>()
        val store=object:ReadingProgressStore {
            override suspend fun get(id:ReadingProgressId):ReadingProgress?=null
            override suspend fun save(progress:ReadingProgress):Boolean {writes+=progress;return true}
            override suspend fun remove(id:ReadingProgressId)=false
        }
        val writer=org.infinilect.app.progress.ProgressPersistence(store,StandardTestDispatcher(testScheduler)){42}
        val r=PageReaderController(doc,backgroundScope,writer,decoder=TestRasterDecoder(),decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {
            r.initialize(null);runCurrent();r.seek(9);val old=assertNotNull(r.state.value.transition).ticket
            runCurrent();r.flush();runCurrent();assertTrue(writes.isEmpty())
            r.fit(PageFit.WIDTH);runCurrent();r.finishTransition(old);r.flush();runCurrent();assertTrue(writes.isEmpty())
            r.seek(9);runCurrent();val t=assertNotNull(r.state.value.transition);val stamp=(r.state.value.frames[9] as PageFrame.Ready).stamp
            r.transitionReady(t.ticket,9,stamp);r.flush();runCurrent();assertTrue(writes.isEmpty())
            r.transitionOffset(t.ticket,-1f);r.finishTransition(t.ticket);r.flush();runCurrent();assertTrue(writes.isEmpty())
            r.presented(r.state.value.ticket,9,stamp);r.flush();runCurrent()
            assertEquals(9,(writes.single().locator as ReadingLocator.Page).pageIndex)
        }finally{r.close();writer.close();writer.awaitClosed();runCurrent()}
    }
    @Test fun thousandFitChangesKeepTheSameBoundedFramesAndNoExtraDecodeJobs()=runTest {reader {r,doc,decoder->
        val calls=decoder.calls;val opens=doc.opened.size
        repeat(1000){r.fit(PageFit.entries[it%3]);assertTrue(r.retainedPages<=3);assertNull(r.state.value.transition)}
        runCurrent();assertEquals(calls,decoder.calls);assertEquals(opens,doc.opened.size);assertEquals(6,r.state.value.position.index)
    }}

}
