// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.test.*
import kotlinx.coroutines.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PageContinuityTest {
    private class Store : ReadingProgressStore {
        val values = mutableMapOf<ReadingProgressId, ReadingProgress>()
        val writes = mutableListOf<ReadingProgress>()
        override suspend fun get(id: ReadingProgressId) = values[id]
        override suspend fun save(progress: ReadingProgress): Boolean { values[progress.id] = progress; writes += progress; return true }
        override suspend fun remove(id: ReadingProgressId) = values.remove(id) != null
    }
    private suspend fun TestScope.reader(action: suspend (PageReaderController, TestPageDocument, Store, ProgressPersistence) -> Unit) {
        val doc = TestPageDocument(12); val store = Store()
        val writer = ProgressPersistence(store, StandardTestDispatcher(testScheduler)) { 10 }
        val r = PageReaderController(doc, backgroundScope, writer, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
        try { r.initialize(null); r.navigate(6); runCurrent(); action(r, doc, store, writer) }
        finally { r.close(); writer.close(); writer.awaitClosed(); runCurrent() }
        assertEquals(1, doc.closes); assertEquals(doc.opened.size, doc.closedHandles)
    }
    @Test fun singleDoubleSingleRestoresTheExactSecondLogicalPage() = runTest {
        val doc = TestPageDocument(8)
        val r = PageReaderController(doc, this, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
        try {
            r.initialize(null); r.navigate(6); runCurrent()
            r.layout(PageLayout.DOUBLE); runCurrent(); assertEquals(5, r.state.value.position.index)
            r.layout(PageLayout.SINGLE); runCurrent()
            assertEquals(6, r.state.value.position.index, "Presentation normalization must not replace the session's exact page")
        } finally { r.close() }
    }
    @Test fun continuousFractionSurvivesTemporaryDoublePresentation() = runTest {
        val doc = TestPageDocument(8)
        val r = PageReaderController(doc, this, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
        try {
            r.initialize(null); r.mode(PageReadingMode.VERTICAL); r.report(r.state.value.ticket, 6, .4); runCurrent()
            r.layout(PageLayout.DOUBLE); r.mode(PageReadingMode.PAGED_RTL); runCurrent()
            assertEquals(PagePosition(5), r.state.value.position)
            r.mode(PageReadingMode.WEBTOON); runCurrent()
            assertEquals(PagePosition(6, .4), r.state.value.position)
        } finally { r.close() }
    }
    @Test fun allModeDirectionsAndRapidAlternationPreservePageAndRejectObsoleteReports() = runTest { reader { r, _, store, _ ->
        r.report(r.state.value.ticket, 6, .35); runCurrent()
        repeat(40) {
            for (mode in PageReadingMode.entries) {
                val old = r.state.value.ticket; r.mode(mode)
                if (old != r.state.value.ticket) r.report(old, 0, 0.0)
                assertEquals(PagePosition(6, .35), r.state.value.position)
                runCurrent(); assertTrue(r.retainedPages <= 3)
            }
        }
        assertTrue(store.writes.isEmpty()) // Restoration/reflow alone is not presentation evidence.
    } }
    @Test fun alternatingLayoutAndDirectionKeepsContainingSpreadAndOriginalFraction() = runTest { reader { r, _, _, _ ->
        r.report(r.state.value.ticket, 6, .4)
        repeat(30) {
            r.layout(PageLayout.DOUBLE)
            for (mode in listOf(PageReadingMode.PAGED_RTL, PageReadingMode.PAGED_LTR)) {
                r.mode(mode); runCurrent(); assertEquals(PagePosition(5), r.state.value.position)
                assertEquals(listOf(5, 6), r.spread().indices); assertTrue(r.retainedPages <= 4)
            }
            r.layout(PageLayout.SINGLE); assertEquals(PagePosition(6, .4), r.state.value.position)
        }
    } }
    @Test fun genuineSpreadNavigationReplacesTemporaryExactPageHint() = runTest { reader { r, _, _, _ ->
        r.layout(PageLayout.DOUBLE); runCurrent(); assertEquals(5, r.state.value.position.index)
        r.next(); runCurrent(); r.settleSpread(); assertEquals(7, r.state.value.position.index)
        r.layout(PageLayout.SINGLE); assertEquals(7, r.state.value.position.index)
        r.layout(PageLayout.DOUBLE); r.previous(); runCurrent(); r.settleSpread()
        r.mode(PageReadingMode.VERTICAL); assertEquals(5, r.state.value.position.index)
    } }
    @Test fun resizeAndConfigurationDuringEveryTransitionPhaseNeverCommitItsTarget() = runTest {
        for (phase in PageTransitionPhase.entries) for (configuration in 0..3) reader { r, _, store, _ ->
            r.layout(PageLayout.DOUBLE); runCurrent()
            val ticket = assertNotNull(r.beginEdgeDrag(r.spreadStamps()[5])); r.drag(ticket, -.4f)
            if (phase == PageTransitionPhase.WAITING || phase == PageTransitionPhase.SETTLING) r.releaseDrag(ticket, 0f)
            if (phase == PageTransitionPhase.SETTLING) { runCurrent(); r.transitionReady(ticket, 7, r.spreadStamps(7)); r.transitionOffset(ticket, -.6f) }
            if (phase == PageTransitionPhase.RETURNING) r.returnTransition(ticket)
            assertEquals(phase, r.state.value.transition?.phase)
            runCurrent(); val obsoleteTarget = r.spreadStamps(7)
            when (configuration) { 0 -> r.presentationChanged(); 1 -> r.mode(PageReadingMode.PAGED_RTL); 2 -> r.mode(PageReadingMode.VERTICAL); 3 -> r.layout(PageLayout.SINGLE) }
            r.transitionReady(ticket, 7, obsoleteTarget); r.transitionOffset(ticket, -1f); r.finishTransition(ticket)
            r.report(ticket, 0, 0.0); r.presented(ticket, 7, emptyMap()); r.flush(); runCurrent()
            assertNull(r.state.value.transition); assertEquals(if (configuration <= 1) 5 else 6, r.state.value.position.index)
            assertTrue(store.writes.isEmpty()); r.layout(PageLayout.SINGLE); assertEquals(6, r.state.value.position.index)
        }
    }
    @Test fun configurationDuringTargetLoadingRetiresWorkWithoutChangingSourceOrProgress() = runTest { reader { r, doc, store, _ ->
        r.presented(r.state.value.ticket, 6, r.spreadStamps()); r.flush(); runCurrent()
        val baseline = store.writes.toList(); r.layout(PageLayout.DOUBLE); runCurrent()
        val gate = CompletableDeferred<Unit>(); doc.readAction = { gate.await() }
        r.next(); r.next(); runCurrent(); val old = assertNotNull(r.state.value.transition)
        assertEquals(9, old.target); assertTrue(r.state.value.frames[9] is PageFrame.Loading)
        r.layout(PageLayout.SINGLE); r.presentationChanged(); r.mode(PageReadingMode.VERTICAL)
        gate.complete(Unit); runCurrent(); r.finishTransition(old.ticket)
        assertEquals(6, r.state.value.position.index); assertNull(r.state.value.transition)
        r.flush(); runCurrent(); assertEquals(baseline, store.writes)
        assertTrue(r.retainedPages <= 3)
    } }
    @Test fun changingPresentationSavesOnlyValidatedLogicalAnchorsAndReopensAtDurablePosition() = runTest { reader { r, doc, store, writer ->
        r.presented(r.state.value.ticket, 6, r.spreadStamps()); r.flush(); runCurrent()
        r.layout(PageLayout.DOUBLE); runCurrent()
        r.presented(r.state.value.ticket, 5, r.spreadStamps()); r.flush(); runCurrent()
        assertEquals(listOf(6, 5), store.writes.map { (it.locator as ReadingLocator.Page).pageIndex })
        val reopenedDoc = TestPageDocument(12)
        val other = PageReaderController(reopenedDoc, backgroundScope, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
        try { other.initialize(writer.get(doc.progressId)); other.layout(PageLayout.DOUBLE); runCurrent(); assertEquals(5, other.state.value.position.index) }
        finally { other.close() }
        r.layout(PageLayout.SINGLE); runCurrent(); assertEquals(6, r.state.value.position.index)
        r.presented(r.state.value.ticket, 6, r.spreadStamps()); r.close(); runCurrent()
        assertEquals(6, (assertNotNull(store.values[doc.progressId]).locator as ReadingLocator.Page).pageIndex)
        assertTrue(store.writes.none { (it.locator as ReadingLocator.Page).pageIndex == 0 })
    } }
    @Test fun restoreKeyPrecedenceClampedFallbackLastPageAndOtherPublicationIsolation() = runTest {
        for ((key, index, expected) in listOf(Triple("page-6", 0, 6), Triple("old-key", 6, 6), Triple("old-key", 999, 11), Triple("page-11", 11, 11))) {
            val doc = TestPageDocument(12)
            val saved = ReadingProgress(doc.progressId, ReadingLocator.Page(key, index, .4), .5, 1)
            val r = PageReaderController(doc, backgroundScope, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
            try {
                r.initialize(saved); r.layout(PageLayout.DOUBLE); runCurrent()
                assertTrue(expected in r.spread().indices); r.layout(PageLayout.SINGLE)
                assertEquals(PagePosition(expected, .4), r.state.value.position)
                r.presentationChanged(); assertEquals(expected, r.state.value.position.index)
            } finally { r.close() }
        }
        val doc = TestPageDocument(12, publicationId = PublicationId(SourceId("test"), "another"))
        val r = PageReaderController(doc, backgroundScope, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
        try { r.initialize(ReadingProgress(TestPageDocument(12).progressId, ReadingLocator.Page("page-6", 6, 0.0), .5, 1)); assertEquals(0, r.state.value.position.index) }
        finally { r.close() }
    }
}
