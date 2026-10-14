// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import org.infinilect.app.progress.ProgressPersistence
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EpubTranslationProgressTest {
    private val publication = PublicationId(SourceId("fixture"), "translation")
    private val id = ReadingProgressId(publication, "book", PublicationFormat.EPUB)
    private val path = EpubEntryPath("OPS/chapter.xhtml")
    private val model = TranslationModel(TranslationPair("en", "es"), "test", 1)
    private inner class Document : EpubDocument {
        override val publicationId = publication
        override val packagePath = EpubEntryPath("OPS/package.opf")
        override val metadata = EpubMetadata("fixture", "Book", listOf("en"), null)
        override val manifest = listOf(EpubManifestItem("chapter", path, "application/xhtml+xml"))
        override val spine = listOf(EpubSpineItem("chapter"))
        override val navigationItemId = "nav"
        var closes = 0
        override suspend fun openResource(path: EpubEntryPath): ResourceContent = error("Synthetic parser owns test text")
        override fun close() { closes++ }
    }
    private inner class Parser : EpubParser {
        var parses = 0
        override suspend fun toc(document: EpubDocument) = emptyList<EpubTocEntry>()
        override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter {
            parses++
            return EpubChapter(path, listOf(
                EpubBlock(listOf(0, 0), 0, EpubBlockKind.HEADING, listOf(EpubRun("Title")), 0),
                EpubBlock(listOf(0, 1), 0, EpubBlockKind.PARAGRAPH, listOf(EpubRun("Hello 🌍 and friends", emphasis = true)), 5)), emptyMap())
        }
    }
    private inner class Engine : EpubTranslationEngine {
        override val identity = "deterministic-test-v1"
        override val unavailable: String? = null
        override val models = listOf(model)
        var calls = 0; var installs = 0; var closes = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun installed(model: TranslationModel) = true
        override suspend fun install(model: TranslationModel, progress: (Long, Long) -> Unit) { installs++ }
        override suspend fun remove(model: TranslationModel) = Unit
        override suspend fun translate(model: TranslationModel, text: String): String { calls++; gate?.await(); return text.repeat(2) }
        override fun close() { closes++ }
    }
    private class Progress : ReadingProgressStore {
        var value: ReadingProgress? = null
        override suspend fun get(id: ReadingProgressId) = value
        override suspend fun save(progress: ReadingProgress): Boolean { value = progress; return true }
        override suspend fun remove(id: ReadingProgressId): Boolean { value = null; return true }
    }
    @Test fun togglesTypographyRecreationAndReopenPreserveOriginalDeepUnicodeLocator() = runTest {
        val store = Progress(); val persistence = ProgressPersistence(store, StandardTestDispatcher(testScheduler)) { 20 }
        val engine = Engine(); val service = EpubTranslationService(engine); val parser = Parser()
        val reader = EpubReaderController(Document(), id, this, parser, persistence, translationService = service, resourceRevision = "sha")
        try {
            reader.initialize(null); runCurrent(); assertEquals(0, engine.installs); assertEquals(0, engine.calls)
            val frame = assertIs<EpubReaderState.Ready>(reader.state.value)
            reader.report(frame.ticket, 1, 8)
            reader.translatedReading(true); runCurrent()
            assertEquals(1 to 8, assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
            val projection = reader.translation.state.value.blocks!!.last()
            val display = projection.sourceToDisplay(8)
            assertEquals(8, projection.displayToSource(display))
            val translated = assertIs<EpubReaderState.Ready>(reader.state.value)
            reader.report(translated.ticket, 1, projection.displayToSource(display))
            reader.presentationChanged(reader.settings.value.copy(fontSize = 26, margin = 32))
            assertEquals(1 to 8, assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
            reader.translatedReading(false)
            reader.presentationChanged() // retained-session remount/resize
            assertEquals(1 to 8, assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
            assertEquals(1, parser.parses)
            reader.flush(); runCurrent()
            assertEquals(8L, (store.value!!.locator as ReadingLocator.Epub).codePointOffset)
            reader.close(); runCurrent()
            val reopened = EpubReaderController(Document(), id, this, Parser(), persistence, translationService = service, resourceRevision = "sha")
            try {
                reopened.initialize(store.value)
                assertEquals(1 to 8, assertIs<EpubReaderState.Ready>(reopened.state.value).initialPosition)
                assertEquals(0, engine.closes) // reader does not own application engine
            } finally { reopened.close() }
        } finally { reader.close(); service.close(); persistence.close(); persistence.awaitClosed() }
        assertEquals(1, engine.closes)
    }
    @Test fun pendingFailedAndCancelledProjectionCannotAdvanceProgressOrAcceptOldUiTickets() = runTest {
        val store = Progress(); val persistence = ProgressPersistence(store, StandardTestDispatcher(testScheduler)) { 20 }
        val engine = Engine(); engine.gate = CompletableDeferred()
        val service = EpubTranslationService(engine)
        val reader = EpubReaderController(Document(), id, this, Parser(), persistence, translationService = service, resourceRevision = "sha")
        try {
            reader.initialize(null); val frame = assertIs<EpubReaderState.Ready>(reader.state.value)
            reader.report(frame.ticket, 1, 3); reader.flush(); runCurrent()
            val saved = store.value!!.locator
            reader.translatedReading(true); runCurrent()
            assertTrue(reader.translation.state.value.busy); assertNull(reader.translation.state.value.blocks)
            reader.report(frame.ticket, 0, 0) // obsolete original presentation
            reader.translatedReading(false); engine.gate!!.complete(Unit); runCurrent()
            reader.flush(); runCurrent(); assertEquals(saved, store.value!!.locator)
            assertNull(reader.translation.state.value.blocks)
            assertEquals(1 to 3, assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
        } finally { reader.close(); service.close(); persistence.close(); persistence.awaitClosed() }
    }
    @Test fun deepPositionIn3000BlockChapterStaysBoundedAcrossForwardAndReverseWindows() = runTest {
        val engine = Engine(); val service = EpubTranslationService(engine)
        val windows = object : EpubParser {
            override suspend fun toc(document: EpubDocument) = emptyList<EpubTocEntry>()
            override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter = error("No full chapter materialization")
            override suspend fun window(document: EpubDocument, path: EpubEntryPath, request: EpubWindowRequest): EpubChapter {
                val index = when(request) {
                    is EpubWindowRequest.Block -> request.index
                    is EpubWindowRequest.Locator -> request.value.elementPath.last()
                    EpubWindowRequest.End -> 2999
                    else -> error("Unused request")
                }
                val start = index / 128 * 128
                val blocks = (start until minOf(start + 128, 3000)).map {
                    EpubBlock(listOf(0, it), 0, EpubBlockKind.PARAGRAPH, listOf(EpubRun("Block " + it.toString().padStart(4, '0'))), it * 10)
                }
                return EpubChapter(path, blocks, emptyMap(), start, 3000, 30000)
            }
        }
        val saved = ReadingProgress(id, ReadingLocator.Epub(path, listOf(0, 2500), 4, 25004.0 / 30000), 25004.0 / 30000, 10)
        val reader = EpubReaderController(Document(), id, this, windows, translationService = service, resourceRevision = "sha")
        try {
            reader.initialize(saved); runCurrent(); reader.translatedReading(true); runCurrent()
            repeat(4) {
                val frame = assertIs<EpubReaderState.Ready>(reader.state.value)
                val blocks = reader.translation.state.value.blocks!!
                assertEquals(frame.chapter.blocks.size, blocks.size)
                assertEquals(blocks.size, blocks.map { value -> value.source.elementPath }.distinct().size)
                assertTrue(blocks.size <= 256); assertTrue(reader.retainedChapters <= 2)
                assertTrue(blocks.sumOf { value -> value.text.length } <= EpubTranslationPolicy.PRESENTATION_UNITS)
                if (it % 2 == 0) reader.next() else reader.previous()
                runCurrent()
            }
            reader.translatedReading(false)
            assertTrue(assertIs<EpubReaderState.Ready>(reader.state.value).chapter.startBlock > 2048)
        } finally { reader.close(); service.close() }
    }

}
