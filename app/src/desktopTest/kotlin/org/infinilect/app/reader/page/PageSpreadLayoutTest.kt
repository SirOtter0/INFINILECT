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
            f.tap(.5f); f.click("Settings"); f.click("Zoom in")
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

}
