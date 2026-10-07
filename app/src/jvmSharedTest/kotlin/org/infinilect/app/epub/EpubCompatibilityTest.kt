// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*
import org.infinilect.app.reader.*
import org.infinilect.core.*

/** Real ZIP/OPF/NCX/semantic parser, run on Desktop and Android host (not physical Android). */
class EpubCompatibilityTest {
    private suspend fun prepared(fixture: EpubFixture, action: suspend (EpubDocument) -> Unit) {
        val root = Files.createTempDirectory("epub-compatibility")
        val owner = FileEpubPreparer(root)
        try {
            val doc = owner.prepare(epubPublication, epubResource, epubLoader(EpubBytes(fixture.zip())))
            try { action(doc) } finally { doc.close(); doc.close() }
        } finally { owner.close(); owner.awaitClosed(); assertTrue(payloads(root).isEmpty()); root.toFile().deleteRecursively() }
    }
    private suspend fun rejected(fixture: EpubFixture, failure: EpubFailure = EpubFailure.INVALID) {
        val root = Files.createTempDirectory("epub-compatibility-reject")
        val owner = FileEpubPreparer(root)
        val content = EpubBytes(fixture.zip())
        try { assertEquals(failure, assertFailsWith<EpubException> { owner.prepare(epubPublication, epubResource, epubLoader(content)) }.failure) }
        finally { owner.close(); owner.awaitClosed(); assertTrue(content.closed); assertTrue(payloads(root).isEmpty()); root.toFile().deleteRecursively() }
    }
    @Test fun epub2NcxPreparesAndUsesExistingChapterAndTocModels() = runBlocking<Unit> {
        prepared(epub2Fixture()) { doc ->
            assertNull(doc.metadata.modified)
            assertEquals(listOf("chapter"), doc.spine.map { it.itemId })
            val toc = BoundedEpubParser().toc(doc)
            assertEquals(listOf("First chapter"), toc.map { it.label })
            assertEquals(EpubEntryPath("OPS/chapter.xhtml"), toc.single().target.path)
            assertEquals("start", toc.single().target.anchor)
            assertTrue(BoundedEpubParser().chapter(doc, toc.single().target.path).blocks.isNotEmpty())
        }
    }
    @Test fun nestedNcxKeepsDocumentOrderButNeverReordersSpine() = runBlocking<Unit> {
        val fixture = epub2Fixture().apply {
            entries["OPS/second.xhtml"] = entries.getValue("OPS/chapter.xhtml")
            opf { it.replace("</manifest>", "<item id=\"second\" href=\"second.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>")
                .replace("</spine>", "<itemref idref=\"second\"/></spine>") }
            entries["OPS/Nav/toc.ncx"] = ncx(point("second", "Second", "../second.xhtml", point("child", "Child", "../chapter.xhtml#start")) + point("first", "First", "../chapter.xhtml")).encodeToByteArray()
        }
        prepared(fixture) { doc ->
            assertEquals(listOf("chapter", "second"), doc.spine.map { it.itemId })
            val toc = BoundedEpubParser().toc(doc)
            assertEquals(listOf("Second", "Child", "First"), toc.map { it.label })
            assertEquals(listOf(1, 2, 1), toc.map { it.depth })
        }
    }
    @Test fun spacesEncodedSpacesUnicodeAndNestedImagesResolveThroughReader() = runBlocking<Unit> {
        for ((name, href) in listOf("cover image.png" to "cover image.png", "cover image.png" to "cover%20image.png",
            "日本語.png" to "%E6%97%A5%E6%9C%AC%E8%AA%9E.png", "é.png" to "é.png", "a+b.png" to "a+b.png")) {
            val fixture = EpubFixture().apply {
                entries["OPS/Images/$name"] = byteArrayOf(1, 2, 3) // resource resolution, not decoder acceptance
                entries["OPS/Text/chapter.xhtml"] = entries.remove("OPS/chapter.xhtml")!!
                opf { it.replace("href=\"chapter.xhtml\"", "href=\"Text/chapter.xhtml\"")
                    .replace("</manifest>", "<item id=\"image\" href=\"Images/$href\" media-type=\"image/png\"/></manifest>") }
                change("OPS/nav.xhtml") { it.replace("chapter.xhtml#start", "Text/chapter.xhtml#%73tart") }
                change("OPS/Text/chapter.xhtml") { it.replace("</body>", "<img src=\"../Images/$href\" alt=\"Original image\"/></body>") }
            }
            prepared(fixture) { doc ->
                val parser = BoundedEpubParser()
                val toc = parser.toc(doc)
                val chapter = parser.chapter(doc, toc.single().target.path)
                assertEquals(EpubEntryPath("OPS/Images/$name"), chapter.blocks.single { it.image != null }.image!!.path)
                assertContentEquals(byteArrayOf(1, 2, 3), doc.openResource(EpubEntryPath("OPS/Images/$name")).readBytes(3))
            }
        }
    }
    @Test fun epub2EncodedNcxAndSpineNamesResolveLocally() = runBlocking<Unit> {
        val fixture = epub2Fixture().apply {
            entries["OPS/chapter one.xhtml"] = entries.remove("OPS/chapter.xhtml")!!
            opf { it.replace("href=\"chapter.xhtml\"", "href=\"chapter%20one.xhtml\"") }
            change("OPS/Nav/toc.ncx") { it.replace("../chapter.xhtml", "../chapter%20one.xhtml") }
        }
        prepared(fixture) { assertEquals(EpubEntryPath("OPS/chapter one.xhtml"), BoundedEpubParser().toc(it).single().target.path) }
    }
    @Test fun storedAndDeflatedEmptyDirectoriesAreCountedButNotResources() = runBlocking<Unit> {
        for (compressed in listOf(false, true)) prepared(epub2Fixture().apply {
            deflate = compressed
            entries["OPS/"] = byteArrayOf(); entries["OPS/Nav/"] = byteArrayOf(); entries["Unused/"] = byteArrayOf()
        }) { doc ->
            assertEquals(2, doc.manifest.size)
            assertFailsWith<IllegalArgumentException> { doc.openResource(EpubEntryPath("Unused")) }
            assertEquals(1, doc.spine.size)
        }
        rejected(epub2Fixture().apply { deflate = true; entries["OPS/Bad/"] = byteArrayOf(1) })
    }
    @Test fun missingMalformedAndWrongNcxAreControlledPreparationFailures() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { entries.remove("OPS/Nav/toc.ncx") })
        rejected(epub2Fixture().apply { opf { it.replace(" toc=\"nav\"", "") } })
        rejected(epub2Fixture().apply { opf { it.replace("toc=\"nav\"", "toc=\"chapter\"") } })
        for (xml in listOf("<broken", ncx(""), ncx(point("one", "", "../chapter.xhtml")), ncx(point("one", "A", "../missing.xhtml")),
            ncx(point("one", "A", "../chapter.xhtml") + point("one", "Duplicate", "../chapter.xhtml"))))
            rejected(epub2Fixture().apply { entries["OPS/Nav/toc.ncx"] = xml.encodeToByteArray() })
    }
    @Test fun ncxNodeDepthLabelAndHrefCeilingsFailBeforePublication() = runBlocking<Unit> {
        val nodes = (0..256).joinToString("") { point("n$it", "Chapter", "../chapter.xhtml") }
        val depth = (16 downTo 0).fold("") { child, i -> point("n$i", "Chapter", "../chapter.xhtml", child) }
        for (points in listOf(nodes, depth, point("n", "L".repeat(513), "../chapter.xhtml")))
            rejected(epub2Fixture().apply { entries["OPS/Nav/toc.ncx"] = ncx(points).encodeToByteArray() }, EpubFailure.LIMIT)
        rejected(epub2Fixture().apply { entries["OPS/Nav/toc.ncx"] = ncx(point("n", "Chapter", "x".repeat(641))).encodeToByteArray() })
    }
    @Test fun manifestAndNcxTraversalRemoteAndDoubleEncodingFailClosed() = runBlocking<Unit> {
        for (reference in listOf("../../escape.xhtml", "%2e%2e/chapter.xhtml", "%252e%252e/chapter.xhtml", "/OPS/chapter.xhtml",
            "https://example.org/chapter.xhtml", "//example.org/chapter.xhtml", "C:/chapter.xhtml", "..\\chapter.xhtml", "chapter%2Fxhtml", "chapter%00.xhtml")) {
            rejected(EpubFixture().apply { opf { it.replace("href=\"chapter.xhtml\"", "href=\"$reference\"") } })
            val ncxReference = if (reference == "../../escape.xhtml") "../../../escape.xhtml" else reference
            rejected(epub2Fixture().apply { entries["OPS/Nav/toc.ncx"] = ncx(point("bad", "Bad", ncxReference)).encodeToByteArray() })
        }
    }
    @Test fun normalizationAliasesCannotAuthorizeAnotherPayload() = runBlocking<Unit> {
        rejected(EpubFixture().apply { entries["OPS/é.xhtml"] = byteArrayOf(1); entries["OPS/e\u0301.xhtml"] = byteArrayOf(2) })
        rejected(EpubFixture().apply { entries["OPS/é.xhtml"] = byteArrayOf(1); entries["OPS/É.xhtml"] = byteArrayOf(2) })
        rejected(EpubFixture().apply { entries["OPS/%20.xhtml"] = byteArrayOf(2) })
        rejected(EpubFixture().apply { opf { it.replace("</manifest>", "<item id=\"alias\" href=\"%63hapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>") } })
    }
    @Test fun epub3StillUsesXhtmlNavigationAndSameSemanticChapter() = runBlocking<Unit> {
        prepared(EpubFixture()) { doc ->
            assertEquals("2026-10-04T00:00:00Z", doc.metadata.modified)
            assertEquals("nav", doc.navigationItemId)
            val parser = BoundedEpubParser()
            assertEquals("Chapter", parser.toc(doc).single().label)
            assertEquals(EpubEntryPath("OPS/chapter.xhtml"), parser.chapter(doc, EpubEntryPath("OPS/chapter.xhtml")).path)
        }
    }
    @Test fun epub2DocumentsCanRepeatedlyOpenCloseAndRejectLateResourceUse() = runBlocking<Unit> {
        repeat(3) { prepared(epub2Fixture()) { doc ->
            val handle = doc.openResource(EpubEntryPath("OPS/chapter.xhtml")); doc.close()
            assertFails { handle.read(ByteArray(1)) }; handle.close()
            assertFails { BoundedEpubParser().toc(doc) }
        } }
    }
}
