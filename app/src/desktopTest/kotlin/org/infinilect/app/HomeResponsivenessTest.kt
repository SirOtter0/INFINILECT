// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.covers.*
import org.infinilect.app.discovery.*
import org.infinilect.app.media.Raster
import org.infinilect.app.ui.*
import org.infinilect.core.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Actual Home composables, synthetic metadata/art only, no network/native translation.
 * Software Skia on a Linux host is NOT an Android frame-time benchmark. Timing has no pass threshold. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HomeResponsivenessTest {
    @Test fun readyCacheHitPresentsArtworkInTheFirstComposition() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val image = ImageBitmap(32, 48)
        val id = PublicationId(SourceId("original"), "ready")
        var loads = 0
        val covers = PublicationCovers({ loads++; CoverArtwork(PublicationFormat.EPUB, Raster(32, 48, IntArray(32 * 48))) }, dispatcher, { image })
        val warm = covers.acquire(id); runCurrent(); covers.release(warm)
        val seen = mutableListOf<CoverState>()
        val scene = ImageComposeScene(100, 100, Density(1f), coroutineContext = dispatcher)
        try {
            scene.setContent { val state = coverState(id, covers); SideEffect { seen += state } }
            scene.render(0).close()
            assertSame(image, seen.first().image, "A warm card must not lay out its typographic placeholder first")
            runCurrent(); assertEquals(1, loads)
        } finally { scene.close(); covers.close(); runCurrent(); covers.awaitClosed() }
    }

    @Test fun cachedArtworkScrollingDoesNotReloadOrAccumulateCoverResources() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var requests = 0
        val source = object : PublicationSource, DiscoverySource {
            override val id = SourceId("original-performance-fixture")
            override val canBrowseGenres = true
            val books = List(12) { DiscoveryEntry(Publication(PublicationId(id, "$it"),
                "Original illustrated publication $it", PublicationType.BOOK, listOf("Fixture author"), languages = listOf("en")),
                subjects = listOf("History", "Philosophy"), thumbnail = "https://example.invalid/owned-cover") }
            override suspend fun discover(request: DiscoveryRequest, token: String?): DiscoveryPage { requests++; return DiscoveryPage(books) }
            override suspend fun search(query: String, pageToken: String?) = error("No network")
            override suspend fun getPublication(publicationId: PublicationId): Publication? = error("No details")
            override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("No acquisition")
        }
        var loads = 0
        val covers = PublicationCovers({ loads++; CoverArtwork(null, Raster(192, 288, IntArray(192 * 288))) }, dispatcher,
            convert = { ImageBitmap(it.width, it.height) })
        val owner = ApplicationSources(listOf(SourceOption("Fixture", source)), covers = covers, sessionDispatcher = dispatcher) {}
        val app = owner.applicationSession()
        val preferences = ApplicationAppearanceState(loaded = true, profile = LocalProfile(setupHandled = true),
            discovery = DiscoveryPreferences(homeEnabled = true))
        val position = LazyListState()
        val scene = ImageComposeScene(390, 720, Density(1f), coroutineContext = dispatcher)
        var frame = 0L
        fun draw() { runCurrent(); scene.render(++frame * 16_666_667L).close(); runCurrent() }
        suspend fun scroll() {
            repeat(60) { step -> position.scrollBy(if (step < 30) 30f else -30f); draw() }
        }
        try {
            // Warm every thumbnail, retaining exactly the same 12 IDs across three real Home rows.
            for (entry in source.books) { val lease = covers.acquire(entry.publication.id); runCurrent(); covers.release(lease) }
            scene.setContent { MaterialTheme { HomeScreen(app, preferences, emptyList(), null, position, null, {}) } }
            repeat(8) { draw() }
            scroll()
            assertEquals(12, loads)
            val calls = requests
            val bean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
                ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
            val tid = Thread.currentThread().threadId()
            val samples = List(5) {
                val bytes = bean?.getThreadAllocatedBytes(tid)
                val start = System.nanoTime()
                scroll()
                (System.nanoTime() - start) to bytes?.let { bean.getThreadAllocatedBytes(tid) - it }
            }
            assertEquals(calls, requests, "Scrolling cannot restart catalog requests")
            assertEquals(12, loads, "Cached scrolling cannot decode again")
            assertEquals(12, covers.retainedEntries())
            System.getenv("INFINILECT_PERFORMANCE_DIRECTORY")?.let { directory ->
                val path = Path.of(directory); require(path.isAbsolute && !path.startsWith(Path.of(System.getProperty("user.dir"))))
                Files.createDirectories(path)
                Files.writeString(path.resolve("home-scroll.txt"),
                    "JDK=${System.getProperty("java.version")}; OS=${System.getProperty("os.name")}; arch=${System.getProperty("os.arch")}\n" +
                    "390x720, density=1, 3 rows x 12 repeated IDs; all 12 covers warm; no network; software Skia\n" +
                    "5 samples, each 60 scrollBy(30px)/render steps (30 down/30 up); first warm traversal excluded\n" +
                    samples.joinToString("\n") { "ns=${it.first}; current-thread allocated bytes=${it.second}" } +
                    "\nrequests=$requests; cover loads=$loads; retained=${covers.retainedEntries()}\nNot Android FPS/jank evidence.\n")
            }
        } finally { scene.close(); owner.close(); covers.close(); runCurrent(); covers.awaitClosed() }
        assertEquals(0, covers.retainedEntries())
    }
}
