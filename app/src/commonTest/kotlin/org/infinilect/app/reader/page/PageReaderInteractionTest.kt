// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PageReaderInteractionTest {
    private class Store : ReadingProgressStore {
        var value: ReadingProgress? = null
        val writes = mutableListOf<ReadingProgress>()
        override suspend fun get(id: ReadingProgressId) = value?.takeIf { it.id == id }
        override suspend fun save(progress: ReadingProgress): Boolean { value = progress; writes += progress; return true }
        override suspend fun remove(id: ReadingProgressId) = true
        val index get() = (value?.locator as? ReadingLocator.Page)?.pageIndex
    }
    private suspend fun TestScope.withReader(action: suspend (PageReaderController, TestRasterDecoder, Store) -> Unit) {
        val store = Store()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistence = ProgressPersistence(store, dispatcher) { 10 }
        val decoder = TestRasterDecoder()
        val document = TestPageDocument(8)
        val reader = PageReaderController(document, this, persistence, decoder = decoder, decodeDispatcher = dispatcher)
        try { reader.initialize(null); runCurrent(); action(reader, decoder, store) }
        finally { reader.close(); persistence.close(); persistence.awaitClosed() }
        assertEquals(1, document.closes)
        assertEquals(document.opened.size, document.closedHandles)
    }
    private fun PageReaderController.show(): PageFrame.Ready {
        val state = state.value
        val frame = assertIs<PageFrame.Ready>(state.frames[state.position.index])
        presented(state.ticket, state.position.index, frame.stamp)
        return frame
    }
    @Test fun proportionalZonesMapBothDirectionsAndRejectInvalidCoordinates() {
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) {
            assertEquals(if (mode == PageReadingMode.PAGED_LTR) PageTapAction.PREVIOUS else PageTapAction.NEXT, pageTapAction(.1f, mode))
            for (x in listOf(.3f, .5f, .7f)) assertEquals(PageTapAction.CONTROLS, pageTapAction(x, mode))
            assertEquals(if (mode == PageReadingMode.PAGED_LTR) PageTapAction.NEXT else PageTapAction.PREVIOUS, pageTapAction(.9f, mode))
            for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) assertNull(pageTapAction(x, mode))
        }
    }
    @Test fun tapsControlsAndLogicalButtonsRespectDirectionAndEdges() = runTest { withReader { reader, _, _ ->
        assertEquals(PageReadingMode.PAGED_LTR, reader.state.value.settings.mode)
        reader.tap(.1f); assertEquals(0, reader.state.value.position.index)
        val ticket = reader.state.value.ticket
        reader.tap(.5f); assertTrue(reader.state.value.controlsVisible)
        reader.tap(.5f); assertFalse(reader.state.value.controlsVisible)
        assertEquals(ticket, reader.state.value.ticket)
        reader.tap(.9f); runCurrent(); reader.settlePageTurn(); assertEquals(1, reader.state.value.position.index)
        reader.mode(PageReadingMode.PAGED_RTL)
        reader.tap(.9f); runCurrent(); reader.settlePageTurn(); assertEquals(0, reader.state.value.position.index)
        reader.tap(.1f); runCurrent(); reader.settlePageTurn(); assertEquals(1, reader.state.value.position.index)
        reader.next(); runCurrent(); reader.settlePageTurn(); assertEquals(2, reader.state.value.position.index)
        reader.previous(); runCurrent(); reader.settlePageTurn(); assertEquals(1, reader.state.value.position.index)
        reader.navigate(7); reader.tap(.1f); assertEquals(7, reader.state.value.position.index)
        reader.navigate(0); reader.tap(.9f); runCurrent(); reader.settlePageTurn(); assertEquals(0, reader.state.value.position.index)
    } }
    @Test fun decodingAloneDoesNotSaveAndSuccessfulPresentationCommitsOnce() = runTest { withReader { reader, _, store ->
        reader.show(); reader.flush(); runCurrent(); assertTrue(store.writes.isEmpty())
        reader.next(); runCurrent(); reader.flush(); runCurrent(); assertNull(store.index)
        reader.settlePageTurn(); reader.show(); reader.show(); reader.flush(); runCurrent(); assertEquals(1, store.index)
        assertEquals(1, store.writes.size)
    } }
    @Test fun failedPageAndReopenKeepLastSuccessfullyPresentedPosition() = runTest { withReader { reader, decoder, store ->
        reader.navigate(2); runCurrent(); reader.show(); reader.flush(); runCurrent(); assertEquals(2, store.index)
        decoder.action = { error("Unavailable page") }
        reader.navigate(6); runCurrent(); assertIs<PageFrame.Unavailable>(reader.state.value.frames[6])
        reader.presented(reader.state.value.ticket, 6, Long.MAX_VALUE)
        reader.close(); runCurrent(); assertEquals(2, store.index)
        val recreated = PageReaderController(TestPageDocument(8), this, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
        try { recreated.initialize(store.value); runCurrent(); assertEquals(2, recreated.state.value.position.index); recreated.show() }
        finally { recreated.close() }
    } }
    @Test fun lateNoncooperativeDecodeAndOldPresentationCannotReplaceLatestOrSave() = runTest { withReader { reader, decoder, store ->
        reader.navigate(1); runCurrent(); val oldFrame = reader.show(); val oldTicket = reader.state.value.ticket
        reader.flush(); runCurrent(); assertEquals(1, store.index)
        val gate = CompletableDeferred<Unit>()
        decoder.action = { withContext(NonCancellable) { gate.await() } }
        reader.navigate(4); runCurrent()
        val obsoleteTicket = reader.state.value.ticket
        reader.navigate(6); runCurrent()
        reader.presented(oldTicket, 1, oldFrame.stamp)
        reader.presented(obsoleteTicket, 4, oldFrame.stamp)
        gate.complete(Unit); runCurrent()
        assertEquals(6, reader.state.value.position.index)
        assertFalse(4 in reader.state.value.frames)
        assertIs<PageFrame.Ready>(reader.state.value.frames[6])
        reader.flush(); runCurrent(); assertEquals(1, store.index)
        reader.show(); reader.flush(); runCurrent(); assertEquals(6, store.index)
    } }
    @Test fun backWhileDecodeIsInFlightCannotPublishOrSaveRequestedPage() = runTest { withReader { reader, decoder, store ->
        reader.navigate(1); runCurrent(); val old = reader.show(); reader.flush(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        decoder.action = { withContext(NonCancellable) { gate.await() } }
        reader.navigate(6); runCurrent(); val ticket = reader.state.value.ticket
        reader.close(); gate.complete(Unit); runCurrent()
        reader.presented(ticket, 6, old.stamp); reader.tap(.5f)
        assertTrue(reader.state.value.frames.isEmpty()); assertFalse(reader.state.value.controlsVisible)
        assertEquals(1, store.index)
    } }
    @Test fun recreationTicketsAndRestoredFractionKeepSemanticProgress() = runTest { withReader { reader, _, store ->
        reader.report(reader.state.value.ticket, 3, .4); runCurrent(); val frame = reader.show()
        reader.flush(); runCurrent(); val oldTicket = reader.state.value.ticket
        reader.presentationChanged(); reader.presented(oldTicket, 3, frame.stamp)
        reader.report(oldTicket, 0, 0.0); assertEquals(PagePosition(3, .4), reader.state.value.position)
        reader.close(); runCurrent()
        val recreated = PageReaderController(TestPageDocument(8), this, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(testScheduler))
        try { recreated.initialize(store.value); runCurrent(); assertEquals(PagePosition(3, .4), recreated.state.value.position) }
        finally { recreated.close() }
    } }
    @Test fun transitionDirectionUsesSuccessfulIndicesAndReadingDirection() {
        assertEquals(0, pageIncomingSide(0, 0, PageReadingMode.PAGED_LTR))
        assertEquals(0, pageIncomingSide(2, 2, PageReadingMode.PAGED_RTL))
        assertEquals(1, pageIncomingSide(1, 3, PageReadingMode.PAGED_LTR))
        assertEquals(-1, pageIncomingSide(3, 1, PageReadingMode.PAGED_LTR))
        assertEquals(-1, pageIncomingSide(1, 3, PageReadingMode.PAGED_RTL))
        assertEquals(1, pageIncomingSide(3, 1, PageReadingMode.PAGED_RTL))
    }
}
