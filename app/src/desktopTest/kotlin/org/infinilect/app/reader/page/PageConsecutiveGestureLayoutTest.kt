// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Real pager/input/Compose animation with virtual coroutine and render clocks.
 * Supplied tiny artwork isolates conversion readiness from gesture timing. No sleeps. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PageConsecutiveGestureLayoutTest {
    private class Fixture(val scope: TestScope, private val layout: PageLayout = PageLayout.DOUBLE,
        private val mode: PageReadingMode = PageReadingMode.PAGED_LTR) : AutoCloseable {
        val doc = TestPageDocument(8)
        val reader = PageReaderController(doc, scope.backgroundScope, decoder = TestRasterDecoder(),
            decodeDispatcher = StandardTestDispatcher(scope.testScheduler))
        val scene = ImageComposeScene(640, 420, Density(1f), coroutineContext = UnconfinedTestDispatcher(scope.testScheduler))
        val bitmap = ImageBitmap(2, 2)
        lateinit var transform: PageTransform
        var hidden by mutableStateOf(emptySet<Int>())
        suspend fun start() {
            reader.initialize(null); reader.layout(layout); reader.mode(mode); scope.runCurrent()
            reader.navigate(3); scope.runCurrent(); if (reader.state.value.transition != null) reader.settleSpread()
            scene.setContent {
                val state by reader.state.collectAsState()
                val live = remember(reader, state.position.index, state.settings) { PageTransform() }
                transform = live
                val art = state.frames.filter { (i, frame) -> frame is PageFrame.Ready && i !in hidden }.mapValues { bitmap }
                MaterialTheme { PagedCanvas(reader, state, art, emptySet(), live, Modifier.fillMaxSize()) }
            }
            pump()
        }
        fun pump() { repeat(4) { scope.runCurrent(); scene.render(scope.testScheduler.currentTime * 1_000_000).close() }; scope.runCurrent() }
        fun advance(ms: Long) { scope.advanceTimeBy(ms); pump() }
        fun touch(type: PointerEventType, at: Offset) {
            scene.sendPointerEvent(type, at, timeMillis = scope.testScheduler.currentTime, type = PointerType.Touch); pump()
        }
        fun record(label: String) {
            val s = reader.state.value; val t = s.transition
            println("$label position=${s.position.index} from=${t?.from} target=${t?.target} phase=${t?.phase} offset=${t?.offset} ticket=${t?.ticket} edgeTicket=${transform.edgeTicket} side=${t?.let { pageIncomingSide(it.from, it.target, s.settings.mode) }}")
        }
        fun zoomToEdge(sign: Float) {
            transform.zoom = 2f
            val fit = fitPageSpread(reader.spread().indices.map { doc.pages[it].dimensions }, Size(640f, 420f), 2f)
            val bounds = pagePanBounds(fit.size, Size(640f, 420f), 2f)
            transform.pan = Offset(sign * bounds.x, 0f); pump()
        }
        fun settle(target: Int) { repeat(14) { advance(16) }; assertEquals(target, reader.state.value.position.index); assertNull(reader.state.value.transition) }
        override fun close() { scene.close(); reader.close(); scope.runCurrent(); assertEquals(doc.opened.size, doc.closedHandles) }
    }
    @Test fun zoomedSecondTouchDoesNotDropIncomingPresentationBeforeSlop() = runTest {
        Fixture(this).use { f ->
            f.start(); f.zoomToEdge(-1f)
            val start = Offset(320f, 210f)
            f.touch(PointerEventType.Press, start); f.advance(8)
            f.touch(PointerEventType.Move, start - Offset(192f, 0f)); f.advance(8)
            f.touch(PointerEventType.Release, start - Offset(192f, 0f))
            assertEquals(PageTransitionPhase.SETTLING, f.reader.state.value.transition?.phase)
            f.advance(48); val old = assertNotNull(f.reader.state.value.transition); f.record("partly settled")
            f.touch(PointerEventType.Press, start); f.record("second down")
            val replacement = assertNotNull(f.reader.state.value.transition)
            assertTrue(replacement.ticket > old.ticket); assertEquals(old.offset, replacement.offset)
            assertEquals(replacement.ticket, f.transform.edgeTicket)
            f.advance(8); f.touch(PointerEventType.Move, start - Offset(2f, 0f)); f.record("second 2px move")
            assertEquals(old.offset - 2f / 640, assertNotNull(f.reader.state.value.transition).offset, .0001f)
            f.advance(8); f.touch(PointerEventType.Release, start - Offset(26f, 0f)); f.record("second release")
            f.settle(5); assertEquals(1f, f.transform.zoom)
        }
    }
    @Test fun oneTimesSecondDownHoldsTransitionUntilGestureIntentWithoutSettlementCancellingInput() = runTest {
        Fixture(this).use { f ->
            f.start(); val start = Offset(320f, 210f)
            f.touch(PointerEventType.Press, start); f.advance(8)
            f.touch(PointerEventType.Move, start - Offset(192f, 0f)); f.advance(8)
            f.touch(PointerEventType.Release, start - Offset(192f, 0f)); f.advance(48)
            val old = assertNotNull(f.reader.state.value.transition); f.record("1x partly settled")
            f.touch(PointerEventType.Press, start); f.record("1x second down")
            val grabbed = assertNotNull(f.reader.state.value.transition)
            f.advance(224); f.record("1x held down beyond original settle")
            assertEquals(PageTransitionPhase.DRAGGING, grabbed.phase)
            assertEquals(3, f.reader.state.value.position.index)
            assertEquals(old.offset, f.reader.state.value.transition?.offset)
            f.touch(PointerEventType.Move, start - Offset(192f, 0f)); f.advance(8)
            f.touch(PointerEventType.Release, start - Offset(192f, 0f)); f.settle(5)
            // No post-settle cooldown: next input starts immediately on the validated page.
            f.touch(PointerEventType.Press, start); f.touch(PointerEventType.Move, start - Offset(192f, 0f))
            assertEquals(7, f.reader.state.value.transition?.target)
            f.touch(PointerEventType.Release, start - Offset(192f, 0f)); f.settle(7)
        }
    }
    @Test fun interruptedNextAndPreviousWorkInBothDirectionsLayoutsAndZoomStates() = runTest {
        for (layout in PageLayout.entries) for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL))
            for (next in listOf(false, true)) for (zoomed in listOf(false, true)) Fixture(this, layout, mode).use { f ->
                f.start()
                val target = 3 + (if (next) 1 else -1) * (if (layout == PageLayout.DOUBLE) 2 else 1)
                val sign = -pageIncomingSide(3, target, mode).toFloat()
                if (zoomed) f.zoomToEdge(sign)
                val start = Offset(320f, 210f)
                f.touch(PointerEventType.Press, start); f.advance(8)
                f.touch(PointerEventType.Move, start + Offset(sign * 192, 0f)); f.advance(8)
                f.touch(PointerEventType.Release, start + Offset(sign * 192, 0f)); f.advance(48)
                val old = assertNotNull(f.reader.state.value.transition)
                f.touch(PointerEventType.Press, start)
                val grabbed = assertNotNull(f.reader.state.value.transition)
                assertTrue(grabbed.ticket > old.ticket); assertEquals(old.offset, grabbed.offset)
                f.advance(8); f.touch(PointerEventType.Move, start + Offset(sign * 2, 0f))
                assertEquals(target, f.reader.state.value.transition?.target)
                f.advance(8); f.touch(PointerEventType.Release, start + Offset(sign * 26, 0f))
                f.settle(target); assertEquals(1f, f.transform.zoom); assertNull(f.transform.edgeTicket)
            }
    }
    @Test fun waitingOnConversionCanBeGrabbedWithoutChangingTargetOrCommittingEarly() = runTest {
        Fixture(this).use { f ->
            f.start(); f.hidden = setOf(5, 6); f.zoomToEdge(-1f)
            val start = Offset(320f, 210f)
            f.touch(PointerEventType.Press, start); f.advance(8)
            f.touch(PointerEventType.Move, start - Offset(192f, 0f)); f.advance(8)
            f.touch(PointerEventType.Release, start - Offset(192f, 0f))
            assertEquals(PageTransitionPhase.WAITING, f.reader.state.value.transition?.phase)
            f.touch(PointerEventType.Press, start); f.advance(8)
            f.touch(PointerEventType.Move, start - Offset(2f, 0f)); f.advance(8)
            f.touch(PointerEventType.Release, start - Offset(26f, 0f))
            f.advance(224); assertEquals(3, f.reader.state.value.position.index)
            assertEquals(5, f.reader.state.value.transition?.target)
            f.hidden = emptySet(); f.pump(); f.settle(5)
        }
    }
    @Test fun centerTouchResumesAcceptedTurnRatherThanReturningAndStillTogglesControls() = runTest {
        for (zoomed in listOf(false, true)) Fixture(this).use { f ->
            f.start(); if (zoomed) f.zoomToEdge(-1f)
            val start = Offset(320f, 210f)
            f.touch(PointerEventType.Press, start); f.advance(8)
            f.touch(PointerEventType.Move, start - Offset(192f, 0f)); f.advance(8)
            f.touch(PointerEventType.Release, start - Offset(192f, 0f)); f.advance(48)
            val visible = f.reader.state.value.controlsVisible
            f.touch(PointerEventType.Press, start); f.advance(8); f.touch(PointerEventType.Release, start)
            assertEquals(!visible, f.reader.state.value.controlsVisible)
            f.settle(5)
        }
    }
    @Test fun pinchDuringGrabRetiresBothTicketsAndCannotFinishAnInterruptedTurn() = runTest {
        Fixture(this).use { f ->
            f.start(); f.zoomToEdge(-1f); val start = Offset(320f, 210f)
            f.touch(PointerEventType.Press, start); f.advance(8)
            f.touch(PointerEventType.Move, start - Offset(192f, 0f)); f.advance(8)
            f.touch(PointerEventType.Release, start - Offset(192f, 0f)); f.advance(48)
            val old = assertNotNull(f.reader.state.value.transition)
            f.touch(PointerEventType.Press, start)
            val grabbed = assertNotNull(f.reader.state.value.transition)
            f.scene.sendPointerEvent(PointerEventType.Press, listOf(
                ComposeScenePointer(PointerId(0), start, true, PointerType.Touch),
                ComposeScenePointer(PointerId(1), start + Offset(50f, 0f), true, PointerType.Touch)),
                timeMillis = testScheduler.currentTime)
            f.pump(); assertNull(f.reader.state.value.transition); assertNull(f.transform.edgeTicket)
            f.reader.finishTransition(old.ticket); f.reader.finishTransition(grabbed.ticket)
            f.advance(224); assertEquals(3, f.reader.state.value.position.index)
            f.scene.sendPointerEvent(PointerEventType.Release, listOf(
                ComposeScenePointer(PointerId(0), start, false, PointerType.Touch),
                ComposeScenePointer(PointerId(1), start + Offset(50f, 0f), false, PointerType.Touch)),
                timeMillis = testScheduler.currentTime)
            f.pump(); assertNull(f.reader.state.value.transition)
        }
    }
    @Test fun oppositeGestureUnwindsAndReturningCanBeGrabbedWithoutLosingZoomOrPan() = runTest {
        Fixture(this).use { f ->
            f.start(); f.zoomToEdge(-1f); val start = Offset(320f, 210f)
            val pan = f.transform.pan
            f.touch(PointerEventType.Press, start); f.advance(8)
            f.touch(PointerEventType.Move, start - Offset(192f, 0f)); f.advance(8)
            f.touch(PointerEventType.Release, start - Offset(192f, 0f)); f.advance(48)
            val old = assertNotNull(f.reader.state.value.transition)
            f.touch(PointerEventType.Press, start)
            f.advance(80)
            val unwind = (-old.offset - .03f) * 640
            f.touch(PointerEventType.Move, start + Offset(unwind, 0f)); f.advance(80)
            f.touch(PointerEventType.Release, start + Offset(unwind, 0f))
            assertEquals(PageTransitionPhase.RETURNING, f.reader.state.value.transition?.phase)
            f.advance(48); val returning = assertNotNull(f.reader.state.value.transition)
            f.touch(PointerEventType.Press, start)
            assertEquals(returning.offset, f.reader.state.value.transition?.offset)
            f.advance(8); f.touch(PointerEventType.Release, start)
            f.settle(3); assertEquals(2f, f.transform.zoom); assertEquals(pan, f.transform.pan)
        }
    }
}
