// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*
import org.infinilect.app.progress.ProgressPersistence

internal fun PageReaderController.spreadStamps(index: Int = state.value.position.index) = spread(index).indices.associateWith {
    assertIs<PageFrame.Ready>(state.value.frames[it]).stamp
}
internal fun PageReaderController.settleSpread() {
    var t = assertNotNull(state.value.transition)
    if (t.phase == PageTransitionPhase.WAITING) {
        transitionReady(t.ticket, t.target, spreadStamps(t.target)); t = assertNotNull(state.value.transition)
    }
    assertTrue(t.phase == PageTransitionPhase.SETTLING || t.phase == PageTransitionPhase.RETURNING)
    transitionOffset(t.ticket, if (t.phase == PageTransitionPhase.SETTLING) -pageIncomingSide(t.from, t.target, state.value.settings.mode).toFloat() else 0f)
    finishTransition(t.ticket)
}

@OptIn(ExperimentalCoroutinesApi::class)
class PageSpreadTest {
    private val double = PageReaderSettings(layout = PageLayout.DOUBLE)
    private fun groups(wide: Set<Int> = emptySet(), count: Int = 8, settings: PageReaderSettings = double): List<List<Int>> {
        val pages = TestPageDocument(count).pages.mapIndexed { index, page ->
            page.copy(resource = page.resource.copy(pageDimensions = if (index in wide) PageDimensions(4, 2) else PageDimensions(2, 4)))
        }
        return pageSpreads(pages, settings).map { it.indices }
    }
    @Test fun singleIsDefaultAndAllExistingModesGroupIndividually() {
        assertEquals(PageLayout.SINGLE, PageReaderSettings().layout)
        for (mode in PageReadingMode.entries) assertEquals((0..7).map { listOf(it) }, groups(settings = PageReaderSettings(mode)))
        for (mode in listOf(PageReadingMode.VERTICAL, PageReadingMode.WEBTOON))
            assertEquals((0..7).map { listOf(it) }, groups(settings = double.copy(mode = mode)))
    }
    @Test fun coverEvenOddAndOnePagePublications() {
        assertEquals(listOf(listOf(0)), groups(count = 1))
        assertEquals(listOf(listOf(0), listOf(1)), groups(count = 2))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), groups(count = 4))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), groups(count = 5))
    }
    @Test fun wideCoverMiddleConsecutiveAndPairingReset() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), groups(setOf(0), 5))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)), groups(setOf(2), 5))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5, 6)), groups(setOf(1, 2), 7))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5)), groups(setOf(3), 6))
        assertEquals((0..7).map { listOf(it) }, groups((0..7).toSet()))
    }
    @Test fun groupingPartitionsEveryLogicalPageAndNeverPairsLandscape() {
        for (count in 1..32) for (mask in 0..15) {
            val wide = (0 until count).filter { mask and (1 shl (it % 4)) != 0 }.toSet()
            val grouped = groups(wide, count)
            assertEquals((0 until count).toList(), grouped.flatten())
            assertEquals(listOf(0), grouped.first())
            assertTrue(grouped.all { it.size in 1..2 && (it.size == 1 || it.none { page -> page in wide }) })
        }
    }
    @Test fun visualOrderAndHumanIndicatorDoNotReverseIdentity() {
        val spread = PageSpread(1, 2)
        assertEquals(listOf(1, 2), spread.visualOrder(PageReadingMode.PAGED_LTR))
        assertEquals(listOf(2, 1), spread.visualOrder(PageReadingMode.PAGED_RTL))
        assertEquals(1, spread.anchor); assertEquals("2–3 / 8", spread.indicator(8))
        assertEquals("8 / 8", PageSpread(7).indicator(8))
    }
    private class Store : ReadingProgressStore {
        var saved: ReadingProgress? = null
        val writes = mutableListOf<ReadingProgress>()
        override suspend fun get(id: ReadingProgressId) = saved
        override suspend fun save(progress: ReadingProgress): Boolean { saved = progress; writes += progress; return true }
        override suspend fun remove(id: ReadingProgressId) = true
    }
    private suspend fun TestScope.reader(count: Int = 8, action: suspend (PageReaderController, TestPageDocument, TestRasterDecoder, Store) -> Unit) {
        val doc = TestPageDocument(count)
        val decoder = TestRasterDecoder()
        val store = Store()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val progress = ProgressPersistence(store, dispatcher) { 20 }
        val r = PageReaderController(doc, this, progress, decoder = decoder, decodeDispatcher = dispatcher)
        try { r.initialize(null); r.layout(PageLayout.DOUBLE); runCurrent(); action(r, doc, decoder, store) }
        finally { r.close(); progress.close(); progress.awaitClosed(); runCurrent() }
        assertEquals(1, doc.closes); assertEquals(doc.opened.size, doc.closedHandles)
    }
    @Test fun bothDirectionsNavigateSpreadsAndBoundariesNotOverlappingWindows() = runTest { reader { r, _, _, _ ->
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) {
            r.mode(mode); r.previous(); assertNull(r.state.value.transition)
            for (index in listOf(1, 3, 5, 7)) { r.next(); runCurrent(); r.settleSpread(); assertEquals(index, r.state.value.position.index) }
            r.next(); assertNull(r.state.value.transition)
            for (index in listOf(5, 3, 1, 0)) { r.previous(); runCurrent(); r.settleSpread(); assertEquals(index, r.state.value.position.index) }
        }
    } }
    @Test fun sideTapsCenterControlsAndSemanticButtons() = runTest { reader { r, _, _, _ ->
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) {
            r.mode(mode); val ticket = r.state.value.ticket
            r.tap(.5f); assertTrue(r.state.value.controlsVisible); r.tap(.5f); assertFalse(r.state.value.controlsVisible)
            assertEquals(ticket, r.state.value.ticket)
            r.tap(if (mode == PageReadingMode.PAGED_LTR) .9f else .1f); runCurrent(); r.settleSpread()
            assertEquals(listOf(1, 2), r.spread().indices)
            r.next(); runCurrent(); r.settleSpread(); assertEquals(listOf(3, 4), r.spread().indices)
            r.previous(); runCurrent(); r.settleSpread(); r.tap(if (mode == PageReadingMode.PAGED_LTR) .1f else .9f)
            runCurrent(); r.settleSpread(); assertEquals(0, r.state.value.position.index)
        }
    } }
    @Test fun completeSpreadHandshakeAloneSavesItsAnchor() = runTest { reader { r, _, _, store ->
        r.presented(r.state.value.ticket, 0, r.spreadStamps()); r.flush(); runCurrent(); assertTrue(store.writes.isEmpty())
        r.next(); runCurrent(); val t = assertNotNull(r.state.value.transition)
        val stamps = r.spreadStamps(1)
        r.transitionReady(t.ticket, 1, stamps[1]!!); assertEquals(PageTransitionPhase.WAITING, r.state.value.transition?.phase)
        r.transitionReady(t.ticket, 1, stamps); r.presented(r.state.value.ticket, 1, stamps)
        r.flush(); runCurrent(); assertTrue(store.writes.isEmpty())
        r.settleSpread(); r.flush(); runCurrent(); assertTrue(store.writes.isEmpty())
        r.presented(r.state.value.ticket, 1, stamps[1]!!); r.flush(); runCurrent(); assertTrue(store.writes.isEmpty())
        r.presented(r.state.value.ticket, 1, stamps + (2 to Long.MAX_VALUE)); r.flush(); runCurrent(); assertTrue(store.writes.isEmpty())
        r.presented(r.state.value.ticket, 1, r.spreadStamps()); r.flush(); runCurrent()
        assertEquals(1, (assertNotNull(store.saved).locator as ReadingLocator.Page).pageIndex)
        assertEquals("page-1", (store.saved!!.locator as ReadingLocator.Page).pageKey)
        assertEquals(1, store.writes.size)
    } }
    @Test fun failureOfEitherTargetPageNeverCommitsHalfOrProgress() = runTest {
        for (bad in listOf("page-5", "page-6")) reader { r, doc, decoder, store ->
            decoder.action = { if (doc.opened.last() == bad) error("Controlled failure") }
            r.navigate(3); runCurrent(); r.settleSpread()
            r.presented(r.state.value.ticket, 3, r.spreadStamps()); r.flush(); runCurrent()
            r.next(); runCurrent(); val t = assertNotNull(r.state.value.transition)
            assertIs<PageFrame.Unavailable>(r.state.value.frames[bad.removePrefix("page-").toInt()])
            val partial = t.target.let { index -> (r.state.value.frames[index] as? PageFrame.Ready)?.let { mapOf(index to it.stamp) } }.orEmpty()
            r.transitionReady(t.ticket, 5, partial); r.finishTransition(t.ticket)
            assertEquals(3, r.state.value.position.index)
            r.returnTransition(t.ticket, failed = true); r.settleSpread(); r.flush(); runCurrent()
            assertTrue(r.state.value.navigationFailed)
            assertEquals(3, (assertNotNull(store.saved).locator as ReadingLocator.Page).pageIndex)
        }
    }
    @Test fun dragNextPreviousThresholdVelocityAndInterruptedIdentity() = runTest { reader { r, _, _, _ ->
        r.next(); runCurrent(); r.settleSpread()
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) {
            r.mode(mode); runCurrent()
            val sign = if (mode == PageReadingMode.PAGED_LTR) -1f else 1f
            fun start() = assertNotNull(r.beginDrag(1f, r.spreadStamps()[r.state.value.position.index]))
            val short = start(); r.drag(short, sign * .08f); r.releaseDrag(short, 0f)
            assertEquals(PageTransitionPhase.RETURNING, r.state.value.transition?.phase); r.settleSpread(); assertEquals(1, r.state.value.position.index)
            val fling = start(); r.drag(fling, sign * .08f); r.releaseDrag(fling, sign)
            runCurrent(); r.settleSpread(); assertEquals(3, r.state.value.position.index)
            val previous = start(); r.drag(previous, -sign * .4f); r.releaseDrag(previous, 0f)
            runCurrent(); r.settleSpread(); assertEquals(1, r.state.value.position.index)
            r.next(); r.next(); runCurrent(); val old = assertNotNull(r.state.value.transition)
            r.transitionReady(old.ticket, 5, r.spreadStamps(5)); r.transitionOffset(old.ticket, sign * .3f)
            val grabbed = start(); r.drag(grabbed, sign * .1f); assertEquals(5, r.state.value.transition?.target)
            r.drag(grabbed, -sign * .8f); assertEquals(0, r.state.value.transition?.target)
            r.cancelTransition(); r.finishTransition(old.ticket); assertEquals(1, r.state.value.position.index)
        }
    } }
    @Test fun rapidRequestsKeepOnlyCurrentAndLatestSpreadWithinFourSlots() = runTest {
        for (count in listOf(8, 512)) reader(count) { r, _, _, _ ->
            repeat(100) { r.next(); if (it % 7 == 0) r.previous(); assertTrue(r.state.value.frames.size <= PagePolicy.SPREAD_RETAINED) }
            runCurrent(); assertTrue(r.retainedPages <= 4)
            val t = assertNotNull(r.state.value.transition)
            assertEquals((r.spread(t.from).indices + r.spread(t.target).indices).toSet(), r.state.value.frames.keys)
            r.settleSpread(); assertTrue(r.retainedPages <= 4)
        }
    }
    @Test fun settingsResizeAndCloseRetireBothPageStampsAndOldTransition() = runTest { reader { r, _, _, store ->
        r.next(); runCurrent(); val old = assertNotNull(r.state.value.transition); val stamps = r.spreadStamps(1)
        r.layout(PageLayout.SINGLE); r.transitionReady(old.ticket, 1, stamps); r.transitionOffset(old.ticket, -1f); r.finishTransition(old.ticket)
        assertNull(r.state.value.transition); assertEquals(0, r.state.value.position.index)
        r.layout(PageLayout.DOUBLE); runCurrent(); r.next(); runCurrent(); val resized = assertNotNull(r.state.value.transition)
        r.presentationChanged(); r.finishTransition(resized.ticket); assertNull(r.state.value.transition)
        r.next(); val closing = assertNotNull(r.state.value.transition); r.close(); r.finishTransition(closing.ticket)
        r.presented(r.state.value.ticket, 1, stamps); assertTrue(r.state.value.frames.isEmpty()); assertTrue(store.writes.isEmpty())
    } }
    @Test fun idleModeChangesNormalizeContainmentAndContinuousModesIgnoreRetainedPreference() = runTest { reader { r, _, _, _ ->
        r.layout(PageLayout.SINGLE); r.navigate(4); runCurrent(); r.layout(PageLayout.DOUBLE); runCurrent()
        assertEquals(3, r.state.value.position.index); assertEquals(listOf(3, 4), r.spread().indices)
        // PR #24 retains the exact session page behind Double's visual anchor.
        r.layout(PageLayout.SINGLE); assertEquals(4, r.state.value.position.index)
        r.layout(PageLayout.DOUBLE)
        for (mode in listOf(PageReadingMode.VERTICAL, PageReadingMode.WEBTOON)) {
            r.mode(mode); r.report(r.state.value.ticket, 4, .6); runCurrent()
            assertEquals(PageLayout.DOUBLE, r.state.value.settings.layout); assertEquals(PagePosition(4, .6), r.state.value.position)
            assertEquals(listOf(4), r.spread().indices); assertTrue(r.state.value.frames.size <= 3)
        }
        r.mode(PageReadingMode.PAGED_LTR); runCurrent(); assertEquals(PagePosition(3), r.state.value.position)
        assertNull(r.beginDrag(2f, r.spreadStamps()[3]))
    } }
    @Test fun reopenAtAnchorOrSecondPageNormalizesOnlyPresentationUntilAcknowledged() = runTest {
        for (index in listOf(3, 4)) {
            val doc = TestPageDocument(8); val store = Store()
            store.saved = ReadingProgress(doc.progressId, ReadingLocator.Page("page-$index", index, 0.0), .5, 1)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val progress = ProgressPersistence(store, dispatcher) { 10 }
            val prefs = PageSettingsPersistence(object : PageReaderSettingsStore {
                override suspend fun load() = PageReaderPreferences(double, 1)
                override suspend fun save(preferences: PageReaderPreferences) = true
            }, dispatcher)
            val r = PageReaderController(doc, this, progress, prefs, TestRasterDecoder(), dispatcher)
            try {
                r.initialize(store.saved); runCurrent(); assertEquals(3, r.state.value.position.index)
                r.flush(); runCurrent(); assertEquals(index, (store.saved!!.locator as ReadingLocator.Page).pageIndex)
                assertTrue(store.writes.isEmpty())
                r.presented(r.state.value.ticket, 3, r.spreadStamps()); r.flush(); runCurrent()
                assertEquals(3, (store.saved!!.locator as ReadingLocator.Page).pageIndex)
            } finally { r.close(); progress.close(); progress.awaitClosed(); prefs.close(); prefs.awaitClosed() }
        }
    }
    @Test fun noncooperativeDecodeModeSwitchAndRapidReplacementRemainSerialized() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); val doc = TestPageDocument(512)
        val gate = CompletableDeferred<Unit>(); val decoder = TestRasterDecoder()
        decoder.action = { withContext(NonCancellable) { gate.await() } }
        val r = PageReaderController(doc, this, decoder = decoder, decodeDispatcher = dispatcher)
        try {
            r.initialize(null); runCurrent(); assertEquals(1, decoder.calls)
            r.layout(PageLayout.DOUBLE); repeat(100) { r.next(); assertTrue(r.state.value.frames.size <= 4) }; runCurrent()
            assertEquals(1, decoder.calls)
            r.layout(PageLayout.SINGLE); r.navigate(400); runCurrent(); assertEquals(1, decoder.calls)
            gate.complete(Unit); runCurrent(); assertTrue(r.state.value.frames.size <= 3)
            assertFalse(0 in r.state.value.frames); assertIs<PageFrame.Ready>(r.state.value.frames[400])
        } finally { gate.complete(Unit); r.close(); runCurrent() }
        assertEquals(doc.opened.size, doc.closedHandles)
    }
    @Test fun singleTurnCannotCommitAfterSwitchingToDouble() = runTest { reader { r, _, _, store ->
        r.layout(PageLayout.SINGLE); r.navigate(4); runCurrent()
        r.next(); runCurrent(); val old = assertNotNull(r.state.value.transition)
        val oldStamp = assertIs<PageFrame.Ready>(r.state.value.frames[5]).stamp
        r.layout(PageLayout.DOUBLE); runCurrent()
        r.transitionReady(old.ticket, 5, oldStamp); r.transitionOffset(old.ticket, -1f); r.finishTransition(old.ticket)
        assertNull(r.state.value.transition); assertEquals(PagePosition(3), r.state.value.position)
        assertEquals(listOf(3, 4), r.spread().indices)
        r.flush(); runCurrent(); assertTrue(store.writes.isEmpty())
    } }
    @Test fun layoutSelectionUsesExistingPreferenceWriterAndSurvivesModeChanges() = runTest {
        var stored = PageReaderPreferences()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = PageSettingsPersistence(object : PageReaderSettingsStore {
            override suspend fun load() = stored
            override suspend fun save(preferences: PageReaderPreferences): Boolean { stored = preferences; return true }
        }, dispatcher)
        val r = PageReaderController(TestPageDocument(8), this, preferences = prefs, decoder = TestRasterDecoder(), decodeDispatcher = dispatcher)
        try {
            r.initialize(null); r.layout(PageLayout.DOUBLE); r.mode(PageReadingMode.WEBTOON)
            r.mode(PageReadingMode.PAGED_RTL); r.flush(); runCurrent()
            assertEquals(PageReaderSettings(PageReadingMode.PAGED_RTL, PageLayout.DOUBLE), stored.settings)
            assertTrue(stored.updatedAtEpochMillis > 0)
        } finally { r.close(); prefs.close(); prefs.awaitClosed() }
    }

}
