// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.covers.*
import org.infinilect.app.discovery.*
import org.infinilect.app.media.Raster
import org.infinilect.app.ui.*
import org.infinilect.core.*
import kotlin.test.*

/** Public runtime observation and real clipped layouts, not Android frame-time evidence. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalComposeRuntimeApi::class, ExperimentalCoroutinesApi::class)
class HomeRenderWorkTest {
    private class Source : PublicationSource {
        override val id = SourceId("original-home-work")
        override suspend fun search(query: String, pageToken: String?) = SearchPage(emptyList())
        override suspend fun getPublication(publicationId: PublicationId): Publication? = error("No acquisition")
        override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("No acquisition")
    }

    @Test fun queryOnlyChangesDoNotRecomposePresentedHome() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = ApplicationSources(listOf(SourceOption("Original", Source())), sessionDispatcher = dispatcher) {}
        val app = owner.applicationSession()
        val scene = ImageComposeScene(390, 720, Density(1f), coroutineContext = dispatcher)
        val handles = mutableListOf<CompositionObserverHandle>()
        var entered = 0
        val observer = object : CompositionObserver {
            override fun onBeginComposition(composition: ObservableComposition) {}
            override fun onEndComposition(composition: ObservableComposition) {}
            override fun onScopeEnter(scope: RecomposeScope) { entered++ }
            override fun onScopeExit(scope: RecomposeScope) {}
            override fun onReadInScope(scope: RecomposeScope, value: Any) {}
            override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
            override fun onScopeDisposed(scope: RecomposeScope) {}
        }
        var frame = 0L
        fun draw() { runCurrent(); scene.render(++frame * 16_666_667L).close(); runCurrent() }
        try {
            scene.setContent { MaterialTheme { HomeScreen(app, ApplicationAppearanceState(loaded = true,
                profile = LocalProfile(setupHandled = true)), emptyList(), null,
                androidx.compose.foundation.lazy.rememberLazyListState(), null, {}) } }
            repeat(6) { draw() }
            val registrations = Recomposer.runningRecomposers.value.map { info ->
                info.observe(object : CompositionRegistrationObserver {
                    override fun onCompositionRegistered(composition: ObservableComposition) { handles += composition.setObserver(observer) }
                    override fun onCompositionUnregistered(composition: ObservableComposition) {}
                })
            }
            try {
                assertTrue(handles.isNotEmpty(), "The public observer must actually observe the Home scene")
                entered = 0
                repeat(40) { app.discovery.edit("Original query $it"); draw() }
                println("Home query-only updates=40; entered composition scopes=$entered")
                assertEquals(0, entered, "Query/loading/filter-only changes do not change the metadata Home uses")
            } finally { registrations.forEach { it?.dispose() }; handles.forEach { it.dispose() } }
        } finally { scene.close(); owner.close(); runCurrent() }
    }

    @Test fun clippedComposedCoverCannotMonopolizeTheWorkerAfterViewportChanges() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = Source()
        fun book(index: Int) = Publication(PublicationId(source.id, "$index"), "Original $index", PublicationType.BOOK)
        val started = mutableListOf<PublicationId>()
        val cancelled = mutableListOf<PublicationId>()
        var active = 0; var maximum = 0
        val ready = CompletableDeferred<Unit>()
        val covers = PublicationCovers({ id ->
            started += id; active++; maximum = maxOf(maximum, active)
            try {
                if (id == book(0).id) awaitCancellation() else ready.await()
                CoverArtwork(null, Raster(32, 48, IntArray(32 * 48)))
            } catch (e: CancellationException) { cancelled += id; throw e }
            finally { active-- }
        }, dispatcher, convert = { ImageBitmap(it.width, it.height) })
        val position = ScrollState(0)
        // The clip is smaller than the window: window-only visibility is insufficient.
        val scene = ImageComposeScene(360, 640, Density(1f, 1.5f), coroutineContext = dispatcher)
        var frame = 0L
        fun draw() { runCurrent(); scene.render(++frame * 16_666_667L).close(); runCurrent() }
        try {
            scene.setContent { MaterialTheme {
                Box(Modifier.size(144.dp, 216.dp).clipToBounds()) {
                    Column(Modifier.verticalScroll(position)) {
                        repeat(3) { HomeCover(book(it), covers, null, false) {} }
                    }
                }
            } }
            repeat(4) { draw() }
            assertEquals(listOf(book(0).id), started)
            position.scrollTo(216)
            repeat(4) { draw() }
            assertEquals(listOf(book(0).id), cancelled, "A clipped but still composed card must release its pending lease")
            assertEquals(listOf(book(0).id, book(1).id), started, "Visible work must run before a fully clipped neighbor")
            ready.complete(Unit); repeat(3) { draw() }
            assertNotNull(covers.peek(book(1).id).image)
            assertNull(covers.peek(book(2).id).image)
            assertEquals(1, maximum)
        } finally { scene.close(); covers.close(); ready.complete(Unit); runCurrent(); covers.awaitClosed() }
        assertEquals(0, active); assertEquals(0, covers.retainedEntries())
    }

    @Test fun unplacedButComposedCardCancelsAndCanBecomeVisibleAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val book = Publication(PublicationId(Source().id, "unplaced"), "Original", PublicationType.BOOK)
        val placed = mutableStateOf(true)
        var loads = 0; var cancelled = 0
        val covers = PublicationCovers({
            loads++
            try { awaitCancellation() } finally { cancelled++ }
        }, dispatcher)
        val scene = ImageComposeScene(200, 300, Density(1f), coroutineContext = dispatcher)
        var frame = 0L
        fun draw() { runCurrent(); scene.render(++frame * 16_666_667L).close(); runCurrent() }
        try {
            scene.setContent { MaterialTheme {
                Layout(content = { HomeCover(book, covers, null, false) {} }) { measurables, constraints ->
                    val child = measurables.single().measure(constraints)
                    layout(200, 300) { if (placed.value) child.place(0, 0) }
                }
            } }
            repeat(4) { draw() }; assertEquals(1, loads)
            placed.value = false; repeat(4) { draw() }; assertEquals(1, cancelled)
            placed.value = true; repeat(4) { draw() }; assertEquals(2, loads)
        } finally { scene.close(); covers.close(); runCurrent(); covers.awaitClosed() }
        assertEquals(2, cancelled); assertEquals(0, covers.retainedEntries())
    }

    @Test fun visibleDuplicateKeepsSharedWorkWhenAnotherCardLeavesComposition() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val book = Publication(PublicationId(Source().id, "duplicate"), "Original", PublicationType.BOOK)
        val first = mutableStateOf(true)
        val ready = CompletableDeferred<Unit>()
        var loads = 0; var converted = 0; var cancelled = 0
        val covers = PublicationCovers({
            loads++
            try { ready.await(); CoverArtwork(null, Raster(32, 48, IntArray(32 * 48))) }
            catch (e: CancellationException) { cancelled++; throw e }
        }, dispatcher, convert = { converted++; ImageBitmap(it.width, it.height) })
        val scene = ImageComposeScene(360, 300, Density(1f), coroutineContext = dispatcher)
        var frame = 0L
        fun draw() { runCurrent(); scene.render(++frame * 16_666_667L).close(); runCurrent() }
        try {
            scene.setContent { MaterialTheme { Row {
                if (first.value) key("first") { HomeCover(book, covers, null, false) {} }
                key("second") { HomeCover(book, covers, null, false) {} }
            } } }
            repeat(4) { draw() }; assertEquals(1, loads)
            first.value = false; repeat(4) { draw() }; assertEquals(0, cancelled)
            ready.complete(Unit); repeat(4) { draw() }
            assertEquals(1, converted); assertNotNull(covers.peek(book.id).image)
        } finally { scene.close(); covers.close(); ready.complete(Unit); runCurrent(); covers.awaitClosed() }
        assertEquals(1, loads); assertEquals(0, cancelled); assertEquals(0, covers.retainedEntries())
    }
}
