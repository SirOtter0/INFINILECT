// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PageZoomEdgeTransitionTest {
    private suspend fun TestScope.reader(action: suspend (PageReaderController, TestPageDocument, TestRasterDecoder, MutableList<ReadingProgress>) -> Unit) {
        val saved = mutableListOf<ReadingProgress>(); val dispatcher = StandardTestDispatcher(testScheduler)
        val store = object : ReadingProgressStore {
            override suspend fun get(id: ReadingProgressId) = saved.lastOrNull()
            override suspend fun save(progress: ReadingProgress): Boolean { saved += progress; return true }
            override suspend fun remove(id: ReadingProgressId) = true
        }
        val persistence = ProgressPersistence(store, dispatcher) { 10 }
        val doc = TestPageDocument(8); val decoder = TestRasterDecoder()
        val r = PageReaderController(doc, this, persistence, decoder = decoder, decodeDispatcher = dispatcher)
        try {
            r.initialize(null); r.layout(PageLayout.DOUBLE); runCurrent(); r.navigate(3); runCurrent(); r.settleSpread()
            r.presented(r.state.value.ticket, 3, r.spreadStamps()); r.flush(); runCurrent()
            action(r, doc, decoder, saved)
        } finally { r.close(); persistence.close(); persistence.awaitClosed(); runCurrent() }
        assertEquals(1, doc.closes); assertEquals(doc.opened.size, doc.closedHandles)
    }
    private fun PageReaderController.edge() = assertNotNull(beginEdgeDrag(spreadStamps()[state.value.position.index]))
    @Test fun edgeEntryReusesBothDirectionsAndAllSpreadPresentationGuards() = runTest { reader { r, _, _, saved ->
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) {
            r.mode(mode); runCurrent()
            val before = saved.size
            val ticket = r.edge(); val sign = -pageIncomingSide(3, 5, mode).toFloat()
            r.drag(ticket, sign * .3f); r.flush(); runCurrent()
            assertEquals(3, r.state.value.position.index); assertEquals(before, saved.size)
            r.releaseDrag(ticket, 0f); runCurrent(); val stamps = r.spreadStamps(5)
            r.transitionReady(ticket, 5, mapOf(5 to stamps.getValue(5)))
            assertEquals(PageTransitionPhase.WAITING, r.state.value.transition?.phase)
            r.transitionReady(ticket, 5, stamps); r.flush(); runCurrent(); assertEquals(before, saved.size)
            r.settleSpread(); r.flush(); runCurrent(); assertEquals(before, saved.size)
            r.presented(r.state.value.ticket, 5, r.spreadStamps()); r.flush(); runCurrent()
            assertEquals(5, (saved.last().locator as ReadingLocator.Page).pageIndex)
            val previous = r.edge(); r.drag(previous, -sign * .3f); r.releaseDrag(previous, 0f)
            runCurrent(); r.settleSpread(); assertEquals(3, r.state.value.position.index)
        }
    } }
    @Test fun edgeThresholdVelocityAndReversalUseOriginalPagerRules() = runTest { reader { r, _, _, saved ->
        val before = saved.size
        for ((offset, velocity, completes) in listOf(Triple(-.1f, 0f, false), Triple(-.08f, -1f, true), Triple(-.04f, -10f, false), Triple(-.08f, 1f, false))) {
            val t = r.edge(); r.drag(t, offset); r.releaseDrag(t, velocity); runCurrent()
            assertEquals(if (completes) PageTransitionPhase.WAITING else PageTransitionPhase.RETURNING, r.state.value.transition?.phase)
            r.settleSpread(); assertEquals(if (completes) 5 else 3, r.state.value.position.index)
            r.flush(); runCurrent(); assertEquals(before, saved.size)
            if (completes) { r.navigate(3); runCurrent(); r.settleSpread() }
        }
        val t = r.edge(); r.drag(t, -.35f); r.drag(t, .35f); r.releaseDrag(t, 0f)
        r.settleSpread(); assertEquals(3, r.state.value.position.index); assertEquals(before, saved.size)
    } }
    @Test fun eitherFailedTargetPagePreservesPriorSpreadAndProgress() = runTest {
        for (bad in listOf("page-5", "page-6")) reader { r, doc, decoder, saved ->
            decoder.action = { if (doc.opened.last() == bad) error("Controlled failure") }
            val t = r.edge(); r.drag(t, -.4f); r.releaseDrag(t, 0f); runCurrent()
            val partial = r.state.value.frames.filterValues { it is PageFrame.Ready }.mapValues { (it.value as PageFrame.Ready).stamp }.filterKeys { it in listOf(5, 6) }
            r.transitionReady(t, 5, partial); r.finishTransition(t)
            assertEquals(3, r.state.value.position.index)
            r.returnTransition(t, failed = true); r.settleSpread(); r.flush(); runCurrent()
            assertEquals(3, (saved.last().locator as ReadingLocator.Page).pageIndex); assertEquals(1, saved.size)
        }
    }
    @Test fun cancelResizeLayoutDirectionAndCloseRetireOldEdgeTickets() = runTest {
        for (retire in 0..4) reader { r, _, _, saved ->
            val t = r.edge(); r.drag(t, -.4f); r.releaseDrag(t, 0f); runCurrent(); val stamps = r.spreadStamps(5)
            when (retire) {
                0 -> r.cancelTransition() // multi-pointer arbitration also uses this path
                1 -> r.presentationChanged()
                2 -> r.layout(PageLayout.SINGLE)
                3 -> r.mode(PageReadingMode.PAGED_RTL)
                4 -> r.close()
            }
            r.transitionReady(t, 5, stamps); r.transitionOffset(t, -1f); r.finishTransition(t)
            r.presented(t, 5, stamps); r.flush(); runCurrent()
            assertEquals(3, r.state.value.position.index); assertEquals(1, saved.size)
            assertNull(r.state.value.transition)
        }
    }
    @Test fun grabbingAndRapidReplacementKeepAuthoritativeIdentityAndFourSlotBound() = runTest { reader { r, _, _, saved ->
        r.next(); r.next(); runCurrent(); val old = assertNotNull(r.state.value.transition)
        r.transitionReady(old.ticket, old.target, r.spreadStamps(old.target)); r.transitionOffset(old.ticket, -.3f)
        val grab = r.edge(); r.drag(grab, -.1f); assertEquals(old.target, r.state.value.transition?.target)
        r.finishTransition(old.ticket); assertEquals(3, r.state.value.position.index)
        repeat(100) { r.next(); r.previous(); val t = r.edge(); r.drag(t, -.2f); assertTrue(r.state.value.frames.size <= 4) }
        runCurrent(); assertTrue(r.retainedPages <= 4); r.flush(); runCurrent(); assertEquals(1, saved.size)
    } }
    @Test fun edgeEntryRejectsInvalidSourcesClosedOwnersAndContinuousModes() = runTest { reader { r, _, _, _ ->
        assertNull(r.beginEdgeDrag(-1)); assertNull(r.beginDrag(2f, r.spreadStamps()[3]))
        for (mode in listOf(PageReadingMode.VERTICAL, PageReadingMode.WEBTOON)) {
            r.mode(mode); runCurrent(); assertNull(r.beginEdgeDrag((r.state.value.frames[3] as? PageFrame.Ready)?.stamp))
        }
        r.close(); assertNull(r.beginEdgeDrag(1))
    } }
}
