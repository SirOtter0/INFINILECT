// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.reader.EpubException
import org.infinilect.app.reader.EpubFailure
import org.infinilect.core.*
import kotlin.test.*

/** Source policy can reject a fully validated document using the exact acquired or
 * retained publication snapshot. It cannot grant exceptions to EPUB security rules. */
class EpubSourceContextTest {
    private val publication = epubPublication.copy(
        title = "Original source title",
        authors = listOf("Original source author"),
        languages = listOf("fr"),
        sourceUrl = "https://example.org/original-work",
        rights = "Original source rights",
    )

    @Test fun validatedDocumentReceivesTheExactSourceSnapshotAndPreservesOriginalBytes() = runBlocking<Unit> {
        val root = Files.createTempDirectory("epub-source-context")
        val bytes = EpubFixture().zip()
        val original = Files.write(root.resolve("original.epub"), bytes)
        val content = EpubBytes(bytes)
        var calls = 0
        val preparer = FileEpubPreparer(root.resolve("prepared"), validateDocument = { document, resource, snapshot ->
            calls++
            assertSame(publication, snapshot)
            assertSame(epubResource, resource)
            assertEquals(snapshot.id, document.publicationId)
            assertEquals("Résumé 📚", document.metadata.title)
            assertEquals(listOf("Original test fixture"), document.metadata.rights.map { it.text })
            assertContentEquals(bytes, Files.readAllBytes(payloads(root.resolve("prepared")).single()))
            assertContentEquals(bytes, Files.readAllBytes(original))
        })
        try {
            val document = preparer.prepare(publication, epubResource, epubLoader(content))
            try {
                assertEquals(1, calls)
                assertEquals("Résumé 📚", document.metadata.title)
                assertEquals("https://example.org/original-work", publication.sourceUrl)
                assertEquals("Original source rights", publication.rights)
            } finally { document.close() }
        } finally {
            preparer.close(); preparer.awaitClosed()
            try {
                assertTrue(content.closed)
                assertTrue(payloads(root.resolve("prepared")).isEmpty())
                assertContentEquals(bytes, Files.readAllBytes(original))
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun sourcePolicyRejectionClosesThePreparedDocumentWithoutChangingTheOriginalOrSnapshot() = runBlocking<Unit> {
        val root = Files.createTempDirectory("epub-source-reject")
        val bytes = EpubFixture().zip()
        val original = Files.write(root.resolve("original.epub"), bytes)
        val content = EpubBytes(bytes)
        var calls = 0
        val preparer = FileEpubPreparer(root.resolve("prepared"), validateDocument = { document, resource, snapshot ->
            calls++
            assertSame(publication, snapshot)
            assertEquals(epubResource, resource)
            assertEquals("urn:synthetic:foundation", document.metadata.identifier)
            assertEquals(listOf("作者"), document.metadata.creators)
            assertEquals(listOf("Original test fixture"), document.metadata.rights.map { it.text })
            throw EpubException(EpubFailure.INVALID)
        })
        try {
            assertEquals(EpubFailure.INVALID, assertFailsWith<EpubException> {
                preparer.prepare(publication, epubResource, epubLoader(content))
            }.failure)
            assertEquals(1, calls)
            assertEquals("Original source title", publication.title)
            assertEquals("Original source rights", publication.rights)
        } finally {
            preparer.close(); preparer.awaitClosed()
            try {
                assertTrue(content.closed)
                assertTrue(payloads(root.resolve("prepared")).isEmpty())
                assertContentEquals(bytes, Files.readAllBytes(original))
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun sourcePolicyCannotAdmitAnEpubWithForbiddenRemoteResources() = runBlocking<Unit> {
        val root = Files.createTempDirectory("epub-source-boundary")
        val fixture = EpubFixture().apply {
            change("OPS/chapter.xhtml") { it.replace("</body>", "<img src=\"https://example.org/remote.png\"/></body>") }
        }
        val content = EpubBytes(fixture.zip())
        var calls = 0
        val preparer = FileEpubPreparer(root, validateDocument = { _, _, _ -> calls++ })
        try {
            assertEquals(EpubFailure.INVALID, assertFailsWith<EpubException> {
                preparer.prepare(publication, epubResource, epubLoader(content))
            }.failure)
            assertEquals(0, calls)
        } finally {
            preparer.close(); preparer.awaitClosed()
            try { assertTrue(content.closed); assertTrue(payloads(root).isEmpty()) }
            finally { root.toFile().deleteRecursively() }
        }
    }
}
