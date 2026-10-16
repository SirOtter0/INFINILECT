// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.collections.*
import org.infinilect.app.covers.*
import org.infinilect.app.media.Raster
import org.infinilect.app.ui.*
import org.infinilect.core.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 1,000 original synthetic publications, bounded cache, errors and tab disposal.
 * Diagnostic times include host scheduling/layout/render; never Android FPS. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class LargeLibraryResponsivenessTest {
    @Test fun thousandPublicationsKeepScrollOwnershipAndStorageDuringRepeatedEviction() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val data = FakeCollections()
        val source = object : PublicationSource {
            override val id = SourceId("original-thousand-fixture")
            override suspend fun search(query: String, pageToken: String?) = error("No network")
            override suspend fun getPublication(publicationId: PublicationId): Publication? = error("No acquisition")
            override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("No acquisition")
        }
        repeat(1_000) { index ->
            val publication = Publication(PublicationId(source.id, "$index"), "Original publication $index 📚 with a long title",
                PublicationType.BOOK, if (index % 3 == 0) emptyList() else listOf("Original author"))
            val snapshot = PublicationSnapshot.from(publication)
            data.saved[snapshot.id] = LibraryEntry(snapshot, index.toLong())
        }
        val before = data.saved.toMap()
        val collections = ApplicationCollections(data.library, data.history, dispatcher)
        var loads = 0; var converted = 0; var active = 0; var maximum = 0; var cancelled = 0
        val attempts = mutableMapOf<PublicationId, Int>()
        val covers = PublicationCovers({ id ->
            loads++; attempts[id] = (attempts[id] ?: 0) + 1; active++; maximum = maxOf(maximum, active)
            try {
                yield()
                if (id.localId.toInt() % 11 == 0) error("Controlled malformed artwork")
                CoverArtwork(null, Raster(192, 288, IntArray(192 * 288)))
            } catch (e: CancellationException) { cancelled++; throw e }
            finally { active-- }
        }, dispatcher, convert = { converted++; ImageBitmap(it.width, it.height) })
        val owner = ApplicationSources(listOf(SourceOption("Original", source)), collections = collections,
            covers = covers, sessionDispatcher = dispatcher) {}
        val app = owner.applicationSession()
        val position = LazyGridState()
        val scene = ImageComposeScene(390, 720, Density(1f), coroutineContext = dispatcher)
        var frame = 0L
        fun draw() { runCurrent(); scene.render(++frame * 16_666_667L).close(); runCurrent() }
        try {
            app.navigate(Destination.LIBRARY); runCurrent()
            scene.setContent { MaterialTheme { CollectionScreen(Destination.LIBRARY, app, position) } }
            repeat(6) { draw() }
            val bean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
                ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
            val tid = Thread.currentThread().threadId()
            val samples = List(5) {
                val bytes = bean?.getThreadAllocatedBytes(tid)
                val start = System.nanoTime()
                for (index in listOf(0, 200, 600, 998, 600, 200, 0)) {
                    position.scrollToItem(index); repeat(3) { draw() }
                    assertTrue(covers.retainedEntries() <= 24)
                }
                (System.nanoTime() - start) to bytes?.let { bean.getThreadAllocatedBytes(tid) - it }
            }
            val index = position.firstVisibleItemIndex; val pixels = position.firstVisibleItemScrollOffset
            scene.setContent { MaterialTheme {} }; draw()
            scene.setContent { MaterialTheme { CollectionScreen(Destination.LIBRARY, app, position) } }; repeat(4) { draw() }
            assertEquals(index, position.firstVisibleItemIndex); assertEquals(pixels, position.firstVisibleItemScrollOffset)
            assertEquals(before, data.saved); assertTrue(data.opened.isEmpty())
            assertEquals(1, maximum); assertTrue(loads > 24)
            val result = samples.joinToString("\n") { "ns=${it.first}; current-thread allocated bytes=${it.second}" } +
                "\nloads=$loads; repeatedLoads=${attempts.values.sumOf { it - 1 }}; conversions=$converted; cancelled=$cancelled; maxWorker=$maximum; retained=${covers.retainedEntries()}\n"
            println("Large Library host samples:\n$result")
            System.getenv("INFINILECT_PERFORMANCE_DIRECTORY")?.let { dir ->
                val path = Path.of(dir); require(path.isAbsolute && !path.startsWith(Path.of(System.getProperty("user.dir"))))
                Files.createDirectories(path); Files.writeString(path.resolve("large-library.txt"), result + "Not Android frame timings or a native-memory measurement.\n")
            }
        } finally { scene.close(); owner.close(); runCurrent(); owner.awaitProgressClosed() }
        assertEquals(0, active); assertEquals(0, covers.retainedEntries())
    }
}
