// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.collections.*
import org.infinilect.app.covers.*
import org.infinilect.app.discovery.*
import org.infinilect.app.ui.*
import org.infinilect.core.*
import kotlin.test.*

/** Real App -> shell -> Home -> lazy rows, controlled catalog/cover completions.
 * The physical C55 crash is NOT reproduced by a passing shared-Compose test. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HomeDepartureTest {
    private class Source(val cooperative: Boolean, val fails: Boolean) : PublicationSource, DiscoverySource {
        override val id = SourceId("original-departure-fixture")
        override val canBrowseGenres = true
        class Call(val request: DiscoveryRequest, val gate: CompletableDeferred<Unit> = CompletableDeferred())
        val calls = mutableListOf<Call>()
        var cancellations = 0
        override suspend fun discover(request: DiscoveryRequest, token: String?): DiscoveryPage {
            val call = Call(request); calls += call
            try {
                if (cooperative) call.gate.await() else withContext(NonCancellable) { call.gate.await() }
                if (fails) throw java.io.IOException("Synthetic offline source")
                // Missing metadata and a repeated owning ID exercise actual lazy keys.
                val entries = List(12) { i -> DiscoveryEntry(Publication(PublicationId(id, "${request.genre}-$i"),
                    "Original fixture $i 📚", PublicationType.BOOK), thumbnail = "https://example.invalid/cover") }
                return DiscoveryPage(entries + entries.first())
            } catch (e: CancellationException) { cancellations++; throw e }
        }
        fun deliver() { calls.toList().forEach { it.gate.complete(Unit) } }
        override suspend fun search(query: String, pageToken: String?) = error("No legacy requests")
        override suspend fun getPublication(publicationId: PublicationId): Publication? = error("No acquisition")
        override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("No book bytes")
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun tab(scene: ImageComposeScene, target: Destination) {
        val index = applicationDestinations.indexOf(target)
        val tabs = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        assertEquals(5, tabs.size)
        assertTrue(tabs[index].config[SemanticsActions.OnClick].action!!.invoke())
    }
    private enum class Timing { BEFORE_SUCCESS, BEFORE_ERROR, AFTER_SUCCESS, AFTER_ERROR, COOPERATIVE, RETURN_PENDING, GENRES_PENDING }

    private fun scenario(target: Destination, timing: Timing, round: Int) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = Source(timing == Timing.COOPERATIVE, timing in listOf(Timing.BEFORE_ERROR, Timing.AFTER_ERROR))
        val data = FakeCollections()
        repeat(40) { i ->
            val publication = PublicationSnapshot(PublicationId(source.id, "local-$i"), "Original local $i", PublicationType.BOOK)
            data.saved[publication.id] = LibraryEntry(publication, i.toLong())
            data.opened[publication.id] = HistoryEntry(publication, i.toLong())
        }
        val beforeLibrary = data.saved.toMap(); val beforeHistory = data.opened.toMap()
        val collections = ApplicationCollections(data.library, data.history, dispatcher)
        val preferences = ApplicationPreferences(profile = LocalProfile(interfaceLanguage = listOf("en", "es", "ca")[round % 3], setupHandled = true),
            discovery = DiscoveryPreferences(homeEnabled = true, showCatalogOnlySources = true))
        val appearance = ApplicationAppearancePreferences(object : ApplicationAppearanceStore {
            override suspend fun load() = ApplicationThemeMode.LIGHT
            override suspend fun save(mode: ApplicationThemeMode) = true
            override suspend fun loadPreferences() = preferences
            override suspend fun savePreferences(value: ApplicationPreferences) = true
        }, dispatcher)
        val coversPending = CompletableDeferred<Unit>(); val releasing = CompletableDeferred<Unit>()
        var started = 0; var active = 0; var maximum = 0
        val covers = PublicationCovers({
            started++; active++; maximum = maxOf(maximum, active)
            try { coversPending.await(); throw java.io.IOException("Synthetic invalid thumbnail") }
            finally { withContext(NonCancellable) { releasing.await() }; active-- }
        }, dispatcher)
        // Deterministic eviction before Home composes; no bitmap duplication/recycling.
        repeat(24) { i -> val lease = covers.acquire(PublicationId(source.id, "evicted-$i")); covers.release(lease) }
        val owner = ApplicationSources(listOf(SourceOption("Original fixture", source)), collections = collections, appearance = appearance,
            covers = covers, sessionDispatcher = dispatcher) {}
        val app = owner.applicationSession(); val scene = ImageComposeScene(360, 640, coroutineContext = dispatcher)
        var frame = 0L
        fun draw() { repeat(4) { advanceTimeBy(16); runCurrent(); scene.render(++frame * 16_666_667L).close() }; runCurrent() }
        fun bottom() {
            val vertical = nodes(scene).first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null && it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
            // Last four items are small; jumping to the real footer avoids animation timing.
            // Greeting + optional recommendations + recently added + optional genre rows.
            val rows = app.homeDiscovery.state.value.rows
            val index = 2 + (if (rows.any { it.entries.isNotEmpty() }) 1 else 0) + rows.count { it.genre != null }
            vertical.config[SemanticsActions.ScrollToIndex].action!!.invoke(index); draw()
        }
        try {
            scene.setContent { App(owner) }; draw()
            assertTrue(source.calls.isNotEmpty()); assertTrue(app.homeDiscovery.state.value.loading)
            if (timing == Timing.GENRES_PENDING) {
                source.deliver(); draw(); assertTrue(source.calls.any { it.request.genre != null })
            }
            bottom()
            val loading = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == "home-discovery-loading" }
            assertTrue(loading.boundsInRoot.top < 640 && loading.boundsInRoot.bottom > 0, "Observed bottom catalog indicator must actually be in the viewport")
            assertTrue(started > 0, "Cover work must overlap catalog work")
            if (timing in listOf(Timing.BEFORE_SUCCESS, Timing.BEFORE_ERROR)) {
                // Release general batch, then genre batch. No sleeps or live providers.
                source.deliver(); draw(); source.deliver(); draw()
                assertFalse(app.homeDiscovery.state.value.loading)
            }
            tab(scene, target); draw()
            assertEquals(target, app.destination.value); assertFalse(app.homeDiscovery.state.value.loading)
            assertTrue(nodes(scene).none { it.config.getOrNull(SemanticsProperties.TestTag) == "home-discovery-loading" })
            val abandoned = app.homeDiscovery.state.value
            if (timing == Timing.RETURN_PENDING) { tab(scene, Destination.HOME); draw(); assertTrue(app.homeDiscovery.state.value.loading) }
            // Change destination while cancelled covers are inside their release barrier.
            tab(scene, if (target == Destination.SETTINGS) Destination.LIBRARY else Destination.SETTINGS); draw()
            releasing.complete(Unit); coversPending.complete(Unit); source.deliver(); draw()
            assertEquals(abandoned.rows, app.homeDiscovery.state.value.rows, "Stale callback cannot install rows in another destination")
            assertFalse(app.homeDiscovery.state.value.loading)
            tab(scene, Destination.HOME); draw(); source.deliver(); draw(); source.deliver(); draw()
            assertFalse(app.homeDiscovery.state.value.loading)
            app.homeDiscovery.state.value.rows.forEach { row -> assertEquals(row.entries.size, row.entries.map { it.publication.id }.distinct().size) }
            bottom(); draw()
            nodes(scene).mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }.forEach {
                assertTrue(it.value().isFinite() && it.value() >= 0f, "Scroll remains valid after disposal and late responses")
            }
            if (!source.fails) {
                val calls = source.calls.size
                tab(scene, target); draw(); tab(scene, Destination.HOME); draw()
                assertEquals(calls, source.calls.size, "Warm Home must reuse catalog TTL")
            }
            assertEquals(beforeLibrary, data.saved); assertEquals(beforeHistory, data.opened)
            assertTrue(covers.retainedEntries() <= 24); assertEquals(1, maximum)
            if (timing == Timing.COOPERATIVE) assertTrue(source.cancellations > 0)
        } finally {
            source.deliver(); releasing.complete(Unit); coversPending.complete(Unit)
            scene.close(); owner.close(); runCurrent(); source.deliver(); runCurrent(); owner.awaitProgressClosed()
        }
        assertEquals(0, active); assertEquals(0, covers.retainedEntries())
    }
    @Test fun leaveForLibraryWithPendingSuccessFailureAndCleanup() { Timing.entries.forEachIndexed { i, timing -> scenario(Destination.LIBRARY, timing, i) } }
    @Test fun leaveForHistoryWithPendingSuccessFailureAndCleanup() { Timing.entries.forEachIndexed { i, timing -> scenario(Destination.HISTORY, timing, i) } }
    @Test fun leaveForSearchWithPendingSuccessFailureAndCleanup() { Timing.entries.forEachIndexed { i, timing -> scenario(Destination.SEARCH, timing, i) } }
    @Test fun leaveForSettingsWithPendingSuccessFailureAndCleanup() { Timing.entries.forEachIndexed { i, timing -> scenario(Destination.SETTINGS, timing, i) } }
}
