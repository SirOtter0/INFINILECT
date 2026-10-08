// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.epub.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.page.CbzPagePreparer
import org.infinilect.core.*
import kotlin.test.*

class EpubImportCompatibilityTest {
    private suspend fun owned(limits: EpubLimits = EpubLimits(), action: suspend (FileLocalPublicationSource, FileEpubPreparer, java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("epub-import-compatibility")
        val text = FileTextPreparer(root.resolve("text")); val epub = FileEpubPreparer(root.resolve("epub"), limits)
        val pages = CbzPagePreparer(root.resolve("cbz"))
        val local = FileLocalPublicationSource(root.resolve("imports"), text, epub, pages)
        try { action(local, epub, root) }
        finally { local.close(); local.awaitClosed(); epub.close(); epub.awaitClosed(); text.close(); text.awaitClosed(); pages.close(); pages.awaitClosed(); root.toFile().deleteRecursively() }
    }
    private suspend fun rejected(bytes: ByteArray, expected: ImportFailure, limits: EpubLimits = EpubLimits()) {
        owned(limits) { local, _, root ->
            val content = EpubBytes(bytes, chunk = 1)
            val error = assertFailsWith<LocalImportException> { local.import(LocalFileSelection("convincing.epub") { content }) }
            assertEquals(expected, error.failure); assertTrue(content.closed)
            assertTrue(local.search("*").publications.isEmpty())
            Files.list(root.resolve("imports")).use { paths -> assertEquals(listOf(".owner.lock"), paths.map { it.fileName.toString() }.toList()) }
            assertTrue(payloads(root.resolve("epub")).isEmpty())
            assertFalse(error.message!!.contains(root.toString()))
        }
    }
    @Test fun metadataFreeSelectionAndMisleadingNamesDoNotRejectLegacyEpub() = runBlocking<Unit> {
        for (name in listOf(null, "generic.bin", "misleading.pdf")) owned { local, epub, _ ->
            // Android's selection contract carries no MIME value: generic MIME cannot authorize/reject bytes.
            val fixture = epub2Fixture().apply { change("OPS/chapter.xhtml") { it.replace("id=\"start\"", "id=\"123\"") }; change("OPS/Nav/toc.ncx") { it.replace("#start", "#123") } }
            val content = EpubBytes(fixture.zip(), sizeBytes = null, chunk = 1)
            val publication = local.import(LocalFileSelection(name) { content })
            assertEquals(PublicationFormat.EPUB, publication.resources.single().format)
            assertEquals("Résumé 📚", publication.title); assertEquals(listOf("作者"), publication.authors)
            val loader = object : ResourceLoader { override suspend fun load(resource: PublicationResource) = local.loadResource(resource) }
            val document = epub.prepare(publication, publication.resources.single(), loader)
            try { assertNotNull(BoundedEpubParser().chapter(document, EpubEntryPath("OPS/chapter.xhtml")).anchors["123"]) }
            finally { document.close() }
        }
    }
    @Test fun recognizedMalformedPackageHasAnEpubErrorAndLeavesNoOwnedImport() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { entries[packagePath] = "<broken".encodeToByteArray() }.zip(), ImportFailure.EPUB_INVALID)
    }
    @Test fun recognizedMissingContainerHasAnEpubError() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { entries.remove("META-INF/container.xml") }.zip(), ImportFailure.EPUB_INVALID)
    }
    @Test fun recognizedMissingSpineResourceHasAnEpubError() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { entries.remove("OPS/chapter.xhtml") }.zip(), ImportFailure.EPUB_INVALID)
    }
    @Test fun recognizedInvalidNcxTargetHasAnEpubError() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { change("OPS/Nav/toc.ncx") { it.replace("../chapter.xhtml", "../missing.xhtml") } }.zip(), ImportFailure.EPUB_INVALID)
    }
    @Test fun encryptionHasItsOwnControlledErrorAndNoRetainedImport() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { entries["META-INF/encryption.xml"] = "<encryption/>".encodeToByteArray() }.zip(), ImportFailure.EPUB_ENCRYPTED)
    }
    @Test fun recognizedEpubPreparationLimitIsNotReportedAsUnknownFormat() = runBlocking<Unit> {
        rejected(epub2Fixture().zip(), ImportFailure.LIMIT, EpubLimits(xmlBytes = 64))
    }
    @Test fun wrongMimetypeAndMissingMimetypeCannotBeAuthorizedByAnExtension() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { entries["mimetype"] = "application/not-epub".encodeToByteArray() }.zip(), ImportFailure.UNSUPPORTED)
        rejected(epub2Fixture().apply { entries.remove("mimetype") }.zip(), ImportFailure.UNSUPPORTED)
    }
    @Test fun maliciousZipPathAndCorruptArchiveCannotBecomePublications() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { entries["../escape"] = byteArrayOf(1) }.zip(), ImportFailure.EPUB_INVALID)
        val bytes = epub2Fixture().zip()
        rejected(bytes.copyOf(bytes.size - 3), ImportFailure.EPUB_INVALID)
    }
    @Test fun markerIsBoundedRecognitionRatherThanValidation() = runBlocking<Unit> {
        val spoof = epub2Fixture().zip().copyOf(58)
        rejected(spoof, ImportFailure.EPUB_INVALID)
    }
}
