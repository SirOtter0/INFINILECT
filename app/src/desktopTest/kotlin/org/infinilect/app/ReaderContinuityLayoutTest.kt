// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.epub.*
import org.infinilect.app.reader.pdf.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

/** Real presentation lifecycle/reflow with synthetic semantics and virtual clocks. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderContinuityLayoutTest {
    private class Scene(val scope: TestScope) : AutoCloseable {
        val scene = ImageComposeScene(700, 600, Density(1f), coroutineContext = StandardTestDispatcher(scope.testScheduler))
        var mounted by mutableStateOf(true)
        var density by mutableStateOf(Density(1f))
        fun content(body: @Composable () -> Unit) { scene.setContent { if (mounted) CompositionLocalProvider(LocalDensity provides density) { MaterialTheme { body() } } }; pump() }
        fun pump() { repeat(10) { scope.runCurrent(); scene.render(scope.testScheduler.currentTime * 1_000_000).close() }; scope.runCurrent() }
        fun advance(ms: Long) { scope.advanceTimeBy(ms); pump() }
        fun remount() { mounted = false; pump(); mounted = true; pump() }
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        fun text(n: SemanticsNode, label: String) = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        fun click(label: String) {
            // Search submission is a content action; the new Search navigation tab is a separate control.
            val node = assertNotNull(nodes().firstOrNull { !it.config.contains(SemanticsProperties.Disabled) &&
                it.config.getOrNull(SemanticsProperties.Role) != Role.Tab &&
                it.config.getOrNull(SemanticsActions.OnClick)?.action != null && walk(it).any { child -> text(child, label) } },
                "Enabled control $label must be present")
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); pump()
        }
        fun scroll(pixels: Float) {
            val node = nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            assertTrue(node.config[SemanticsActions.ScrollBy].action!!.invoke(0f, pixels))
            repeat(30) { advance(16) }
        }
        fun firstTextLine(text: String, topPadding: Float = 0f): IntRange {
            val node = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
            val viewport = nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            val layout = layouts.single()
            val y = (viewport.positionInRoot.y + topPadding - node.positionInRoot.y).coerceAtLeast(0f)
            val line = layout.getLineForVerticalPosition(y)
            val points = TextLocations(text)
            return points.locator(layout.getLineStart(line)).codePointOffset.toInt() until
                points.locator(layout.getLineEnd(line)).codePointOffset.toInt()
        }
        override fun close() { scene.close(); scope.runCurrent() }
    }
    private class Store : ReadingProgressStore {
        val values = mutableMapOf<ReadingProgressId, ReadingProgress>()
        override suspend fun get(id: ReadingProgressId) = values[id]
        override suspend fun save(progress: ReadingProgress): Boolean { values[progress.id] = progress; return true }
        override suspend fun remove(id: ReadingProgressId) = values.remove(id) != null
    }
    private val pub = PublicationId(SourceId("fixture"), "original")
    private val original = (0..100).joinToString(" ") { "Original 📖 passage $it describes a blue circle and a green square." }

    @Test fun textReflowPreservesTheLiveCodePointInsteadOfTheOldPixelOffset() = runTest {
        val id = ReadingProgressId(pub, "text", PublicationFormat.TEXT)
        val doc = TextDocument(pub, "Original", original, id)
        val store = Store(); val writer = ProgressPersistence(store, StandardTestDispatcher(testScheduler)) { 10 }
        val reading = TextReadingProgress(doc, ReadingProgress(id, ReadingLocator.Text(1200, doc.codePoints.toLong()), .1, 1), writer, backgroundScope)
        try { Scene(this).use { f ->
            f.content { TextReader(doc, reading, false, {}) }
            assertTrue(1200 in f.firstTextLine(original))
            f.scroll(160f); val live = reading.codePointOffset.value; assertTrue(live > 1200)
            f.scene.constraints = Constraints.fixed(350, 600); f.pump()
            val line = f.firstTextLine(original)
            println("Text reflow live=$live first-line=$line")
            assertTrue(live in line, "Reflow must restore the line containing the live code point")
            assertEquals(live, reading.codePointOffset.value)
            f.remount(); assertTrue(live in f.firstTextLine(original))
            for ((width, font) in listOf(900 to 1f, 350 to 1.5f, 700 to 1f)) {
                f.scene.constraints = Constraints.fixed(width, 600); f.density = Density(1f, font); f.pump()
                val resizedLine = f.firstTextLine(original)
                assertTrue(live in resizedLine, "TEXT width=$width font=$font live=$live first=$resizedLine")
                assertEquals(live, reading.codePointOffset.value)
            }
        } } finally { reading.close(); writer.close(); writer.awaitClosed(); doc.close() }
    }
    @Test fun epubRemountRestoresTheLatestSemanticLocationRatherThanChapterEntry() = runTest {
        val path = EpubEntryPath("OPS/ch.xhtml"); val id = ReadingProgressId(pub, "epub", PublicationFormat.EPUB)
        val doc = object : EpubDocument {
            override val publicationId = pub
            override val packagePath = EpubEntryPath("OPS/book.opf")
            override val metadata = EpubMetadata("original", "Original", listOf("en"), "2026-10-08T00:00:00Z")
            override val manifest = listOf(EpubManifestItem("ch", path, "application/xhtml+xml"))
            override val spine = listOf(EpubSpineItem("ch"))
            override val navigationItemId = "nav"
            override suspend fun openResource(path: EpubEntryPath): ResourceContent = error("No acquisition in presentation")
            override fun close() {}
        }
        val chapter = EpubChapter(path, listOf(EpubBlock(listOf(0, 0), 0, EpubBlockKind.PARAGRAPH, listOf(EpubRun(original)), 0)), emptyMap())
        val parser = object : EpubParser {
            override suspend fun chapter(document: EpubDocument, path: EpubEntryPath) = chapter
            override suspend fun toc(document: EpubDocument) = emptyList<EpubTocEntry>()
        }
        val store = Store(); val writer = ProgressPersistence(store, StandardTestDispatcher(testScheduler)) { 10 }
        val reader = EpubReaderController(doc, id, backgroundScope, parser, writer)
        try {
            reader.initialize(null)
            Scene(this).use { f ->
                f.content { EpubReader(reader, false, {}, "Back") }
                f.scroll(500f); reader.flush(); runCurrent()
                val live = assertIs<ReadingLocator.Epub>(assertNotNull(store.values[id]).locator)
                assertTrue(live.codePointOffset > 0)
                f.remount()
                val state = assertIs<EpubReaderState.Ready>(reader.state.value)
                println("EPUB remount live=${live.codePointOffset} initial=${state.initialPosition}")
                assertEquals(chapter.locate(live), state.initialPosition)
                val line = f.firstTextLine(original, 12f) // Existing EPUB content padding.
                println("EPUB remount restored first-line=$line")
                assertTrue(live.codePointOffset.toInt() in line)
                for ((width, font) in listOf(350 to 1f, 900 to 1.5f, 700 to 1f)) {
                    f.scene.constraints = Constraints.fixed(width, 600); f.density = Density(1f, font); f.pump()
                    val latest = assertIs<EpubReaderState.Ready>(reader.state.value)
                    assertEquals(chapter.locate(live), latest.initialPosition)
                    val resizedLine = f.firstTextLine(original, 12f)
                    assertTrue(live.codePointOffset.toInt() in resizedLine, "EPUB width=$width font=$font live=${live.codePointOffset} first=$resizedLine")
                }
            }
        } finally { reader.close(); writer.close(); writer.awaitClosed() }
    }
    @Test fun appPresentationRecreationKeepsItsOpenPublicationWithoutReacquisition() = runTest {
        val resource = PublicationResource(pub, "text", PublicationFormat.TEXT, "text/plain")
        val publication = Publication(pub, "Original", PublicationType.DOCUMENT, resources = listOf(resource))
        var preparations = 0
        val source = object : PublicationSource, org.infinilect.app.discovery.DiscoverySource {
            override val id = pub.sourceId
            override suspend fun discover(request: org.infinilect.app.discovery.DiscoveryRequest, token: String?) =
                org.infinilect.app.discovery.DiscoveryPage(listOf(org.infinilect.app.discovery.DiscoveryEntry(publication)))
            override suspend fun search(query: String, pageToken: String?) = SearchPage(listOf(publication))
            override suspend fun getPublication(publicationId: PublicationId) = publication
            override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("Owned test preparer")
        }
        val preparer = object : TextPreparer {
            override suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader, dispatcher: CoroutineDispatcher): TextDocument {
                preparations++; return TextDocument(pub, "Original", original, ReadingProgressId(pub, "text", PublicationFormat.TEXT))
            }
        }
        val sources = ApplicationSources(listOf(SourceOption("Original", source, true)), textPreparer = preparer) {}
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { Scene(this).use { f ->
            f.scene.constraints = Constraints.fixed(1000, 900)
            sources.applicationSession().navigate(Destination.SEARCH)
            f.content { App(sources) }
            f.nodes().first { it.config.getOrNull(SemanticsActions.SetText)?.action != null }.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("original"))
            f.pump(); f.click("Search"); f.click("Original"); f.click("Start reading")
            assertTrue(f.nodes().any { f.text(it, "Back to results") }); assertEquals(1, preparations)
            f.remount()
            assertTrue(f.nodes().any { f.text(it, "Back to results") }, "The session must outlive its presentation")
            assertEquals(1, preparations)
        } } finally { sources.close(); sources.awaitProgressClosed(); Dispatchers.resetMain() }
    }

    @Test fun pdfResizeAndRemountKeepTheSamePageAndOwnedRasterWithoutRenderingAgain() = runTest {
        val document = TestPdfDocument(12)
        val reader = PdfReaderController(document, backgroundScope)
        try {
            reader.initialize(null); reader.navigate(6); runCurrent()
            val state = reader.state.value
            val raster = assertIs<PdfFrame.Ready>(state.frame).raster
            val renders = document.rendered.toList()
            Scene(this).use { f ->
                f.content { PdfReader(reader, false, {}, "Back") }
                for ((width, height) in listOf(360 to 700, 1000 to 400, 700 to 600)) {
                    f.scene.constraints = Constraints.fixed(width, height); f.pump(); f.remount()
                    assertTrue(f.nodes().any { f.text(it, "7 / 12") })
                    assertEquals(6, reader.state.value.index); assertEquals(6, reader.state.value.presentedIndex)
                    assertEquals(state.ticket, reader.state.value.ticket)
                    assertSame(raster, assertIs<PdfFrame.Ready>(reader.state.value.frame).raster)
                    assertEquals(renders, document.rendered)
                    assertEquals(0, document.closes, "Replacing PDF presentation must not retire its retained document")
                    assertEquals(4, raster.argb.size)
                }
            }
        } finally { reader.close() }
        assertEquals(1, document.closes)
    }
}
