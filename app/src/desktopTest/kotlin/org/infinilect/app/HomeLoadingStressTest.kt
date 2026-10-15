// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.collections.*
import org.infinilect.app.covers.*
import org.infinilect.app.discovery.*
import org.infinilect.app.media.Raster
import org.infinilect.app.ui.*
import org.infinilect.core.*
import kotlin.test.*

/** Shared Compose with actual touch drags and controlled asynchronous responses.
 * No HTTP, physical Android, OOM or device frame-time claim. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HomeLoadingStressTest {
    private class Source(val suffix: String) : PublicationSource, DiscoverySource {
        override val id = SourceId("original-stress-$suffix")
        override val canBrowseGenres = true
        data class Request(val request: DiscoveryRequest, val gate: CompletableDeferred<Unit> = CompletableDeferred())
        val pending = mutableListOf<Request>()
        var failed = false
        var completed = 0
        var cancelled = 0
        val books = List(120) { i -> DiscoveryEntry(Publication(PublicationId(id, "$i"), "Original $suffix publication $i — Unicode 📚",
            PublicationType.BOOK, if (i % 2 == 0) listOf("Original author") else emptyList(),
            languages = if (i % 3 == 0) listOf("ca") else emptyList()), subjects = listOf("Philosophy", "History"),
            thumbnail = "https://example.invalid/owned") }
        override suspend fun discover(request: DiscoveryRequest, token: String?): DiscoveryPage {
            val task = Request(request); pending += task
            try { task.gate.await(); if (failed) error("Controlled source failure"); completed++ }
            catch (error: CancellationException) { cancelled++; throw error }
            val start = if (request.genre == null) 0 else if (request.genre == Genre.HISTORY) 48 else 24
            // Duplicate IDs must be removed before they reach a LazyRow key provider.
            return DiscoveryPage(books.drop(start).take(24) + books[start])
        }
        fun deliver() { pending.toList().forEach { it.gate.complete(Unit) } }
        override suspend fun search(query: String, pageToken: String?) = error("No legacy network")
        override suspend fun getPublication(publicationId: PublicationId): Publication? = error("No metadata acquisition")
        override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("No book acquisition")
    }

    @Test fun coldAndWarmHomeSurviveMixedAxisDragsArrivingResultsFailuresAndTabDisposal() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = Source("first"); val second = Source("second")
        val data = FakeCollections()
        first.books.take(100).forEachIndexed { i, entry ->
            val snapshot = PublicationSnapshot.from(entry.publication)
            data.saved[snapshot.id] = LibraryEntry(snapshot, i.toLong())
            data.opened[snapshot.id] = HistoryEntry(snapshot, i.toLong())
        }
        val libraryBefore = data.saved.toMap(); val historyBefore = data.opened.toMap()
        val collections = ApplicationCollections(data.library, data.history, dispatcher)
        val preferences = ApplicationPreferences(profile = LocalProfile(setupHandled = true), discovery = DiscoveryPreferences(homeEnabled = true))
        val appearance = ApplicationAppearancePreferences(object : ApplicationAppearanceStore {
            override suspend fun load() = ApplicationThemeMode.SYSTEM
            override suspend fun save(mode: ApplicationThemeMode) = true
            override suspend fun loadPreferences() = preferences
            override suspend fun savePreferences(value: ApplicationPreferences) = true
        }, dispatcher)
        var loads = 0; var active = 0; var maximum = 0; var conversionFailures = 0
        var coverGate = CompletableDeferred<Unit>()
        val covers = PublicationCovers({ id ->
            loads++; active++; maximum = maxOf(maximum, active)
            try {
                coverGate.await()
                if (id.localId.toInt() % 7 == 0) error("Controlled thumbnail failure")
                CoverArtwork(null, Raster(32, 48, IntArray(32 * 48)))
            } finally { active-- }
        }, dispatcher, convert = { raster ->
            if (++conversionFailures % 9 == 0) error("Controlled bitmap conversion failure")
            androidx.compose.ui.graphics.ImageBitmap(raster.width, raster.height)
        })
        val owner = ApplicationSources(listOf(SourceOption("First source", first), SourceOption("Second source", second)),
            collections = collections, appearance = appearance, covers = covers, sessionDispatcher = dispatcher) {}
        val app = owner.applicationSession()
        val scene = ImageComposeScene(360, 640, Density(1f), coroutineContext = dispatcher)
        var frame = 0L
        fun pump(count: Int = 3) { repeat(count) { advanceTimeBy(16); runCurrent(); scene.render(++frame * 16_666_667L).close(); runCurrent() } }
        fun nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        fun drag(from: Offset, to: Offset, duringMove: (Int) -> Unit = {}) {
            fun event(type: PointerEventType, at: Offset) = scene.sendPointerEvent(type, at,
                timeMillis = testScheduler.currentTime, type = PointerType.Touch)
            event(PointerEventType.Press, from)
            repeat(5) { step ->
                event(PointerEventType.Move, from + (to - from) * ((step + 1) / 5f))
                duringMove(step); pump(1)
            }
            event(PointerEventType.Release, to); pump(3)
        }
        fun verify() {
            app.homeDiscovery.state.value.rows.forEach { row ->
                assertEquals(row.entries.size, row.entries.map { it.publication.id }.distinct().size)
            }
            nodes().filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }.forEach { node ->
                val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(range.value().isFinite() && range.value() >= 0)
            }
            assertEquals(libraryBefore, data.saved); assertEquals(historyBefore, data.opened)
        }
        try {
            scene.setContent { App(owner) }; pump(6)
            assertTrue(first.pending.isNotEmpty() && second.pending.isNotEmpty())
            repeat(3) { round ->
                // Cold covers, incremental source results during an in-flight drag, then warm browsing.
                first.deliver(); pump(2)
                second.failed = round == 0
                drag(Offset(180f, 520f), Offset(180f, 190f)) { step ->
                    if (step == 1) second.deliver()
                    if (step == 3) coverGate.complete(Unit)
                }
                pump(2)
                first.deliver(); pump(2); first.deliver(); pump(2)
                coverGate.complete(Unit); pump(4)
                repeat(4) { turn ->
                    val horizontal = nodes().firstOrNull { node ->
                        node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null &&
                            node.boundsInRoot.top in 100f..450f
                    }
                    horizontal?.let { row ->
                        val y = (row.boundsInRoot.top + 60f).coerceIn(120f, 500f)
                        drag(Offset(if (turn % 2 == 0) 300f else 60f, y), Offset(if (turn % 2 == 0) 60f else 300f, y))
                    }
                    drag(Offset(180f, if (turn % 2 == 0) 520f else 160f), Offset(180f, if (turn % 2 == 0) 160f else 520f))
                    // Reach distant lazy children as well as the touch/fling route above.
                    // This makes cache pressure deterministic rather than depending on fling physics.
                    nodes().filter { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
                        .forEach { row -> row.config.getOrNull(SemanticsActions.ScrollToIndex)?.action?.invoke(if (turn % 2 == 0) 6 else 0) }
                    pump(4)
                    verify(); assertTrue(covers.retainedEntries() <= 24)
                }
                app.navigate(Destination.SEARCH); pump(3)
                // Requests/results after disposing Home cannot replace a later Home generation.
                first.deliver(); second.deliver(); pump(2)
                app.navigate(Destination.HOME); pump(4)
                app.homeDiscovery.refresh(preferences.discovery, retry = true); pump(2)
                coverGate = CompletableDeferred()
            }
            first.deliver(); second.deliver(); coverGate.complete(Unit); pump(4)
            // Visit every materialized section and distant cards to force >24 distinct IDs.
            // A touch fling alone is platform-dependent and need not reach all three rows.
            repeat(5) { index ->
                nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                    it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
                    .config[SemanticsActions.ScrollToIndex].action!!.invoke(index)
                pump(4)
                nodes().filter { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }.forEach { row ->
                    row.config.getOrNull(SemanticsActions.ScrollToIndex)?.action?.invoke(6)
                }
                pump(4); verify(); assertTrue(covers.retainedEntries() <= 24)
            }
            app.navigate(Destination.SETTINGS); pump(3)
            first.deliver(); second.deliver(); coverGate.complete(Unit); pump(4)
            println("Home stress: loads=$loads; maxActive=$maximum; conversions=$conversionFailures; sourceCompleted=${first.completed + second.completed}; sourceCancelled=${first.cancelled + second.cancelled}")
            verify(); assertEquals(1, maximum); assertTrue(loads > 24, "Exercise eviction, not just a fixed warm viewport")
        } finally {
            scene.close(); owner.close(); first.deliver(); second.deliver(); coverGate.complete(Unit)
            runCurrent(); owner.awaitProgressClosed()
        }
        assertEquals(0, active); assertEquals(0, covers.retainedEntries())
    }
}
