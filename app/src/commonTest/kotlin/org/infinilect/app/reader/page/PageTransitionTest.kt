// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

/** Deterministic UI-handshake simulation; no animation clocks or platform bitmap claims. */
internal fun PageReaderController.settlePageTurn() {
    var t = assertNotNull(state.value.transition)
    if (t.phase == PageTransitionPhase.WAITING) {
        val ready = assertIs<PageFrame.Ready>(state.value.frames[t.target])
        transitionReady(t.ticket, t.target, ready.stamp)
        t = assertNotNull(state.value.transition)
    }
    assertTrue(t.phase == PageTransitionPhase.SETTLING || t.phase == PageTransitionPhase.RETURNING)
    transitionOffset(t.ticket, if (t.phase == PageTransitionPhase.SETTLING) -pageIncomingSide(t.from, t.target, state.value.settings.mode).toFloat() else 0f)
    finishTransition(t.ticket)
}

@OptIn(ExperimentalCoroutinesApi::class)
class PageTransitionTest {
    private suspend fun TestScope.reader(action: suspend (PageReaderController) -> Unit) {
        val doc = TestPageDocument(8)
        val r = PageReaderController(doc, this, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
        try { r.initialize(null); r.navigate(3); runCurrent(); action(r) }
        finally { r.close(); runCurrent() }
        assertEquals(1, doc.closes); assertEquals(doc.opened.size, doc.closedHandles)
    }
    private fun PageReaderController.dragStart(zoom: Float = 1f): Long? = beginDrag(zoom, (state.value.frames[state.value.position.index] as? PageFrame.Ready)?.stamp)
    @Test fun ltrDragTracksBothPagesThenCommitsOnlyAtRest() = runTest { reader { r ->
        val ticket = assertNotNull(r.dragStart())
        r.drag(ticket, -.4f)
        val t = assertNotNull(r.state.value.transition)
        assertEquals(3, t.from); assertEquals(4, t.target); assertEquals(-.4f, t.offset)
        assertEquals(1, pageIncomingSide(t.from, t.target, PageReadingMode.PAGED_LTR))
        assertEquals(3, r.state.value.position.index)
        r.releaseDrag(ticket, 0f); assertEquals(PageTransitionPhase.WAITING, r.state.value.transition?.phase)
        r.finishTransition(ticket); assertEquals(3, r.state.value.position.index)
        runCurrent(); r.settlePageTurn(); assertEquals(4, r.state.value.position.index); assertNull(r.state.value.transition)
        val previous = assertNotNull(r.dragStart()); r.drag(previous, .5f); r.releaseDrag(previous, 0f)
        assertEquals(3, r.state.value.transition?.target)
        assertEquals(-1, pageIncomingSide(4, 3, PageReadingMode.PAGED_LTR))
        runCurrent(); r.settlePageTurn(); assertEquals(3, r.state.value.position.index)
    } }
    @Test fun rtlDragAndAutomaticTapUseOppositeSpatialDirection() = runTest { reader { r ->
        r.mode(PageReadingMode.PAGED_RTL)
        val next = assertNotNull(r.dragStart()); r.drag(next, .4f); assertEquals(4, r.state.value.transition?.target)
        r.releaseDrag(next, 0f); runCurrent(); r.settlePageTurn(); assertEquals(4, r.state.value.position.index)
        val prev = assertNotNull(r.dragStart()); r.drag(prev, -.4f); assertEquals(3, r.state.value.transition?.target)
        r.releaseDrag(prev, 0f); runCurrent(); r.settlePageTurn()
        r.tap(.1f); val automatic = assertNotNull(r.state.value.transition)
        assertEquals(4, automatic.target); assertEquals(-1, pageIncomingSide(automatic.from, automatic.target, r.state.value.settings.mode))
        assertEquals(0f, automatic.offset); assertEquals(3, r.state.value.position.index)
    } }
    @Test fun subthresholdReverseVelocityAndCancelledDragReturnWithoutNavigation() = runTest { reader { r ->
        val ticket = assertNotNull(r.dragStart()); r.drag(ticket, -.1f); r.releaseDrag(ticket, .9f)
        assertEquals(PageTransitionPhase.RETURNING, r.state.value.transition?.phase)
        assertEquals(-.1f, r.state.value.transition?.offset)
        r.settlePageTurn(); assertEquals(3, r.state.value.position.index)
        val cancelled = assertNotNull(r.dragStart()); r.drag(cancelled, -.6f); r.returnTransition(cancelled)
        r.settlePageTurn(); assertEquals(3, r.state.value.position.index)
    } }
    @Test fun deliberateFlingRequiresMinimumMovementAndConsistentVelocity() {
        assertTrue(pageDragCompletes(-.08f, -1f)); assertFalse(pageDragCompletes(-.01f, -10f))
        assertFalse(pageDragCompletes(-.08f, 1f)); assertTrue(pageDragCompletes(.3f, 0f))
        assertFalse(pageDragCompletes(Float.NaN, 1f)); assertFalse(pageDragCompletes(.4f, Float.POSITIVE_INFINITY))
    }
    @Test fun firstLastZoomAndContinuousModesCannotStartInvalidSwipe() = runTest { reader { r ->
        assertNull(r.dragStart(2f)); assertNull(r.beginDrag(1f, -1))
        r.navigate(0); runCurrent(); val first = assertNotNull(r.dragStart()); r.drag(first, .7f)
        assertEquals(0, r.state.value.transition?.target); assertEquals(0f, r.state.value.transition?.offset)
        r.cancelTransition(); r.previous(); assertNull(r.state.value.transition)
        r.navigate(7); runCurrent(); val last = assertNotNull(r.dragStart()); r.drag(last, -.7f)
        assertEquals(7, r.state.value.transition?.target); r.cancelTransition(); r.next(); assertNull(r.state.value.transition)
        for (mode in listOf(PageReadingMode.VERTICAL, PageReadingMode.WEBTOON)) { r.mode(mode); assertNull(r.dragStart()); r.previous(); assertNull(r.state.value.transition) }
    } }
    @Test fun newNavigationRetiresOldTransitionAndCoalescesWithoutQueue() = runTest { reader { r ->
        r.next(); val old = assertNotNull(r.state.value.transition)
        val ready = assertIs<PageFrame.Ready>(r.state.value.frames[4]); r.transitionReady(old.ticket, 4, ready.stamp)
        r.transitionOffset(old.ticket, -.4f)
        r.next(); val latest = assertNotNull(r.state.value.transition)
        assertEquals(5, latest.target); assertEquals(-.4f, latest.offset); assertEquals(3, latest.from)
        r.transitionOffset(old.ticket, -1f); r.finishTransition(old.ticket); r.transitionReady(old.ticket, 4, ready.stamp)
        assertEquals(latest, r.state.value.transition); runCurrent(); assertTrue(r.retainedPages <= 3)
        r.settlePageTurn(); assertEquals(5, r.state.value.position.index); assertNull(r.state.value.transition)
        assertTrue(r.retainedPages <= 3); assertTrue(r.state.value.frames.size <= 3)
    } }
    @Test fun reverseRequestCancelsToOriginalAndCenterTapDoesNotRetireTransition() = runTest { reader { r ->
        r.next(); val next = assertNotNull(r.state.value.transition)
        r.tap(.5f); assertTrue(r.state.value.controlsVisible); assertEquals(next, r.state.value.transition)
        r.previous(); assertEquals(PageTransitionPhase.RETURNING, r.state.value.transition?.phase)
        r.settlePageTurn(); assertEquals(3, r.state.value.position.index)
    } }
    @Test fun pendingDecodeAndLateOldTargetAreBoundedAndNeverAuthoritative() = runTest {
        val gate = CompletableDeferred<Unit>(); val decoder = TestRasterDecoder()
        val doc = TestPageDocument(8); val r = PageReaderController(doc, this, decoder = decoder, decodeDispatcher = StandardTestDispatcher(testScheduler))
        try {
            r.initialize(null); runCurrent(); decoder.action = { withContext(NonCancellable) { gate.await() } }
            r.next(); r.next(); runCurrent(); val old = assertNotNull(r.state.value.transition)
            assertIs<PageFrame.Loading>(r.state.value.frames[2]); assertEquals(0, r.state.value.position.index)
            r.next(); val latest = assertNotNull(r.state.value.transition)
            gate.complete(Unit); runCurrent(); r.transitionReady(old.ticket, 2, 0)
            assertEquals(latest.ticket, r.state.value.transition?.ticket); assertEquals(3, r.state.value.transition?.target)
            assertFalse(2 in r.state.value.frames); r.settlePageTurn(); assertEquals(3, r.state.value.position.index)
        } finally { gate.complete(Unit); r.close(); runCurrent() }
    }
    @Test fun failedAndCancelledTransitionDoNotAdvanceDurableProgress() = runTest {
        val values = mutableListOf<ReadingProgress>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = object : ReadingProgressStore {
            override suspend fun get(id: ReadingProgressId) = values.lastOrNull()
            override suspend fun save(progress: ReadingProgress): Boolean { values += progress; return true }
            override suspend fun remove(id: ReadingProgressId) = true
        }
        val persistence = ProgressPersistence(store, dispatcher) { 1 }
        val r = PageReaderController(TestPageDocument(8), this, persistence, decoder = TestRasterDecoder(), decodeDispatcher = dispatcher)
        try {
            r.initialize(null); runCurrent(); r.next(); runCurrent()
            val t = assertNotNull(r.state.value.transition); val ready = assertIs<PageFrame.Ready>(r.state.value.frames[1])
            r.transitionReady(t.ticket, 1, ready.stamp); r.presented(r.state.value.ticket, 1, ready.stamp)
            r.flush(); runCurrent(); assertTrue(values.isEmpty())
            r.returnTransition(t.ticket, failed = true); r.settlePageTurn(); r.flush(); runCurrent(); assertTrue(values.isEmpty())
            r.next(); runCurrent(); r.settlePageTurn(); r.flush(); runCurrent(); assertTrue(values.isEmpty())
            r.presented(r.state.value.ticket, 1, ready.stamp); r.flush(); runCurrent()
            assertEquals(1, (values.single().locator as ReadingLocator.Page).pageIndex)
            val cancelled = assertNotNull(r.dragStart()); r.drag(cancelled, -.5f); r.cancelTransition(); r.flush(); runCurrent()
            assertEquals(1, values.size)
        } finally { r.close(); persistence.close(); persistence.awaitClosed() }
    }
    @Test fun backModeAndResizeInvalidateTransitionCallbacks() = runTest { reader { r ->
        r.next(); val old = assertNotNull(r.state.value.transition); r.presentationChanged()
        r.transitionOffset(old.ticket, -1f); r.finishTransition(old.ticket); assertNull(r.state.value.transition)
        r.next(); val mode = assertNotNull(r.state.value.transition); r.mode(PageReadingMode.PAGED_RTL)
        r.finishTransition(mode.ticket); assertEquals(3, r.state.value.position.index); assertNull(r.state.value.transition)
        r.next(); val closed = assertNotNull(r.state.value.transition); r.close()
        r.transitionReady(closed.ticket, 4, 0); r.finishTransition(closed.ticket); r.releaseDrag(closed.ticket, 2f)
        assertTrue(r.state.value.frames.isEmpty()); assertNull(r.state.value.transition); assertEquals(3, r.state.value.position.index)
    } }
}
