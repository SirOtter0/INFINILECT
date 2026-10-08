// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PageConsecutiveTransitionTest {
    private suspend fun TestScope.reader(layout: PageLayout = PageLayout.DOUBLE,
        mode: PageReadingMode = PageReadingMode.PAGED_LTR,
        action: suspend (PageReaderController, TestPageDocument, TestRasterDecoder, MutableList<ReadingProgress>) -> Unit) {
        val saved = mutableListOf<ReadingProgress>(); val dispatcher = StandardTestDispatcher(testScheduler)
        val persistence = ProgressPersistence(object : ReadingProgressStore {
            override suspend fun get(id: ReadingProgressId) = saved.lastOrNull()
            override suspend fun save(progress: ReadingProgress): Boolean { saved += progress; return true }
            override suspend fun remove(id: ReadingProgressId) = true
        }, dispatcher) { 10 }
        val doc = TestPageDocument(8); val decoder = TestRasterDecoder()
        val r = PageReaderController(doc, this, persistence, decoder = decoder, decodeDispatcher = dispatcher)
        try {
            r.initialize(null); r.layout(layout); r.mode(mode); runCurrent(); r.navigate(3); runCurrent()
            if (r.state.value.transition != null) r.settleSpread()
            r.presented(r.state.value.ticket, 3, r.spreadStamps()); r.flush(); runCurrent()
            action(r, doc, decoder, saved)
        } finally { r.close(); persistence.close(); persistence.awaitClosed(); runCurrent() }
        assertEquals(1, doc.closes); assertEquals(doc.opened.size, doc.closedHandles)
    }
    private fun PageReaderController.grab(edge: Boolean = true): Long = assertNotNull(
        if (edge) beginEdgeDrag(spreadStamps()[state.value.position.index])
        else beginDrag(1f, spreadStamps()[state.value.position.index]))
    @Test fun secondZoomedSwipeKeepsPartlySettledIncomingOffsetThroughTouchSlop() = runTest {
        val doc = TestPageDocument(8)
        val r = PageReaderController(doc, this, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
        val clock = BroadcastFrameClock()
        suspend fun animateToRest(ticket: Long) {
            val t = assertNotNull(r.state.value.transition)
            if (t.phase == PageTransitionPhase.WAITING) r.transitionReady(ticket, t.target, r.spreadStamps(t.target))
            val active = assertNotNull(r.state.value.transition)
            val motion = launch(clock) {
                Animatable(active.offset).animateTo(if (active.phase == PageTransitionPhase.RETURNING) 0f else -1f, tween(180)) {
                    r.transitionOffset(ticket, value)
                }
                r.finishTransition(ticket)
            }
            runCurrent(); clock.sendFrame(testScheduler.currentTime * 1_000_000); runCurrent()
            repeat(13) { advanceTimeBy(16); clock.sendFrame(testScheduler.currentTime * 1_000_000); runCurrent() }
            motion.join()
        }
        fun record(label: String, edgeTicket: Long) {
            val s = r.state.value; val t = assertNotNull(s.transition)
            println("$label position=${s.position.index} from=${t.from} target=${t.target} phase=${t.phase} offset=${t.offset} ticket=${t.ticket} edgeTicket=$edgeTicket side=${pageIncomingSide(t.from, t.target, s.settings.mode)}")
        }
        try {
            r.initialize(null); r.layout(PageLayout.DOUBLE); runCurrent(); r.navigate(3); runCurrent(); r.settleSpread()
            val first = assertNotNull(r.beginEdgeDrag(r.spreadStamps()[3]))
            r.drag(first, -.3f); r.releaseDrag(first, 0f); runCurrent()
            r.transitionReady(first, 5, r.spreadStamps(5))
            assertEquals(PageTransitionPhase.SETTLING, r.state.value.transition?.phase)
            val motion = launch(clock) {
                Animatable(-.3f).animateTo(-1f, tween(180)) { r.transitionOffset(first, value) }
                r.finishTransition(first)
            }
            runCurrent(); clock.sendFrame(0); runCurrent()
            repeat(3) { advanceTimeBy(16); clock.sendFrame(testScheduler.currentTime * 1_000_000); runCurrent() }
            val old = assertNotNull(r.state.value.transition)
            assertTrue(old.offset < -.3f && old.offset > -1f)
            record("partly settled", first)
            val second = assertNotNull(r.beginEdgeDrag(r.spreadStamps()[3]))
            motion.cancelAndJoin() // The UI effect retires the old animation on ticket/phase change.
            val edge = PageEdgePanGesture(18f, 640f).also { it.overscroll = old.offset * 640 }
            val fit = fitPageSpread(r.spread().indices.map { doc.pages[it].dimensions }, Size(640f, 420f), 2f)
            val bounds = pagePanBounds(fit.size, Size(640f, 420f), 2f)
            var pan = Offset(-bounds.x, 0f)
            record("second down", second)
            // A realistic touch trace begins with 2px at +8ms, before crossing touch slop.
            advanceTimeBy(8); pan = edge.move(pan, Offset(-2f, 0f), bounds)
            r.drag(second, edge.overscroll / 640 - assertNotNull(r.state.value.transition).offset)
            val afterSmallMove = assertNotNull(r.state.value.transition)
            record("second move 2px", second)
            advanceTimeBy(8); edge.move(pan, Offset(-24f, 0f), bounds)
            r.drag(second, edge.overscroll / 640 - assertNotNull(r.state.value.transition).offset)
            r.releaseDrag(second, 0f); record("second up", second)
            assertEquals(old.offset - 2f / 640, afterSmallMove.offset, .0001f, "Touch slop must not erase an inherited incoming offset")
            assertEquals(5, afterSmallMove.target)
            assertEquals(PageTransitionPhase.WAITING, r.state.value.transition?.phase)
            animateToRest(second); assertEquals(5, r.state.value.position.index)
        } finally { r.close(); runCurrent() }
    }
    @Test fun sameDirectionSettlingGrabKeepsSourceTargetOffsetAndPresentationOnlyProgress() = runTest {
        for (layout in PageLayout.entries) for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL))
            for (next in listOf(false, true)) for (edge in listOf(false, true)) reader(layout, mode) { r, _, _, saved ->
                val target = 3 + (if (next) 1 else -1) * (if (layout == PageLayout.DOUBLE) 2 else 1)
                val sign = -pageIncomingSide(3, target, mode).toFloat()
                val first = r.grab(edge); r.drag(first, sign * .3f); r.releaseDrag(first, 0f); runCurrent()
                r.transitionReady(first, target, r.spreadStamps(target)); r.transitionOffset(first, sign * .5f)
                val old = assertNotNull(r.state.value.transition); val second = r.grab(edge)
                assertEquals(old.offset, r.state.value.transition?.offset); assertEquals(target, r.state.value.transition?.target)
                r.drag(second, sign * .02f); r.releaseDrag(second, 0f)
                r.returnTransition(first); r.transitionOffset(first, sign); r.finishTransition(first)
                assertEquals(second, r.state.value.transition?.ticket); assertEquals(3, r.state.value.position.index)
                r.flush(); runCurrent(); assertEquals(1, saved.size)
                r.settleSpread(); r.flush(); runCurrent(); assertEquals(1, saved.size)
                r.presented(r.state.value.ticket, target, emptyMap()); r.flush(); runCurrent(); assertEquals(1, saved.size)
                r.presented(r.state.value.ticket, target, r.spreadStamps()); r.flush(); runCurrent(); assertEquals(2, saved.size)
                assertEquals(target, (saved.last().locator as ReadingLocator.Page).pageIndex)
                val immediate = r.grab(edge); assertEquals(target, r.state.value.transition?.from)
                r.drag(immediate, -pageIncomingSide(target, 7, mode) * .3f)
                assertEquals(PageTransitionPhase.DRAGGING, r.state.value.transition?.phase)
                r.returnTransition(immediate)
            }
    }
    @Test fun allActivePhasesRetainIdentityAndNoIntentResumesOnlyAcceptedMotion() = runTest {
        for (phase in PageTransitionPhase.entries) reader { r, _, _, saved ->
            if (phase == PageTransitionPhase.WAITING) r.next()
            else {
                val first = r.grab(); r.drag(first, -.3f)
                if (phase == PageTransitionPhase.RETURNING) r.returnTransition(first, failed = true)
                if (phase == PageTransitionPhase.SETTLING) { r.releaseDrag(first, 0f); runCurrent(); r.transitionReady(first, 5, r.spreadStamps(5)) }
            }
            val old = assertNotNull(r.state.value.transition); assertEquals(phase, old.phase)
            val next = r.grab(); val grabbed = assertNotNull(r.state.value.transition)
            assertEquals(old.offset, grabbed.offset); assertEquals(old.target, grabbed.target)
            r.drag(next, 0f); assertEquals(grabbed, r.state.value.transition)
            r.resumeDrag(next)
            assertEquals(if (phase in listOf(PageTransitionPhase.WAITING, PageTransitionPhase.SETTLING)) PageTransitionPhase.WAITING else PageTransitionPhase.RETURNING,
                r.state.value.transition?.phase)
            assertEquals(phase == PageTransitionPhase.RETURNING, r.state.value.navigationFailed)
            r.resumeDrag(old.ticket); r.finishTransition(old.ticket); r.returnTransition(old.ticket)
            assertEquals(next, r.state.value.transition?.ticket)
            r.flush(); runCurrent(); assertEquals(1, saved.size)
        }
    }
    @Test fun pendingAcceptedTargetCanContinueButFreshShortSwipeStillCannotQualify() = runTest { reader { r, _, _, saved ->
        r.next(); val old = assertNotNull(r.state.value.transition); assertEquals(0f, old.offset)
        val t = r.grab(); assertEquals(5, r.state.value.transition?.target)
        r.drag(t, -.02f); r.releaseDrag(t, 0f)
        assertEquals(PageTransitionPhase.WAITING, r.state.value.transition?.phase)
        r.returnTransition(t); r.settleSpread()
        val fresh = r.grab(); r.drag(fresh, -.02f); r.releaseDrag(fresh, -10f)
        assertEquals(PageTransitionPhase.RETURNING, r.state.value.transition?.phase)
        r.settleSpread(); r.flush(); runCurrent(); assertEquals(3, r.state.value.position.index); assertEquals(1, saved.size)
    } }
    @Test fun oppositeDragUnwindsSameOffsetAndCrossingOriginSelectsOppositeNeighbor() = runTest {
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) for (cross in listOf(false, true)) reader(mode = mode) { r, _, _, saved ->
            val sign = -pageIncomingSide(3, 5, mode).toFloat()
            val first = r.grab(); r.drag(first, sign * .5f); r.releaseDrag(first, 0f); runCurrent()
            r.transitionReady(first, 5, r.spreadStamps(5))
            val second = r.grab(); r.drag(second, -sign * if (cross) .85f else .45f); r.releaseDrag(second, -sign)
            assertEquals(if (cross) 1 else 5, r.state.value.transition?.target)
            assertEquals(if (cross) PageTransitionPhase.WAITING else PageTransitionPhase.RETURNING, r.state.value.transition?.phase)
            runCurrent(); r.settleSpread(); r.flush(); runCurrent()
            assertEquals(if (cross) 1 else 3, r.state.value.position.index); assertEquals(1, saved.size)
        }
    }
    @Test fun repeatedGrabsKeepOneFlatContinuationAndBoundedResourcesWithoutQueue() = runTest { reader { r, _, _, saved ->
        val first = r.grab(); r.drag(first, -.4f); r.releaseDrag(first, 0f); runCurrent()
        r.transitionReady(first, 5, r.spreadStamps(5)); val next = r.grab()
        val continuation = assertNotNull(r.state.value.transition?.continuation)
        var ticket = next
        repeat(1000) {
            val old = ticket; ticket = r.grab(); r.drag(ticket, -.001f)
            assertSame(continuation, r.state.value.transition?.continuation)
            r.finishTransition(old); r.returnTransition(old); r.resumeDrag(old)
            assertEquals(ticket, r.state.value.transition?.ticket); assertTrue(r.retainedPages <= 4); assertTrue(r.state.value.frames.size <= 4)
        }
        r.flush(); runCurrent(); assertEquals(1, saved.size); assertEquals(3, r.state.value.position.index)
        r.releaseDrag(ticket, 0f); runCurrent(); r.settleSpread(); assertEquals(5, r.state.value.position.index)
    } }
    @Test fun failedInterruptedTargetCannotCommitEitherHalfOrProgress() = runTest {
        for (bad in listOf("page-5", "page-6")) reader { r, doc, decoder, saved ->
            decoder.action = { if (doc.opened.last() == bad) error("Controlled failure") }
            val first = r.grab(); r.drag(first, -.3f); r.releaseDrag(first, 0f)
            val second = r.grab(); r.drag(second, -.02f); r.releaseDrag(second, 0f); runCurrent()
            val partial = r.state.value.frames.filterValues { it is PageFrame.Ready }.mapValues { (it.value as PageFrame.Ready).stamp }.filterKeys { it in listOf(5, 6) }
            r.transitionReady(second, 5, partial); r.finishTransition(second)
            assertEquals(3, r.state.value.position.index)
            r.returnTransition(second, failed = true); r.settleSpread(); r.flush(); runCurrent()
            assertEquals(1, saved.size); assertTrue(r.state.value.navigationFailed)
        }
    }
    @Test fun closeResizeAndModeChangesRetireInterruptedTicketsWithoutProgress() = runTest {
        for (retire in 0..3) reader { r, _, _, saved ->
            val first = r.grab(); r.drag(first, -.3f); r.releaseDrag(first, 0f); runCurrent()
            r.transitionReady(first, 5, r.spreadStamps(5)); r.transitionOffset(first, -.5f)
            val second = r.grab(); r.drag(second, -.02f)
            when (retire) { 0 -> r.close(); 1 -> r.presentationChanged(); 2 -> r.mode(PageReadingMode.PAGED_RTL); 3 -> r.layout(PageLayout.SINGLE) }
            r.releaseDrag(second, -10f); r.resumeDrag(second); r.returnTransition(second)
            r.transitionOffset(first, -1f); r.finishTransition(first); r.finishTransition(second)
            r.presented(second, 5, emptyMap()); r.flush(); runCurrent()
            assertNull(r.state.value.transition); assertEquals(3, r.state.value.position.index); assertEquals(1, saved.size)
        }
    }
}
