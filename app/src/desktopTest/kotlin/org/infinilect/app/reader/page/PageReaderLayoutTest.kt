// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlin.test.*

/** Actual headless Compose hit testing/layout with mouse input and real bitmap conversion.
 * Scenario sizes are inputs; assertions concern visibility and logical state, not fixed pixels. */
@OptIn(ExperimentalComposeUiApi::class)
class PageReaderLayoutTest {
    private class Fixture(val width: Int, val height: Int, fontScale: Float = 1f) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val document = TestPageDocument(8)
        val decoder = TestRasterDecoder()
        val reader = PageReaderController(document, scope, decoder = decoder)
        val scene = ImageComposeScene(width, height, Density(1f, fontScale))
        var backed = false
        private var frame = 0L
        init {
            runBlocking { reader.initialize(null) }
            scene.setContent { MaterialTheme { PageReader(reader, false, { backed = true; reader.close() }, "Back") } }
            awaitArtwork(0)
        }
        fun draw(count: Int = 4) { repeat(count) { scene.render(++frame * 16_666_667L).close() } }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            walk(owner.unmergedRootSemanticsNode)
        }
        fun text(label: String) = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
        fun artwork(index: Int) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Page ${index + 1} of 8") == true }
        fun canvas() = nodes().first { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Toggle reader controls" }
        fun reachable(node: SemanticsNode) {
            val b = node.boundsInRoot
            assertTrue(b.width > 0 && b.height > 0 && b.right > 0 && b.left < width && b.bottom > 0 && b.top < height, "Control must intersect available viewport: $b")
        }
        fun tap(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            draw()
        }
        fun tapZone(fraction: Float) = tap(Offset(width * fraction, height / 2f))
        fun click(label: String) { val node = text(label); reachable(node); tap(node.boundsInRoot.center) }
        fun awaitArtwork(index: Int) {
            repeat(200) { draw(); if (reader.state.value.transition == null && reader.state.value.position.index == index && artwork(index) != null) return; Thread.sleep(5) }
            fail("Current page must be presented: $index")
        }
        fun awaitSettingsClosed() {
            repeat(60) {
                draw()
                if (nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Right-to-left (manga)" } == true }) return
            }
            fail("Exiting settings popup must release its hit-test layer")
        }
        override fun close() { scene.close(); reader.close(); scope.cancel(); assertEquals(1, document.closes) }
    }
    @Test fun mouseTapZonesToggleChromeWithoutShrinkingPageAndButtonsTakePrecedence() {
        Fixture(360, 420).use { f ->
            assertFalse(f.reader.state.value.controlsVisible)
            val bounds = f.canvas().boundsInRoot
            f.tapZone(.9f); f.awaitArtwork(1); assertEquals(1, f.reader.state.value.position.index)
            f.tapZone(.1f); f.awaitArtwork(0); assertEquals(0, f.reader.state.value.position.index)
            f.tapZone(.5f); assertTrue(f.reader.state.value.controlsVisible)
            assertEquals(bounds, f.canvas().boundsInRoot)
            f.click("Next"); f.awaitArtwork(1); assertEquals(1, f.reader.state.value.position.index)
            assertTrue(f.reader.state.value.controlsVisible)
            f.click("Previous"); f.awaitArtwork(0); assertEquals(0, f.reader.state.value.position.index)
            f.tapZone(.5f); assertFalse(f.reader.state.value.controlsVisible)
            assertEquals(0, f.reader.state.value.position.index)
        }
    }
    @Test fun rtlSettingsReverseSpatialTapsButKeepOverlayButtonsLogical() {
        Fixture(360, 420).use { f ->
            f.tapZone(.5f); f.click("Settings"); f.click("Right-to-left (manga)")
            assertEquals(PageReadingMode.PAGED_RTL, f.reader.state.value.settings.mode)
            // Let Material's exiting popup retire its hit-test layer before canvas input.
            f.awaitSettingsClosed()
            f.tapZone(.1f); f.awaitArtwork(1); assertEquals(1, f.reader.state.value.position.index)
            f.tapZone(.9f); f.awaitArtwork(0); assertEquals(0, f.reader.state.value.position.index)
            f.click("Next"); f.awaitArtwork(1); assertEquals(1, f.reader.state.value.position.index)
            f.click("Previous"); f.awaitArtwork(0); assertEquals(0, f.reader.state.value.position.index)
            f.click("Back"); assertTrue(f.backed); assertTrue(f.reader.state.value.frames.isEmpty())
        }
    }
    @Test fun smallPortraitLandscapeAndDesktopKeepEssentialControlsReachable() {
        for ((width, height, scale) in listOf(Triple(320, 280, 1f), Triple(640, 180, 1f), Triple(360, 240, 1.6f), Triple(1280, 800, 1f))) {
            Fixture(width, height, scale).use { f ->
                f.tapZone(.5f)
                for (label in listOf("Back", "Hide", "Previous", "Settings", "Next", "1 / 8")) f.reachable(f.text(label))
                f.click("Next"); f.awaitArtwork(1); assertEquals(1, f.reader.state.value.position.index)
                f.click("Hide"); assertFalse(f.reader.state.value.controlsVisible)
                f.tapZone(.5f); assertTrue(f.reader.state.value.controlsVisible)
            }
        }
    }
    @Test fun accessibilityCanRevealControlsAndNavigateWithoutSpatialTaps() {
        Fixture(360, 420).use { f ->
            assertTrue(assertNotNull(f.canvas().config[SemanticsActions.OnClick].action).invoke()); f.draw()
            f.reachable(f.text("Next"))
            val actions = f.canvas().config[SemanticsActions.CustomActions]
            assertTrue(actions.first { it.label == "Next page" }.action()); f.draw()
            f.awaitArtwork(1); assertEquals(1, f.reader.state.value.position.index)
            assertTrue(actions.first { it.label == "Previous page" }.action()); f.draw()
            f.awaitArtwork(0); assertEquals(0, f.reader.state.value.position.index)
        }
    }
    @Test fun dragMovesBothPagesAndOnlyReleaseSettlesTheTarget() {
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) {
            Fixture(360, 420).use { f ->
                f.reader.mode(mode); f.awaitArtwork(0)
                val sign = if (mode == PageReadingMode.PAGED_LTR) -1 else 1
                val start = Offset(f.width * .5f, f.height * .5f)
                f.scene.sendPointerEvent(PointerEventType.Press, start)
                f.scene.sendPointerEvent(PointerEventType.Move, start + Offset(sign * f.width * .35f, 0f))
                f.draw()
                val t = assertNotNull(f.reader.state.value.transition)
                assertEquals(PageTransitionPhase.DRAGGING, t.phase)
                assertEquals(0, f.reader.state.value.position.index)
                assertEquals(1, t.target)
                assertTrue(t.offset * sign > 0)
                f.reachable(assertNotNull(f.artwork(0)))
                f.reachable(assertNotNull(f.artwork(1)))
                f.scene.sendPointerEvent(PointerEventType.Release, start + Offset(sign * f.width * .35f, 0f))
                f.awaitArtwork(1)
                assertNull(f.artwork(0))
            }
        }
    }
    @Test fun shortDragReturnsAndZoomedPanDoesNotNavigate() {
        Fixture(640, 420).use { f ->
            val start = Offset(f.width * .5f, f.height * .5f)
            f.scene.sendPointerEvent(PointerEventType.Press, start)
            f.scene.sendPointerEvent(PointerEventType.Move, start - Offset(f.width * .04f, 0f))
            f.draw()
            assertEquals(PageTransitionPhase.DRAGGING, f.reader.state.value.transition?.phase)
            // Hold before release: this is a short drag, not a deliberate fling.
            repeat(3) { f.scene.sendPointerEvent(PointerEventType.Move, start - Offset(f.width * .04f, 0f)); f.draw(20) }
            f.scene.sendPointerEvent(PointerEventType.Release, start - Offset(f.width * .04f, 0f))
            f.awaitArtwork(0)
            f.tapZone(.5f); f.click("Settings"); f.click("Zoom in"); f.awaitSettingsClosed()
            f.scene.sendPointerEvent(PointerEventType.Press, start)
            f.scene.sendPointerEvent(PointerEventType.Move, start - Offset(f.width * .4f, 0f))
            f.scene.sendPointerEvent(PointerEventType.Release, start - Offset(f.width * .4f, 0f))
            f.draw(20)
            assertNull(f.reader.state.value.transition)
            assertEquals(0, f.reader.state.value.position.index)
        }
    }
    @Test fun failedTargetReturnsToCurrentAndRapidTurnsKeepOnlyLatestArtwork() {
        Fixture(360, 420).use { f ->
            f.decoder.action = { error("Target unavailable") }
            f.reader.next(); f.reader.next()
            f.awaitArtwork(0)
            assertTrue(f.reader.state.value.navigationFailed)
            assertNull(f.artwork(2))
            f.decoder.action = {}
            f.reader.next(); f.reader.next(); f.reader.next()
            f.awaitArtwork(3)
            assertNull(f.artwork(0)); assertNull(f.artwork(1)); assertNull(f.artwork(2))
            assertTrue(f.reader.retainedPages <= 3)
            f.reader.next(); f.reader.close(); f.draw(20)
            assertTrue(f.reader.state.value.frames.isEmpty())
            assertNull(f.reader.state.value.transition)
            assertNull(f.artwork(4))
        }
    }

    @Test fun rapidNavigationAndDisposalDoNotKeepAnOutgoingArtworkLayer() {
        Fixture(360, 420).use { f ->
            f.reader.navigate(2); f.reader.navigate(4); f.reader.navigate(6)
            f.awaitArtwork(6); f.draw(12)
            val descriptions = f.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription) }.flatten().filter { it.startsWith("Page ") }
            assertEquals(listOf("Page 7 of 8"), descriptions)
            assertTrue(f.reader.retainedPages <= 3)
            f.reader.close(); f.draw()
            assertFalse(f.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Page ") } == true })
        }
    }
}
