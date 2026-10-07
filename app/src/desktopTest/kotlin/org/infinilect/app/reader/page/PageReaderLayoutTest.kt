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
        val reader = PageReaderController(document, scope, decoder = TestRasterDecoder())
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
            repeat(200) { draw(); if (artwork(index) != null) return; Thread.sleep(5) }
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
            f.tapZone(.9f); assertEquals(1, f.reader.state.value.position.index); f.awaitArtwork(1)
            f.tapZone(.1f); assertEquals(0, f.reader.state.value.position.index); f.awaitArtwork(0)
            f.tapZone(.5f); assertTrue(f.reader.state.value.controlsVisible)
            assertEquals(bounds, f.canvas().boundsInRoot)
            f.click("Next"); assertEquals(1, f.reader.state.value.position.index); f.awaitArtwork(1)
            assertTrue(f.reader.state.value.controlsVisible)
            f.click("Previous"); assertEquals(0, f.reader.state.value.position.index)
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
            f.tapZone(.1f); assertEquals(1, f.reader.state.value.position.index)
            f.tapZone(.9f); assertEquals(0, f.reader.state.value.position.index)
            f.click("Next"); assertEquals(1, f.reader.state.value.position.index)
            f.click("Previous"); assertEquals(0, f.reader.state.value.position.index)
            f.click("Back"); assertTrue(f.backed); assertTrue(f.reader.state.value.frames.isEmpty())
        }
    }
    @Test fun smallPortraitLandscapeAndDesktopKeepEssentialControlsReachable() {
        for ((width, height, scale) in listOf(Triple(320, 280, 1f), Triple(640, 180, 1f), Triple(360, 240, 1.6f), Triple(1280, 800, 1f))) {
            Fixture(width, height, scale).use { f ->
                f.tapZone(.5f)
                for (label in listOf("Back", "Hide", "Previous", "Settings", "Next", "1 / 8")) f.reachable(f.text(label))
                f.click("Next"); assertEquals(1, f.reader.state.value.position.index)
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
            assertEquals(1, f.reader.state.value.position.index)
            assertTrue(actions.first { it.label == "Previous page" }.action()); f.draw()
            assertEquals(0, f.reader.state.value.position.index)
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
