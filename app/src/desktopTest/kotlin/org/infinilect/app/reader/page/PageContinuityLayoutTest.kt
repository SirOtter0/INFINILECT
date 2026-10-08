// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PageContinuityLayoutTest {
    private class Fixture(val scope: TestScope) : AutoCloseable {
        val doc = TestPageDocument(12)
        val r = PageReaderController(doc, scope.backgroundScope, decoder = TestRasterDecoder(), decodeDispatcher = StandardTestDispatcher(scope.testScheduler))
        val scene = ImageComposeScene(360, 640, Density(1f), coroutineContext = StandardTestDispatcher(scope.testScheduler))
        var mounted by mutableStateOf(true)
        lateinit var transform: PageTransform
        suspend fun start() {
            r.initialize(null); r.navigate(6); scope.runCurrent()
            val bitmap = ImageBitmap(2, 2)
            scene.setContent {
                if (mounted) {
                    val state by r.state.collectAsState()
                    val live = remember(r, state.position.index, state.settings) { PageTransform() }
                    transform = live
                    val art = state.frames.filterValues { it is PageFrame.Ready }.mapValues { bitmap }
                    MaterialTheme {
                        if (pagedMode(state.settings.mode)) PagedCanvas(r, state, art, emptySet(), live, Modifier.fillMaxSize())
                        else ContinuousPages(r, state, art, Modifier.fillMaxSize())
                    }
                }
            }; pump()
        }
        fun pump() { repeat(10) { scope.runCurrent(); scene.render(scope.testScheduler.currentTime * 1_000_000).close() }; scope.runCurrent() }
        fun resize(w: Int, h: Int) { scene.constraints = Constraints.fixed(w, h); pump() }
        fun visiblePage(index: Int): SemanticsNode {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            val n = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Page ${index + 1} of 12") == true && it.boundsInRoot.height > 0
            }
            return assertNotNull(n, "Expected logical page $index artwork")
        }
        override fun close() { scene.close(); r.close(); scope.runCurrent(); assertEquals(1, doc.closes); assertEquals(doc.opened.size, doc.closedHandles) }
    }
    @Test fun modeChangesAtNoninitialPageMustNotRestartTheActualViewport() = runTest {
        Fixture(this).use { f ->
            f.start()
            for (mode in listOf(PageReadingMode.VERTICAL, PageReadingMode.PAGED_LTR, PageReadingMode.WEBTOON, PageReadingMode.PAGED_RTL)) {
                f.r.mode(mode); f.pump(); assertEquals(6, f.r.state.value.position.index)
                val b = f.visiblePage(6).boundsInRoot
                assertTrue(b.top < 640 && b.bottom > 0, "Semantic page must be visible after $mode: $b")
                println("$mode controller=${f.r.state.value.position} artwork=$b")
            }
        }
    }
    @Test fun resizeAndPresentationRemountKeepTheNoninitialContinuousPage() = runTest {
        Fixture(this).use { f ->
            f.start(); f.r.mode(PageReadingMode.VERTICAL); f.pump()
            for ((w, h) in listOf(640 to 360, 360 to 640, 1000 to 600)) {
                f.resize(w, h); assertEquals(6, f.r.state.value.position.index)
                val b = f.visiblePage(6).boundsInRoot; assertTrue(b.top < h && b.bottom > 0)
            }
            f.mounted = false; f.pump(); f.mounted = true; f.pump()
            assertEquals(6, f.r.state.value.position.index); assertTrue(f.visiblePage(6).boundsInRoot.bottom > 0)
        }
    }
    @Test fun zoomedActiveTransitionResizeAndModeChangesRetireTemporaryGeometryOnly() = runTest {
        Fixture(this).use { f ->
            f.start(); f.transform.zoom = 2f
            val old = assertNotNull(f.r.beginEdgeDrag(f.r.spreadStamps()[6])); f.transform.edgeTicket = old
            f.r.drag(old, -.3f); f.r.releaseDrag(old, 0f); f.pump()
            f.resize(640, 360)
            assertEquals(6, f.r.state.value.position.index); assertNull(f.r.state.value.transition)
            assertEquals(1f, f.transform.zoom); assertNull(f.transform.edgeTicket)
            f.r.finishTransition(old); assertEquals(6, f.r.state.value.position.index)
            f.transform.zoom = 2f; f.r.mode(PageReadingMode.VERTICAL); f.pump()
            assertEquals(6, f.r.state.value.position.index); assertEquals(1f, f.transform.zoom)
            f.r.mode(PageReadingMode.PAGED_RTL); f.r.layout(PageLayout.DOUBLE); f.pump()
            assertEquals(5, f.r.state.value.position.index)
            f.r.layout(PageLayout.SINGLE); f.pump(); assertEquals(6, f.r.state.value.position.index)
        }
    }
}
