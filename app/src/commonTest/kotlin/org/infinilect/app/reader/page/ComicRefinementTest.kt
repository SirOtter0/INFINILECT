// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.test.*
import kotlinx.coroutines.*
import androidx.compose.ui.geometry.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComicRefinementTest {
    private suspend fun TestScope.reader(count:Int=40, action:suspend (PageReaderController,TestPageDocument,TestRasterDecoder)->Unit) {
        val doc=TestPageDocument(count);val decoder=TestRasterDecoder()
        val r=PageReaderController(doc,backgroundScope,decoder=decoder,decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {r.initialize(null);runCurrent();action(r,doc,decoder)} finally {r.close();runCurrent()}
        assertEquals(1,doc.closes);assertEquals(doc.opened.size,doc.closedHandles)
    }
    private fun stamps(r:PageReaderController,index:Int)=r.spread(index).indices.associateWith{(r.state.value.frames[it] as PageFrame.Ready).stamp}
    private fun TestScope.settle(r:PageReaderController):Int {
        runCurrent();val t=assertNotNull(r.state.value.transition)
        val s=stamps(r,t.target);r.transitionReady(t.ticket,t.target,s)
        r.transitionOffset(t.ticket,-pageIncomingSide(t.from,t.target,r.state.value.settings.mode).toFloat());r.finishTransition(t.ticket)
        r.presented(r.state.value.ticket,t.target,s);runCurrent();return t.target
    }
    @Test fun sevenRapidTapsDoNotReplaceAnAcceptedIncomingPage()=runTest {
        val r=PageReaderController(TestPageDocument(20),backgroundScope,decoder=TestRasterDecoder(),decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {
            r.initialize(null);runCurrent();r.tap(.9f);runCurrent()
            val first=assertNotNull(r.state.value.transition)
            val stamp=(r.state.value.frames[1] as PageFrame.Ready).stamp
            r.transitionReady(first.ticket,1,stamp);r.transitionOffset(first.ticket,-.35f)
            repeat(6){r.tap(.9f)}
            assertEquals(first.ticket,r.state.value.transition?.ticket)
            assertEquals(1,r.state.value.transition?.target)
            assertEquals(-.35f,r.state.value.transition?.offset)
            assertEquals(0,r.state.value.position.index)
        } finally {r.close();runCurrent()}
    }
    @Test fun coalescedTapBatchPresentsFirstThenOnlyOneFollowUp()=runTest {reader {r,_,_->
        repeat(7){r.tap(.9f)};assertEquals(1,r.state.value.tapIntent)
        assertEquals(1,settle(r));assertEquals(2,r.state.value.transition?.target)
        assertEquals(2,settle(r));assertNull(r.state.value.transition);assertNull(r.state.value.tapIntent)
    }}
    @Test fun tapLatchIsBoundedInBothDirectionsAndBothLayouts()=runTest {
        for(mode in listOf(PageReadingMode.PAGED_LTR,PageReadingMode.PAGED_RTL))for(layout in PageLayout.entries)for(direction in listOf(-1,1))reader {r,_,_->
            r.navigate(6);r.mode(mode);r.layout(layout);runCurrent()
            val initial=r.state.value.position.index
            val fraction=if((direction==1)==(mode==PageReadingMode.PAGED_LTR)) .9f else .1f
            r.tap(fraction);val t=assertNotNull(r.state.value.transition)
            repeat(1000){r.tap(fraction);assertEquals(t.ticket,r.state.value.transition?.ticket);assertTrue(r.retainedPages<=4)}
            assertEquals(initial+direction*(if(layout==PageLayout.DOUBLE)2 else 1),settle(r))
            settle(r);assertNull(r.state.value.transition)
        }
    }
    @Test fun opposingTapReplacesOnlyTheSinglePendingDirection()=runTest {reader {r,_,_->
        r.tap(.9f);r.tap(.9f);r.tap(.1f);assertEquals(-1,r.state.value.tapIntent)
        assertEquals(1,settle(r));assertEquals(0,r.state.value.transition?.target);assertEquals(0,settle(r))
    }}
    @Test fun dragFitResizeSeekAndCloseRetireTheTapLatch()=runTest {
        for(action in listOf<(PageReaderController)->Unit>({it.fit(PageFit.WIDTH)},{it.presentationChanged()},{it.seek(10)},
            {it.beginDrag(1f,(it.state.value.frames[it.state.value.position.index] as PageFrame.Ready).stamp)},{it.close()}))reader {r,_,_->
            r.tap(.9f);r.tap(.9f);val old=r.state.value.transition!!.ticket;action(r)
            assertNull(r.state.value.tapIntent);r.finishTransition(old);assertEquals(0,r.state.value.position.index)
        }
    }
    @Test fun failedTurnDropsQueuedRetryAndOldCompletion()=runTest {reader {r,_,_->
        r.tap(.9f);r.tap(.9f);val t=r.state.value.transition!!;r.returnTransition(t.ticket,failed=true)
        assertNull(r.state.value.tapIntent);r.transitionOffset(t.ticket,0f);r.finishTransition(t.ticket)
        assertEquals(0,r.state.value.position.index);assertTrue(r.state.value.navigationFailed)
    }}
    @Test fun thousandSliderUpdatesDecodeOnlyTheLatestBoundedTarget()=runTest {reader(512) {r,doc,decoder->
        val opens=doc.opened.size;val calls=decoder.calls
        repeat(1000){r.previewPage(it%512);assertEquals(0,r.state.value.position.index);assertTrue(r.state.value.frames.size<=3)}
        runCurrent();assertEquals(opens,doc.opened.size);advanceTimeBy(PAGE_SCRUB_INTERVAL_MILLIS);runCurrent()
        assertEquals(487,r.state.value.scrub?.requested);assertEquals(setOf(0,487),r.state.value.frames.keys)
        assertEquals(calls+1,decoder.calls);assertEquals(opens+1,doc.opened.size)
    }}
    @Test fun duplicateRoundedSliderTargetsDoNotRestartOrDecode()=runTest {reader {r,doc,_->
        r.previewPage(20);advanceTimeBy(80);runCurrent();val ticket=r.state.value.ticket;val opens=doc.opened.size
        repeat(1000){r.previewPage(20)};advanceTimeBy(160);runCurrent()
        assertEquals(ticket,r.state.value.ticket);assertEquals(opens,doc.opened.size)
    }}
    @Test fun readyPreviewDoesNotChangeCommittedPositionAndNewIntentRejectsOldCallback()=runTest {reader {r,_,_->
        r.previewPage(20);advanceTimeBy(80);runCurrent();val ticket=r.state.value.ticket;val old=stamps(r,20)
        r.previewReady(ticket,20,old);assertEquals(20,r.state.value.scrub?.displayed);assertEquals(0,r.state.value.position.index)
        r.previewPage(30);r.previewReady(ticket,20,old);r.previewPresented(ticket,20,old)
        assertEquals(0,r.state.value.position.index);assertEquals(30,r.state.value.scrub?.requested)
        advanceTimeBy(80);runCurrent();assertEquals(setOf(20,30),r.state.value.frames.keys)
    }}
    @Test fun releaseStillRequiresCompleteConvertedAndPresentedFinalTarget()=runTest {reader {r,_,_->
        r.previewPage(20);advanceTimeBy(80);runCurrent();val before=r.state.value.ticket;val s=stamps(r,20)
        r.previewReady(before,20,s);r.previewPresented(before,20,s);assertEquals(0,r.state.value.position.index)
        r.releasePreview();r.previewPresented(before,20,s);assertEquals(0,r.state.value.position.index)
        r.previewReady(r.state.value.ticket,20,s);r.previewPresented(r.state.value.ticket,20,s)
        assertEquals(20,r.state.value.position.index);assertNull(r.state.value.scrub)
    }}
    @Test fun doublePreviewRetainsFourAndRestoresTheExactRequestedSinglePage()=runTest {reader {r,_,_->
        r.layout(PageLayout.DOUBLE);runCurrent();r.previewPage(4);advanceTimeBy(80);runCurrent()
        assertTrue(r.state.value.frames.size<=4);val s=stamps(r,3)
        r.previewReady(r.state.value.ticket,3,s);assertEquals(0,r.state.value.position.index)
        r.releasePreview();r.previewReady(r.state.value.ticket,3,s);r.previewPresented(r.state.value.ticket,3,s)
        assertEquals(3,r.state.value.position.index);r.layout(PageLayout.SINGLE);assertEquals(4,r.state.value.position.index)
    }}
    @Test fun partialOrFailedPairCannotCommitPreview()=runTest {reader {r,_,_->
        r.layout(PageLayout.DOUBLE);runCurrent();r.previewPage(10);r.releasePreview();runCurrent()
        val s=stamps(r,9);r.previewReady(r.state.value.ticket,9,mapOf(9 to s.getValue(9)))
        r.previewPresented(r.state.value.ticket,9,mapOf(9 to s.getValue(9)));assertEquals(0,r.state.value.position.index)
        r.cancelScrub(failed=true);assertTrue(r.state.value.navigationFailed);assertNull(r.state.value.scrub)
    }}
    @Test fun cancellationRestoresOriginalFractionAndRetiresAllPreviewCallbacks()=runTest {reader {r,_,_->
        r.mode(PageReadingMode.VERTICAL);r.report(r.state.value.ticket,6,.4);r.mode(PageReadingMode.PAGED_LTR);runCurrent()
        val original=r.state.value.position;r.previewPage(20);advanceTimeBy(80);runCurrent();val ticket=r.state.value.ticket;val s=stamps(r,20)
        r.previewReady(ticket,20,s);r.cancelScrub();runCurrent();r.previewPresented(ticket,20,s)
        assertEquals(original,r.state.value.position);assertNull(r.state.value.scrub)
    }}
    @Test fun modeFitResizeCloseAndExplicitTurnRetirePreview()=runTest {
        for(action in listOf<(PageReaderController)->Unit>({it.mode(PageReadingMode.PAGED_RTL)},{it.layout(PageLayout.DOUBLE)},
            {it.fit(PageFit.HEIGHT)},{it.presentationChanged()},{it.next()},{it.close()}))reader {r,_,_->
            r.previewPage(20);advanceTimeBy(80);runCurrent();val ticket=r.state.value.ticket;val s=stamps(r,20)
            action(r);assertNull(r.state.value.scrub);r.previewReady(ticket,20,s);r.previewPresented(ticket,20,s)
            assertEquals(0,r.state.value.position.index)
        }
    }
    @Test fun failedPreviewPreservesOriginalPositionAndClosesHandles()=runTest {reader {r,doc,_->
        doc.bytes=byteArrayOf(1,2,3);doc.declared=3;r.previewPage(20);advanceTimeBy(80);runCurrent()
        assertIs<PageFrame.Unavailable>(r.state.value.frames[20]);r.cancelScrub(failed=true);runCurrent()
        assertEquals(0,r.state.value.position.index);assertTrue(r.state.value.navigationFailed)
    }}
    @Test fun nonCooperativePreviewDecodeCannotReplaceTheLatestTarget()=runTest {reader {r,_,decoder->
        val gate=CompletableDeferred<Unit>();decoder.action={withContext(NonCancellable){gate.await()}}
        r.previewPage(20);advanceTimeBy(80);runCurrent();val first=decoder.calls
        r.previewPage(30);advanceTimeBy(80);runCurrent();assertEquals(first,decoder.calls)
        gate.complete(Unit);runCurrent();assertEquals(setOf(0,30),r.state.value.frames.keys);assertIs<PageFrame.Ready>(r.state.value.frames[30])
    }}
    @Test fun continuousSliderKeepsScrollIdentityUntilReleaseAndRetainsPreferences()=runTest {reader {r,doc,_->
        r.mode(PageReadingMode.WEBTOON);runCurrent();val opens=doc.opened.size
        repeat(100){r.previewPage(20)};advanceTimeBy(1000);runCurrent()
        assertEquals(opens,doc.opened.size);assertEquals(0,r.state.value.position.index)
        r.releasePreview();runCurrent();assertEquals(20,r.state.value.position.index);assertNull(r.state.value.scrub)
    }}
    @Test fun onlyTheReleasedPresentedPreviewCanWriteProgress()=runTest {
        val doc=TestPageDocument(40);val writes=mutableListOf<ReadingProgress>()
        val store=object:ReadingProgressStore {override suspend fun get(id:ReadingProgressId):ReadingProgress?=null;override suspend fun save(progress:ReadingProgress):Boolean{writes+=progress;return true};override suspend fun remove(id:ReadingProgressId)=false}
        val writer=org.infinilect.app.progress.ProgressPersistence(store,StandardTestDispatcher(testScheduler)){42}
        val r=PageReaderController(doc,backgroundScope,writer,decoder=TestRasterDecoder(),decodeDispatcher=StandardTestDispatcher(testScheduler))
        try {
            r.initialize(null);runCurrent();r.previewPage(20);advanceTimeBy(80);runCurrent();val s=stamps(r,20)
            r.previewReady(r.state.value.ticket,20,s);r.presented(r.state.value.ticket,20,s);r.flush();runCurrent();assertTrue(writes.isEmpty())
            r.releasePreview();r.previewPresented(r.state.value.ticket,20,s);r.flush();runCurrent();assertTrue(writes.isEmpty())
            r.presented(r.state.value.ticket,20,s);r.flush();runCurrent();assertEquals(20,(writes.single().locator as ReadingLocator.Page).pageIndex)
        } finally {r.close();writer.close();writer.awaitClosed();runCurrent()}
    }
    @Test fun repeatedPreviewReleaseAndCancelDoNotAccumulateResources()=runTest {reader(512) {r,_,_->
        repeat(200){n->r.previewPage(20+n);advanceTimeBy(80);runCurrent();val target=20+n;val s=stamps(r,target)
            r.previewReady(r.state.value.ticket,target,s);if(n%2==0)r.cancelScrub()else{r.releasePreview();r.previewPresented(r.state.value.ticket,target,s)}
            runCurrent();assertTrue(r.retainedPages<=3);assertTrue(r.state.value.frames.size<=3)
        }
    }}
    @Test fun lateralZoomRequiresExplicitHoldButBriefNavigationDoesNotWait() {
        for(action in listOf(PageTapAction.NEXT,PageTapAction.PREVIOUS)) {
            assertFalse(comicZoomTapIntent(action,1f,10));assertFalse(comicZoomTapIntent(action,1f,119))
            assertTrue(comicZoomTapIntent(action,1f,120));assertTrue(comicZoomTapIntent(action,2.5f,10))
        }
        assertTrue(comicZoomTapIntent(PageTapAction.CONTROLS,1f,10))
    }
    @Test fun artworkHitTestingIncludesEdgesAndExcludesLetterboxingWithRealPan() {
        val content=Size(390f,585f);val viewport=Size(390f,780f)
        assertTrue(comicArtworkContains(Offset(0f,97.5f),content,viewport,1f,Offset.Zero))
        assertTrue(comicArtworkContains(Offset(390f,682.5f),content,viewport,1f,Offset.Zero))
        assertFalse(comicArtworkContains(Offset(20f,90f),content,viewport,1f,Offset.Zero))
        assertTrue(comicArtworkContains(Offset(20f,30f),content,viewport,2.5f,Offset(100f,-100f)))
    }
    @Test fun matchingSecondTapDoesNotNeedAnotherHoldAndExpiredCandidatesDoNotMatch() {
        val taps=ComicDoubleTap();assertFalse(taps.tap(Offset(20f,20f),0,130,40,300,30f))
        assertTrue(taps.matches(Offset(21f,20f),230,40,300,30f))
        assertTrue(taps.tap(Offset(21f,20f),230,240,40,300,30f))
        assertFalse(taps.matches(Offset(21f,20f),300,40,300,30f))
        taps.tap(Offset.Zero,400,530,40,300,30f)
        assertFalse(taps.matches(Offset.Zero,831,40,300,30f));taps.clear()
        assertFalse(taps.matches(Offset.Zero,600,40,300,30f))
    }
    @Test fun doubleTapUsesUpToDownTimingAndRequiresNearbySinglePointers() {
        val taps=ComicDoubleTap();assertFalse(taps.tap(Offset(100f,100f),0,20,40,300,30f))
        assertTrue(taps.tap(Offset(110f,105f),100,110,40,300,30f))
        assertFalse(taps.tap(Offset.Zero,200,210,40,300,30f));taps.clear()
        assertFalse(taps.tap(Offset.Zero,250,260,40,300,30f))
        assertFalse(taps.tap(Offset.Zero,600,610,40,300,30f))
    }
    @Test fun doubleTapRejectsBounceAndSeparatedLocations() {
        val taps=ComicDoubleTap();taps.tap(Offset.Zero,0,10,40,300,30f)
        assertFalse(taps.tap(Offset.Zero,20,30,40,300,30f));assertFalse(taps.tap(Offset(90f,0f),100,110,40,300,30f))
    }
    @Test fun doubleTapZoomKeepsRealBoundsAndSecondTapRestoresFit() {
        for(content in listOf(Size(300f,600f),Size(800f,200f),Size(900f,900f)))for(viewport in listOf(Size(390f,780f),Size(960f,540f))) {
            val at=Offset(viewport.width*.4f,viewport.height*.4f)
            val next=comicDoubleTapZoom(1f,Offset.Zero,at,content,viewport);val bounds=pagePanBounds(content,viewport,next.zoom)
            assertEquals(2.5f,next.zoom);assertTrue(kotlin.math.abs(next.pan.x)<=bounds.x&&kotlin.math.abs(next.pan.y)<=bounds.y)
            assertEquals(ComicZoom(1f,Offset.Zero),comicDoubleTapZoom(next.zoom,next.pan,at,content,viewport))
        }
    }
}
