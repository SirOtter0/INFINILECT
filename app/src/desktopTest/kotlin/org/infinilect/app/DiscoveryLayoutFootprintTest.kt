// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.test.*
import org.infinilect.app.covers.*
import org.infinilect.app.discovery.*
import org.infinilect.app.media.Raster
import org.infinilect.app.ui.*
import org.infinilect.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** Diagnostic footprint/visual parity of real tiles. No platform-font golden is committed. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscoveryLayoutFootprintTest {
    @Test fun loadedTilesRetainMetadataAndAppearanceInBothThemesAndLargeFonts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = object : PublicationSource {
            override val id = SourceId("original-layout-footprint")
            override suspend fun search(query: String, pageToken: String?) = error("No network")
            override suspend fun getPublication(publicationId: PublicationId): Publication? = error("No acquisition")
            override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("No acquisition")
        }
        val publication = Publication(PublicationId(source.id, "original"), "Original long Unicode title 📚 — é",
            PublicationType.BOOK, listOf("Original author"), languages = listOf("es", "ca"))
        val image = ImageBitmap(192, 288)
        Canvas(image).drawRect(0f, 0f, 192f, 288f, Paint().apply { color = Color(0xff40686a) })
        val covers = PublicationCovers({ CoverArtwork(null, Raster(192, 288, IntArray(192 * 288))) }, dispatcher, convert = { image })
        val warm = covers.acquire(publication.id); runCurrent(); covers.release(warm)
        val owner = ApplicationSources(listOf(SourceOption("Original source", source)), covers = covers, sessionDispatcher = dispatcher) {}
        val app = owner.applicationSession()
        try { for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.5f)) {
            val scene = ImageComposeScene(300, 600, Density(1f, fontScale), coroutineContext = dispatcher)
            try {
                scene.setContent { MaterialTheme(colors = applicationColors(dark)) {
                    DiscoveryTile(DiscoveryEntry(publication, thumbnail = "https://example.invalid/owned"), app, Modifier.width(180.dp)) {}
                } }
                repeat(4) { runCurrent(); scene.render(it * 16_666_667L).close() }
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                val texts = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                assertTrue(publication.title in texts); assertTrue("Original author" in texts)
                assertTrue("Original source · catalog only" in texts); assertTrue("es, ca" in texts)
                val title = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == publication.title } == true }
                var info = title.layoutInfo
                var depth = 0
                while (info.parentInfo != null) { info = info.parentInfo!!; depth++ }
                val label = "dark-$dark-font-$fontScale"
                scene.render(100_000_000L).use { frame -> frame.encodeToData()!!.use { png ->
                    val bytes = png.bytes
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    println("Tile $label: titleLayoutAncestors=$depth; titleBounds=${title.boundsInRoot}; pngSha256=$hash")
                    System.getenv("INFINILECT_PERFORMANCE_DIRECTORY")?.let { dir ->
                        val path = Path.of(dir); require(path.isAbsolute && !path.startsWith(Path.of(System.getProperty("user.dir"))))
                        Files.createDirectories(path); Files.write(path.resolve("tile-$label.png"), bytes)
                    }
                } }
            } finally { scene.close() }
        } } finally { owner.close(); runCurrent(); covers.awaitClosed() }
    }
}
