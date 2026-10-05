// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.reader.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class OpenEpubControllerTest {
    private val id = PublicationId(SourceId("fixture"), "epub")
    private val resource = PublicationResource(id, "book", PublicationFormat.EPUB, "application/epub+zip")
    private val publication = Publication(id, "Original", PublicationType.BOOK, resources = listOf(resource))
    private val path = EpubEntryPath("OPS/chapter.xhtml")
    private inner class Doc(override val publicationId: PublicationId = id) : EpubDocument {
        override val packagePath = EpubEntryPath("OPS/package.opf")
        override val metadata = EpubMetadata("original", "Original", listOf("en"), "2026-10-04T00:00:00Z")
        override val manifest = listOf(EpubManifestItem("chapter", path, "application/xhtml+xml"))
        override val spine = listOf(EpubSpineItem("chapter"))
        override val navigationItemId = "nav"
        var closed = false
        override suspend fun openResource(path: EpubEntryPath): ResourceContent = error("Parser fake")
        override fun close() { closed = true }
    }
    private inner class Source : PublicationSource {
        override val id = this@OpenEpubControllerTest.id.sourceId
        override suspend fun search(query: String, pageToken: String?) = SearchPage(listOf(publication))
        override suspend fun getPublication(publicationId: PublicationId) = publication
        override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("Preparer fake")
    }
    private inner class Parser : EpubParser {
        var action: suspend () -> Unit = {}
        override suspend fun toc(document: EpubDocument) = listOf(EpubTocEntry("Chapter", EpubTarget(path), 0))
        override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter {
            action()
            return EpubChapter(path, listOf(EpubBlock(listOf(0, 0), 0, EpubBlockKind.PARAGRAPH, listOf(EpubRun("Original")), 0)), emptyMap())
        }
    }
    private fun preparer(doc: Doc) = object : EpubPreparer {
        override suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader) = doc
        override fun close() {}
        override suspend fun awaitClosed() {}
    }
    private fun TestScope.controller(doc: Doc, parser: Parser, opened: () -> Unit = {}) = OpenPublicationController(Source(),
        object : ResourceLoader { override suspend fun load(resource: PublicationResource): ResourceContent = error("Preparer fake") }, this,
        epubPreparer = preparer(doc), epubParser = parser, onOpened = { opened() })

    @Test fun successfulOpenPublishesOnlyAfterFirstChapterAndCloseReleasesDocument() = runTest {
        val doc = Doc(); val parser = Parser(); var opened = 0
        val controller = controller(doc, parser) { opened++ }
        controller.open(publication); advanceUntilIdle()
        assertIs<OpenPublicationState.EpubReady>(controller.state.value); assertEquals(1, opened); assertFalse(doc.closed)
        controller.close(); controller.close(); assertTrue(doc.closed)
    }
    @Test fun timeoutDuringFirstChapterClosesDocumentAndDoesNotRecordHistory() = runTest {
        val doc = Doc(); val parser = Parser().apply { action = { awaitCancellation() } }; var opened = 0
        val controller = controller(doc, parser) { opened++ }
        controller.open(publication); advanceTimeBy(60_001); runCurrent()
        assertIs<OpenPublicationState.Error>(controller.state.value); assertTrue(doc.closed); assertEquals(0, opened); controller.close()
    }
    @Test fun cancellationDuringFirstChapterRejectsNoncooperativeLateResult() = runTest {
        val doc = Doc(); val gate = CompletableDeferred<Unit>(); var opened = 0
        val parser = Parser().apply { action = { withContext(NonCancellable) { gate.await() } } }
        val controller = controller(doc, parser) { opened++ }
        controller.open(publication); runCurrent(); controller.cancel(); gate.complete(Unit); advanceUntilIdle()
        assertIs<OpenPublicationState.Idle>(controller.state.value); assertTrue(doc.closed); assertEquals(0, opened); controller.close()
    }
    @Test fun parserFailureClosesPreparedDocumentAndDoesNotRecordHistory() = runTest {
        val doc = Doc(); var opened = 0; val parser = Parser().apply { action = { throw EpubException(EpubFailure.INVALID) } }
        val controller = controller(doc, parser) { opened++ }; controller.open(publication); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value); assertTrue(doc.closed); assertEquals(0, opened); controller.close()
    }
    @Test fun wrongPreparedPublicationIsRejectedAndClosed() = runTest {
        val doc = Doc(id.copy(localId = "other")); val controller = controller(doc, Parser())
        controller.open(publication); advanceUntilIdle(); assertIs<OpenPublicationState.Error>(controller.state.value); assertTrue(doc.closed); controller.close()
    }
    @Test fun historyHandoffIsOutsidePreparationDeadline() = runTest {
        val doc = Doc(); var opened = 0
        val controller = controller(doc, Parser()) { opened++; testScheduler.advanceTimeBy(60_001) }
        controller.open(publication); advanceUntilIdle()
        assertIs<OpenPublicationState.EpubReady>(controller.state.value); assertEquals(1, opened); assertFalse(doc.closed); controller.close()
    }
}
