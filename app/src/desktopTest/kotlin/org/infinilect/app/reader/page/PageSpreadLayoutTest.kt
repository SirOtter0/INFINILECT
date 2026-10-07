// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import org.infinilect.app.media.*
import org.infinilect.core.*
import kotlin.test.*

/** Real paired native raster sampling, bitmap conversion and headless Compose layout. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PageSpreadLayoutTest {
    private class Fixture(val width: Int = 640, val height: Int = 420, wide: Set<Int> = emptySet()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var failed: Int? = null
        var opening = -1
        var handles = 0
        val doc = object : PageDocument {
            override val publicationId = PublicationId(SourceId("spread-layout"), "original")
            override val progressId = ReadingProgressId(publicationId, "page-sequence", PublicationFormat.PAGES)
            override val title = "Original spread panels"
            override val pages = (0..7).map { PageEntry(PublicationResource(publicationId, "page-$it", PublicationFormat.PAGES, "image/png",
                pageDimensions = if (it in wide) PageDimensions(8, 4) else PageDimensions(4, 8))) }
            override suspend fun openPage(page: PageEntry): ResourceContent {
                opening = pages.indexOf(page)
                val im = BufferedImage(page.dimensions.width, page.dimensions.height, BufferedImage.TYPE_INT_RGB)
                val bytes = ByteArrayOutputStream().use { out -> ImageIO.write(im, "PNG", out); out.toByteArray() }; im.flush()
                handles++
                return object : ResourceContent {
                    override val sizeBytes = bytes.size.toLong()
                    var at = 0; var closed = false
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (at == bytes.size) return -1
                        val n = minOf(length, bytes.size - at); bytes.copyInto(buffer, offset, at, at + n); at += n; return n
                    }
                    override fun close() { if (!closed) { closed = true; handles-- } }
                }
            }
            override fun close() = Unit
        }
        val native = defaultRasterDecoder()
        val decoder = object : RasterDecoder {
            override suspend fun decode(bytes: ByteArray, mediaType: String) = decodePage(bytes, mediaType, false)
            override suspend fun decodePage(bytes: ByteArray, mediaType: String, paired: Boolean): Raster {
                if (opening == failed) error("Controlled failure")
                return native.decodePage(bytes, mediaType, paired)
            }
        }
        val reader = PageReaderController(doc, scope, decoder = decoder)
        val scene = ImageComposeScene(width, height, Density(1f))
        var frame = 0L
        init {
            runBlocking { reader.initialize(null) }
            scene.setContent { MaterialTheme { PageReader(reader, false, reader::close, "Back") } }
            await(0)
        }
        fun draw(n: Int = 4) { repeat(n) { scene.render(++frame * 16_666_667L).close() } }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            walk(owner.unmergedRootSemanticsNode)
        }
        fun artwork(index: Int) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Page ${index + 1} of 8") == true }
        fun await(anchor: Int) {
            repeat(200) {
                draw()
                if (reader.state.value.transition == null && reader.state.value.position.index == anchor && reader.spread().indices.all { artwork(it) != null }) return
                Thread.sleep(5)
            }
            fail("Spread $anchor must be fully presented")
        }
        fun tap(fraction: Float) {
            val at = Offset(width * fraction, height / 2f)
            scene.sendPointerEvent(PointerEventType.Press, at); scene.sendPointerEvent(PointerEventType.Release, at); draw()
        }
        fun click(label: String) {
            val n = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
            scene.sendPointerEvent(PointerEventType.Press, n.boundsInRoot.center)
            scene.sendPointerEvent(PointerEventType.Release, n.boundsInRoot.center)
            repeat(30) { draw() } // retire the exiting Material popup before canvas events
        }
        override fun close() { scene.close(); reader.close(); scope.cancel(); assertEquals(0, handles) }
    }
    @Test fun settingsPairsPagesFitsBothHalvesAndShowsLogicalRangeInEitherDirection() {
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) Fixture().use { f ->
            f.reader.mode(mode); f.await(0); f.tap(.5f); f.click("Settings"); f.click("Double page"); f.await(0)
            assertEquals(PageLayout.DOUBLE, f.reader.state.value.settings.layout)
            f.click("Next"); f.await(1)
            val first = assertNotNull(f.artwork(1)).boundsInRoot; val second = assertNotNull(f.artwork(2)).boundsInRoot
            assertEquals(first.width, second.width)
            assertTrue(if (mode == PageReadingMode.PAGED_LTR) first.right <= second.left else second.right <= first.left)
            assertTrue(first.left >= 0 && first.right <= f.width && second.left >= 0 && second.right <= f.width)
            assertTrue(f.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "2–3 / 8" } == true })
            f.click("Previous"); f.await(0)
            assertNull(f.artwork(1)); assertNull(f.artwork(2))
        }
    }
    @Test fun widePageStandsAloneAndPairingResumesAfterIt() {
        Fixture(wide = setOf(2)).use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0)
            f.reader.next(); f.await(1); assertNull(f.artwork(2))
            f.reader.next(); f.await(2); assertNull(f.artwork(1)); assertNull(f.artwork(3))
            assertTrue(assertNotNull(f.artwork(2)).boundsInRoot.width > f.width / 2)
            f.reader.next(); f.await(3); assertNotNull(f.artwork(4)); assertNull(f.artwork(2))
        }
    }
    @Test fun dragMovesCompleteSpreadWithIncomingPairOneViewportAway() {
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) Fixture().use { f ->
            f.reader.mode(mode); f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1)
            val before1 = assertNotNull(f.artwork(1)).positionInRoot; val before2 = assertNotNull(f.artwork(2)).positionInRoot
            val sign = if (mode == PageReadingMode.PAGED_LTR) -1 else 1
            val start = Offset(f.width * .5f, f.height * .5f); val end = start + Offset(sign * f.width * .35f, 0f)
            f.scene.sendPointerEvent(PointerEventType.Press, start); f.scene.sendPointerEvent(PointerEventType.Move, end); f.draw()
            val t = assertNotNull(f.reader.state.value.transition); assertEquals(3, t.target); assertEquals(PageTransitionPhase.DRAGGING, t.phase)
            val delta1 = assertNotNull(f.artwork(1)).positionInRoot.x - before1.x
            val delta2 = assertNotNull(f.artwork(2)).positionInRoot.x - before2.x
            assertEquals(delta1, delta2, .01f); assertTrue(delta1 * sign > 0)
            assertNotNull(f.artwork(3)); assertNotNull(f.artwork(4))
            f.scene.sendPointerEvent(PointerEventType.Release, end); f.await(3)
            assertNull(f.artwork(1)); assertNull(f.artwork(2)); assertTrue(f.reader.retainedPages <= 4)
        }
    }
    @Test fun failingSecondPageNeverDisplaysHalfOfTargetPair() {
        Fixture().use { f ->
            f.failed = 4; f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1)
            f.reader.next(); f.await(1)
            assertTrue(f.reader.state.value.navigationFailed); assertNull(f.artwork(3)); assertNull(f.artwork(4))
        }
    }
    @Test fun zoomPanAndResetUseOneSpreadTransformWithoutPageTurn() {
        Fixture().use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1)
            f.tap(.5f)
            // At 1.5x this fitted pair is narrower than the viewport; 2.5x gives real pan range.
            repeat(3) { f.click("Settings"); f.click("Zoom in") }
            f.tap(.9f); assertEquals(1, f.reader.state.value.position.index); assertNull(f.reader.state.value.transition)
            val start = Offset(f.width * .5f, f.height * .5f)
            val before = assertNotNull(f.artwork(1)).positionInRoot.x
            f.scene.sendPointerEvent(PointerEventType.Press, start)
            f.scene.sendPointerEvent(PointerEventType.Move, start + Offset(80f, 20f))
            f.scene.sendPointerEvent(PointerEventType.Release, start + Offset(80f, 20f)); f.draw(20)
            assertEquals(1, f.reader.state.value.position.index); assertNull(f.reader.state.value.transition)
            assertTrue(assertNotNull(f.artwork(1)).positionInRoot.x != before)
            f.click("Settings"); f.click("Reset zoom"); f.tap(.9f); f.await(3)
        }
    }
    @Test fun smallLandscapeAndDesktopKeepPairIndicatorAndControlsReachable() {
        for ((w, h) in listOf(320 to 280, 640 to 180, 1280 to 800)) Fixture(w, h).use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1); f.tap(.5f)
            val indicator = f.nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "2–3 / 8" } == true }
            assertTrue(indicator.boundsInRoot.top < h && indicator.boundsInRoot.bottom > 0)
            f.reader.presentationChanged(); f.await(1); assertNotNull(f.artwork(2))
        }
    }
    @Test fun resizeDuringTurnRetiresOffsetAndReconstructsSameLogicalSpread() {
        Fixture().use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1)
            f.reader.next(); f.draw(1)
            val old = assertNotNull(f.reader.state.value.transition)
            f.scene.constraints = Constraints.fixed(320, 280); f.await(1)
            assertNull(f.reader.state.value.transition)
            f.reader.finishTransition(old.ticket); assertEquals(1, f.reader.state.value.position.index)
            for (page in listOf(1, 2)) {
                val bounds = assertNotNull(f.artwork(page)).boundsInRoot
                assertTrue(bounds.left >= 0 && bounds.right <= 320 && bounds.bottom <= 280)
            }
            f.scene.constraints = Constraints.fixed(640, 180); f.await(1)
            assertEquals(PageLayout.DOUBLE, f.reader.state.value.settings.layout)
            assertEquals(listOf(1, 2), f.reader.spread().indices)
        }
    }
    @Test fun pinchAndMultiplePointersCannotTurnSpreadAndResetRestoresSideTap() {
        Fixture().use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1)
            fun send(type: PointerEventType, a: Offset, b: Offset, first: Boolean = true, second: Boolean = true) {
                f.scene.sendPointerEvent(type, listOf(
                    ComposeScenePointer(PointerId(1), a, first, PointerType.Touch),
                    ComposeScenePointer(PointerId(2), b, second, PointerType.Touch)))
                f.draw()
            }
            send(PointerEventType.Press, Offset(260f, 220f), Offset(380f, 220f))
            send(PointerEventType.Move, Offset(180f, 220f), Offset(460f, 220f))
            send(PointerEventType.Move, Offset(80f, 240f), Offset(360f, 240f))
            send(PointerEventType.Release, Offset(80f, 240f), Offset(360f, 240f), false, false)
            assertEquals(1, f.reader.state.value.position.index); assertNull(f.reader.state.value.transition)
            f.tap(.5f); f.click("Settings"); f.click("Reset zoom"); f.tap(.9f); f.await(3)
        }
    }

    @Test fun compactPairHasSmallGutterAspectRatioAndCenteredCombinedRectangle() {
        for ((w, h) in listOf(640 to 180, 320 to 480, 1280 to 720))
            for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) Fixture(w, h).use { f ->
                f.reader.mode(mode); f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1)
                val a = assertNotNull(f.artwork(1)).boundsInRoot; val b = assertNotNull(f.artwork(2)).boundsInRoot
                val left = if (mode == PageReadingMode.PAGED_LTR) a else b
                val right = if (mode == PageReadingMode.PAGED_LTR) b else a
                assertTrue(right.left - left.right in 0f..3f, "Only a tiny gutter")
                assertEquals(.5f, a.width / a.height, .01f, "Portrait aspect must fill its own compact box")
                assertEquals(.5f, b.width / b.height, .01f)
                assertEquals(w / 2f, (left.left + right.right) / 2, 1f)
                assertEquals(h / 2f, (a.top + a.bottom) / 2, 1f)
                assertTrue(a.left >= 0 && b.left >= 0 && a.right <= w && b.right <= w && a.bottom <= h && b.bottom <= h)
            }
    }
    @Test fun zoomedSameGesturePansThenOffersOnlyEdgeExcessToPager() {
        Fixture().use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1)
            f.tap(.5f); repeat(2) { f.click("Settings"); f.click("Zoom in") }
            val start = Offset(320f, 210f)
            f.scene.sendPointerEvent(PointerEventType.Press, start)
            f.scene.sendPointerEvent(PointerEventType.Move, start - Offset(60f, 0f)); f.draw()
            assertNull(f.reader.state.value.transition, "Pan range must be consumed first")
            f.scene.sendPointerEvent(PointerEventType.Move, start - Offset(280f, 0f)); f.draw()
            assertEquals(3, assertNotNull(f.reader.state.value.transition, "Continued edge drag must hand off").target)
            f.scene.sendPointerEvent(PointerEventType.Release, start - Offset(280f, 0f)); f.await(3)
        }
    }

    private fun Fixture.zoom(times: Int = 2) {
        if (!reader.state.value.controlsVisible) tap(.5f)
        repeat(times) { click("Settings"); click("Zoom in") }
    }
    private fun Fixture.edgeMotion(sign: Float, fraction: Float): Offset {
        val viewport = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat())
        val fit = fitPageSpread(reader.spread().indices.map { doc.pages[it].dimensions }, viewport, 2f)
        val bound = pagePanBounds(fit.size, viewport, 2f).x
        val start = Offset(width / 2f, height / 2f)
        scene.sendPointerEvent(PointerEventType.Press, start)
        val end = start + Offset(sign * (bound + width * fraction), 0f)
        scene.sendPointerEvent(PointerEventType.Move, end); draw()
        return end
    }
    @Test fun zoomedNextAndPreviousInEitherDirectionResetNewSpreadToFit() {
        for (mode in listOf(PageReadingMode.PAGED_LTR, PageReadingMode.PAGED_RTL)) for (target in listOf(1, 5)) Fixture().use { f ->
            f.reader.mode(mode); f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.navigate(3); f.await(3)
            f.zoom(); val sign = -pageIncomingSide(3, target, mode).toFloat()
            val end = f.edgeMotion(sign, .3f)
            assertEquals(target, f.reader.state.value.transition?.target)
            f.scene.sendPointerEvent(PointerEventType.Release, end); f.await(target)
            val fit = fitPageSpread(f.reader.spread().indices.map { f.doc.pages[it].dimensions }, androidx.compose.ui.geometry.Size(640f, 420f), 2f)
            val first = f.reader.spread().visualOrder(mode).first()
            assertEquals((640f - fit.size.width) / 2, assertNotNull(f.artwork(first)).positionInRoot.x, 1f)
            f.tap(if (mode == PageReadingMode.PAGED_LTR) .9f else .1f); f.await(target + 2)
        }
    }
    @Test fun returnedZoomEdgeAndFailedTargetPreserveZoomAndBoundaryPan() {
        for (fail in listOf(false, true)) Fixture().use { f ->
            if (fail) f.failed = 4
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1); f.zoom()
            val end = f.edgeMotion(-1f, if (fail) .3f else .08f)
            val t = assertNotNull(f.reader.state.value.transition)
            val edgePosition = assertNotNull(f.artwork(1)).positionInRoot.x - t.offset * f.width
            // Remove fling velocity so the small excess exercises the positional return.
            repeat(3) { f.scene.sendPointerEvent(PointerEventType.Move, end); f.draw(20) }
            f.scene.sendPointerEvent(PointerEventType.Release, end); f.await(1)
            assertEquals(edgePosition, assertNotNull(f.artwork(1)).positionInRoot.x, 1f)
            assertEquals(fail, f.reader.state.value.navigationFailed)
            f.tap(.9f); assertNull(f.reader.state.value.transition); assertEquals(1, f.reader.state.value.position.index)
        }
    }
    @Test fun narrowZoomedContentCanOverscrollWhileVerticalPanCannotTurn() {
        Fixture(640, 180).use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1); f.zoom()
            val start = Offset(320f, 90f)
            f.scene.sendPointerEvent(PointerEventType.Press, start)
            f.scene.sendPointerEvent(PointerEventType.Move, start + Offset(40f, 100f)); f.draw()
            assertNull(f.reader.state.value.transition)
            f.scene.sendPointerEvent(PointerEventType.Release, start + Offset(40f, 100f)); f.await(1)
            val end = f.edgeMotion(-1f, .3f); assertEquals(3, f.reader.state.value.transition?.target)
            f.scene.sendPointerEvent(PointerEventType.Release, end); f.await(3)
        }
    }
    @Test fun secondPointerRetiresAlreadyStartedEdgeTurnAndCannotCommitOnRelease() {
        Fixture().use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1); f.zoom()
            fun send(type: PointerEventType, a: Offset, b: Offset, first: Boolean, second: Boolean) {
                f.scene.sendPointerEvent(type, listOf(ComposeScenePointer(PointerId(1), a, first, PointerType.Touch),
                    ComposeScenePointer(PointerId(2), b, second, PointerType.Touch))); f.draw()
            }
            send(PointerEventType.Press, Offset(320f, 210f), Offset(400f, 210f), true, false)
            send(PointerEventType.Move, Offset(20f, 210f), Offset(400f, 210f), true, false)
            val old = assertNotNull(f.reader.state.value.transition)
            send(PointerEventType.Press, Offset(20f, 210f), Offset(400f, 210f), true, true)
            assertNull(f.reader.state.value.transition)
            send(PointerEventType.Move, Offset(0f, 210f), Offset(440f, 210f), true, true)
            send(PointerEventType.Release, Offset(0f, 210f), Offset(440f, 210f), false, false)
            f.await(1); f.reader.finishTransition(old.ticket); assertEquals(1, f.reader.state.value.position.index)
        }
    }
    @Test fun compactLayoutRecomputesAfterResizeAndEdgeWorkCannotSurviveResizeLayoutOrBack() {
        for (action in 0..3) Fixture().use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1); f.zoom()
            val end = f.edgeMotion(-1f, .3f); val old = assertNotNull(f.reader.state.value.transition)
            when (action) {
                0 -> f.scene.constraints = Constraints.fixed(640, 180)
                1 -> f.reader.layout(PageLayout.SINGLE)
                2 -> f.reader.mode(PageReadingMode.PAGED_RTL)
                3 -> f.reader.close()
            }
            f.draw(10); f.reader.finishTransition(old.ticket)
            assertNull(f.reader.state.value.transition); assertEquals(1, f.reader.state.value.position.index)
            f.scene.sendPointerEvent(PointerEventType.Release, end)
            if (action == 0) {
                f.await(1)
                val a = assertNotNull(f.artwork(1)).boundsInRoot; val b = assertNotNull(f.artwork(2)).boundsInRoot
                assertTrue(b.left - a.right in 0f..3f); assertEquals(.5f, a.width / a.height, .01f)
            }
        }
    }

    @Test fun singleZoomSideTapsStayBlockedCenterWorksAndEdgeTurnUsesFitReset() {
        for (switchLayout in listOf(false, true)) Fixture().use { f ->
            f.zoom(4) // Fitted single portrait remains narrower than this viewport at 3x.
            f.tap(.9f); assertEquals(0, f.reader.state.value.position.index); assertNull(f.reader.state.value.transition)
            f.tap(.5f); assertFalse(f.reader.state.value.controlsVisible)
            f.tap(.5f); assertTrue(f.reader.state.value.controlsVisible)
            val end = f.edgeMotion(-1f, .3f); val old = assertNotNull(f.reader.state.value.transition)
            assertEquals(1, old.target)
            if (switchLayout) {
                f.reader.layout(PageLayout.DOUBLE); f.draw(); f.reader.finishTransition(old.ticket)
                assertNull(f.reader.state.value.transition); assertEquals(0, f.reader.state.value.position.index)
                f.scene.sendPointerEvent(PointerEventType.Release, end); f.await(0)
            } else {
                f.scene.sendPointerEvent(PointerEventType.Release, end); f.await(1)
                // Single artwork retains its full-viewport box; fit reset restores that box to origin.
                assertEquals(0f, assertNotNull(f.artwork(1)).positionInRoot.x, 1f)
            }
        }
    }
    @Test fun reversingZoomEdgeDisplacementResumesPanWithoutTurningOrResettingZoom() {
        Fixture().use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1); f.zoom()
            val end = f.edgeMotion(-1f, .2f)
            f.scene.sendPointerEvent(PointerEventType.Move, end + Offset(160f, 0f)); f.draw()
            assertEquals(0f, assertNotNull(f.reader.state.value.transition).offset)
            f.scene.sendPointerEvent(PointerEventType.Release, end + Offset(160f, 0f)); f.await(1)
            assertEquals(-172f, assertNotNull(f.artwork(1)).positionInRoot.x, 1f)
        }
    }
    @Test fun grabbingSettlingZoomEdgeRetiresOldTicketAndCanReverseBackToCurrentSpread() {
        Fixture().use { f ->
            f.reader.layout(PageLayout.DOUBLE); f.await(0); f.reader.next(); f.await(1); f.zoom()
            val end = f.edgeMotion(-1f, .3f)
            f.scene.sendPointerEvent(PointerEventType.Release, end); f.draw()
            val old = assertNotNull(f.reader.state.value.transition)
            assertTrue(old.phase == PageTransitionPhase.SETTLING || old.phase == PageTransitionPhase.WAITING)
            val start = Offset(320f, 210f)
            f.scene.sendPointerEvent(PointerEventType.Press, start); f.draw()
            val grabbed = assertNotNull(f.reader.state.value.transition)
            assertTrue(grabbed.ticket > old.ticket); assertEquals(old.target, grabbed.target)
            val cancelAt = start - Offset(grabbed.offset * f.width, 0f)
            f.scene.sendPointerEvent(PointerEventType.Move, cancelAt); f.draw()
            f.reader.finishTransition(old.ticket); assertEquals(1, f.reader.state.value.position.index)
            f.scene.sendPointerEvent(PointerEventType.Release, cancelAt); f.await(1)
            assertTrue(f.reader.retainedPages <= 4)
        }
    }

}
