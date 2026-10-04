// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TextViewportTest {
    private class Windows(override val count: Int = 100) : TextWindows {
        override val codePoints = count * TEXT_WINDOW_CODE_POINTS
        override fun start(index: Int) = index * TEXT_WINDOW_CODE_POINTS
        val reads = mutableListOf<Int>()
        var action: suspend (Int) -> Unit = {}
        var running = 0; var maximumRunning = 0
        override suspend fun read(index: Int): TextWindow {
            reads += index; running++; maximumRunning = maxOf(maximumRunning, running)
            try { action(index); return TextWindow(index, start(index), "a".repeat(TEXT_WINDOW_CODE_POINTS)) }
            finally { running-- }
        }
        override fun close() {}
    }
    private fun document(windows: TextWindows) = TextDocument(PublicationId(SourceId("fixture"), "text"), "Title", windows)

    // Independent measurement simulation: Compose 1.12.1 LazyListMeasure subtracts
    // the reverse delta, then adds each previous item's measured height until >= 0.
    // This tests geometry/anchors, not Android gesture velocity or Compose rendering.
    private fun backwards(index: Int, offset: Int, pixels: Int, height: (Int) -> Int): Pair<Int, Int> {
        var first = index; var top = offset - pixels
        while (top < 0 && first > 0) { first--; top += height(first) }
        return first to top.coerceAtLeast(0)
    }
    private fun forwards(index: Int, offset: Int, pixels: Int, height: (Int) -> Int): Pair<Int, Int> {
        var first = index; var top = offset + pixels
        while (top >= height(first)) { top -= height(first); first++ }
        return first to top
    }

    @Test fun coldBackwardReloadUsesMeasuredExtentInsteadOfCollapsingTenWindows() {
        val geometry = TextViewportGeometry(20)
        repeat(20) { geometry.measured(it, 1000) }
        assertEquals(0 to 0, backwards(10, 0, 200) { 20 }) // old one-line Loading text…
        assertEquals(9 to 800, backwards(10, 0, 200) { geometry.pendingHeight(it, 600) })
    }
    @Test fun forwardAndBackwardHaveEqualDistanceAcrossEvictedSlots() {
        val geometry = TextViewportGeometry(50)
        repeat(50) { geometry.measured(it, 300 + it * 7) }
        val start = 20 to 123
        val next = forwards(start.first, start.second, 1900) { geometry.pendingHeight(it, 600) }
        assertEquals(start, backwards(next.first, next.second, 1900) { geometry.pendingHeight(it, 600) })
    }
    @Test fun repeatedDirectionChangesDoNotAccumulateAnchorError() {
        val geometry = TextViewportGeometry(100)
        repeat(100) { geometry.measured(it, 110 + it * 13) }
        var anchor = 30 to 57
        repeat(500) {
            val next = forwards(anchor.first, anchor.second, 1234) { geometry.pendingHeight(it, 600) }
            anchor = backwards(next.first, next.second, 1234) { geometry.pendingHeight(it, 600) }
            assertEquals(30 to 57, anchor)
        }
    }
    @Test fun earlierAndLaterLoadingDoNotChangeAnAlreadyMeasuredVisibleAnchor() {
        val geometry = TextViewportGeometry(10); geometry.measured(5, 900)
        val document = document(Windows(10)); val key = document.windowStart(5)
        val height = geometry.pendingHeight(5, 600)
        geometry.measured(4, 1200); geometry.measured(6, 700)
        assertEquals(height, geometry.pendingHeight(5, 600)); assertEquals(10240, key)
        assertEquals(key, document.windowStart(5)) // global location, never loaded-range position
    }
    @Test fun unseenSlotReservesViewportInsteadOfPretendingItIsOneLine() {
        val geometry = TextViewportGeometry(2)
        assertEquals(600, geometry.pendingHeight(0, 600)); assertEquals(1, geometry.pendingHeight(1, 0))
        geometry.measured(0, 1400); assertEquals(1400, geometry.pendingHeight(0, 300))
    }
    @Test fun differentLayoutGetsNewGeometryWithoutChangingLogicalIdentity() {
        val wide = TextViewportGeometry(3); wide.measured(1, 400)
        val narrow = TextViewportGeometry(3)
        assertEquals(800, narrow.pendingHeight(1, 800)); assertEquals(400, wide.pendingHeight(1, 800))
        val doc = document(Windows(3)); assertEquals(1, doc.windowFor(2500)); assertEquals(2048, doc.windowStart(1))
    }
    @Test fun beginningAndEndGeometryRemainValidAtMaximumIndexCount() {
        val geometry = TextViewportGeometry(16384)
        geometry.measured(0, 200); geometry.measured(16383, 500)
        assertEquals(0 to 0, backwards(0, 0, 500) { geometry.pendingHeight(it, 600) })
        assertEquals(500, geometry.pendingHeight(16383, 600))
    }
    @Test fun neighborsAreSymmetricBoundedAndRequestsSerialized() = runTest {
        val windows = Windows(); val loader = TextWindowLoader(document(windows), this)
        loader.focus(50); val state = loader.attach(50); runCurrent()
        assertEquals(listOf(50, 49, 51, 48, 52), windows.reads)
        assertEquals(50, state.value?.index); assertEquals(1, windows.maximumRunning)
        assertEquals(5, loader.cachedWindows); loader.close(); loader.awaitClosed()
    }
    @Test fun beginningAndEndPrefetchNeverGoOutsideDocument() = runTest {
        val windows = Windows(3); val loader = TextWindowLoader(document(windows), this)
        loader.attach(0); runCurrent(); assertEquals(listOf(0, 1, 2), windows.reads)
        loader.detach(0); loader.focus(2); loader.attach(2); runCurrent()
        assertEquals(3, windows.reads.size); loader.close(); loader.awaitClosed()
    }
    @Test fun duplicateAttachAndRepeatedFocusDoNotDuplicateIO() = runTest {
        val windows = Windows(); val loader = TextWindowLoader(document(windows), this)
        val first = loader.attach(20); loader.attach(20); loader.focus(20); runCurrent()
        repeat(50) { loader.focus(20) }; runCurrent()
        assertEquals(5, windows.reads.size); assertEquals(1, windows.reads.count { it == 20 })
        assertEquals(1, loader.activeWindows); assertEquals(20, first.value?.index)
        loader.close(); loader.awaitClosed()
    }
    @Test fun warmNeighborAttachesImmediatelyWithoutLoadingPlaceholderOrIO() = runTest {
        val windows = Windows(); val loader = TextWindowLoader(document(windows), this)
        loader.attach(20); loader.focus(20); runCurrent()
        assertEquals(21, loader.available(21)?.index)
        val readCount = windows.reads.size; val next = loader.attach(21)
        assertEquals(21, next.value?.index); runCurrent()
        assertEquals(readCount + 1, windows.reads.size) // only newly needed neighbor 23
        loader.close(); loader.awaitClosed()
    }
    @Test fun detachedEvictedWindowReloadsWithoutChangingKeyOrExtent() = runTest {
        val windows = Windows(); val doc = document(windows); val loader = TextWindowLoader(doc, this)
        val geometry = TextViewportGeometry(doc.windowCount)
        val first = loader.attach(10); loader.focus(10); runCurrent()
        val key = first.value!!.startCodePoint; geometry.measured(10, 1000); loader.detach(10)
        val second = loader.attach(80); loader.focus(80); runCurrent(); assertNotNull(second.value)
        assertTrue(loader.cachedWindows <= TEXT_WINDOW_CACHE_ENTRIES)
        loader.detach(80); val back = loader.attach(10); loader.focus(10)
        assertNull(back.value); assertEquals(1000, geometry.pendingHeight(10, 600)); runCurrent()
        assertEquals(key, back.value!!.startCodePoint); assertEquals(2, windows.reads.count { it == 10 })
        loader.close(); loader.awaitClosed()
    }
    @Test fun currentlyComposedTextSurvivesPrefetchCacheEviction() = runTest {
        val windows = Windows(); val loader = TextWindowLoader(document(windows), this)
        val states = (0..10).map(loader::attach); loader.focus(5); runCurrent()
        assertTrue(states.all { it.value != null }); assertEquals(8, loader.cachedWindows)
        assertEquals(11, loader.activeWindows) // viewport-owned, not retained after disposal
        (0..10).forEach(loader::detach); assertEquals(0, loader.activeWindows)
        loader.close(); loader.awaitClosed()
    }
    @Test fun rapidDirectionChangesCoalesceWithoutLaunchingAnIOJobPerChange() = runTest {
        val windows = Windows(); val loader = TextWindowLoader(document(windows), this)
        val gate = CompletableDeferred<Unit>(); windows.action = { if (it == 10) gate.await() }
        loader.attach(10); runCurrent()
        repeat(1000) { loader.focus(if (it % 2 == 0) 20 else 80) }
        loader.detach(10); val final = loader.attach(80); gate.complete(Unit); runCurrent()
        assertEquals(80, final.value?.index); assertEquals(6, windows.reads.size)
        assertEquals(1, windows.maximumRunning); assertTrue(loader.cachedWindows <= 8)
        loader.close(); loader.awaitClosed()
    }
    @Test fun obsoleteNonCooperativeCompletionCannotPublishIntoNewRange() = runTest {
        val windows = Windows(); val loader = TextWindowLoader(document(windows), this)
        val gate = CompletableDeferred<Unit>(); windows.action = { if (it == 10) withContext(NonCancellable) { gate.await() } }
        val old = loader.attach(10); runCurrent(); loader.detach(10)
        val current = loader.attach(70); loader.focus(70); gate.complete(Unit); runCurrent()
        assertNull(old.value); assertEquals(70, current.value?.index); assertFalse(loader.failed.value)
        loader.close(); loader.awaitClosed()
    }
    @Test fun closingDuringLoadCancelsWorkerAndNeverPublishesResult() = runTest {
        val windows = Windows(); val loader = TextWindowLoader(document(windows), this)
        windows.action = { awaitCancellation() }; val state = loader.attach(10); runCurrent()
        loader.close(); loader.close(); loader.awaitClosed()
        assertNull(state.value); assertEquals(0, loader.cachedWindows); assertEquals(0, windows.running)
        assertFailsWith<IllegalStateException> { loader.attach(10) }
    }
    @Test fun nonCooperativeLateCompletionCannotReviveClosedReaderOrNewDocument() = runTest {
        val windows = Windows(); val first = TextWindowLoader(document(windows), this)
        val gate = CompletableDeferred<Unit>(); windows.action = { withContext(NonCancellable) { gate.await() } }
        val old = first.attach(10); runCurrent(); first.close()
        val replacement = TextWindowLoader(document(Windows()), this); val current = replacement.attach(30)
        gate.complete(Unit); runCurrent(); first.awaitClosed()
        assertNull(old.value); assertEquals(0, first.cachedWindows); assertEquals(30, current.value?.index)
        replacement.close(); replacement.awaitClosed()
    }
    @Test fun storageFailureIsFixedAndDoesNotStartARetryLoop() = runTest {
        val windows = Windows(); windows.action = { error("private data") }
        val loader = TextWindowLoader(document(windows), this); val state = loader.attach(1); runCurrent()
        assertTrue(loader.failed.value); assertNull(state.value)
        repeat(100) { loader.focus(it) }; runCurrent(); assertEquals(1, windows.reads.size)
        loader.close(); loader.awaitClosed()
    }
    @Test fun staleReadFailureDoesNotFailNewRange() = runTest {
        val windows = Windows(); val gate = CompletableDeferred<Unit>()
        windows.action = { if (it == 10) { gate.await(); error("old failure") } }
        val loader = TextWindowLoader(document(windows), this); loader.attach(10); runCurrent()
        loader.detach(10); val next = loader.attach(50); loader.focus(50); gate.complete(Unit); runCurrent()
        assertEquals(50, next.value?.index); assertFalse(loader.failed.value)
        loader.close(); loader.awaitClosed()
    }
    @Test fun pendingWindowsDoNotReportFalseEofOrRegressExistingPosition() {
        val doc = document(Windows()); val window = TextWindow(20, doc.windowStart(20), "é😀abc")
        assertNull(visibleWindowCodePoint(doc, null, 0, atBottom = true, finalWindowLaidOut = false))
        assertEquals(40962, visibleWindowCodePoint(doc, window, 3, atBottom = true, finalWindowLaidOut = false))
        assertEquals(doc.codePoints, visibleWindowCodePoint(doc, window, 3, atBottom = true, finalWindowLaidOut = true))
    }
    @Test fun earlierWindowLoadingDoesNotChangeVisibleSemanticProgress() {
        val doc = document(Windows()); val window = TextWindow(30, doc.windowStart(30), "😀é\r\ne\u0301")
        val before = visibleWindowCodePoint(doc, window, 5, false, false)
        val geometry = TextViewportGeometry(doc.windowCount); geometry.measured(29, 2300)
        assertEquals(61444, before); assertEquals(before, visibleWindowCodePoint(doc, window, 5, false, false))
    }
    @Test fun distantNavigationNeverTraversesOrRetainsWholeDocument() = runTest {
        val windows = Windows(8192); val loader = TextWindowLoader(document(windows), this)
        var previous: Int? = null
        repeat(100) { turn ->
            val next = if (turn % 2 == 0) 100 + turn else 8000 - turn
            previous?.let(loader::detach); loader.focus(next); val state = loader.attach(next); runCurrent()
            assertEquals(next, state.value?.index)
            assertTrue(loader.cachedWindows <= 8); assertEquals(1, loader.activeWindows)
            previous = next
        }
        assertTrue(windows.reads.size <= 500); assertEquals(1, windows.maximumRunning)
        loader.close(); loader.awaitClosed()
    }

}
