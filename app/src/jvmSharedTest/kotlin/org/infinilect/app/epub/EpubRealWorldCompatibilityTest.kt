// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.reader.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import kotlin.test.*

/** Original synthetic reproductions; none of the user's downloaded books are redistributed. */
class EpubRealWorldCompatibilityTest {
    private suspend fun rejected(fixture: EpubFixture, expected: EpubFailure = EpubFailure.INVALID) {
        val root = Files.createTempDirectory("epub-real-world-rejected")
        val owner = FileEpubPreparer(root); val content = EpubBytes(fixture.zip())
        try { assertEquals(expected, assertFailsWith<EpubException> { owner.prepare(epubPublication, epubResource, epubLoader(content)) }.failure) }
        finally { owner.close(); owner.awaitClosed(); assertTrue(content.closed); assertTrue(payloads(root).isEmpty()); root.toFile().deleteRecursively() }
    }
    private suspend fun prepared(fixture: EpubFixture, action: suspend (EpubDocument) -> Unit) {
        val root = Files.createTempDirectory("epub-real-world")
        val owner = FileEpubPreparer(root)
        val content = EpubBytes(fixture.zip(), chunk = 1)
        try {
            val doc = owner.prepare(epubPublication, epubResource, epubLoader(content))
            try { action(doc) } finally { doc.close() }
        } finally {
            owner.close(); owner.awaitClosed()
            assertTrue(content.closed); assertTrue(payloads(root).isEmpty())
            root.toFile().deleteRecursively()
        }
    }
    @Test fun numericLegacyXhtmlIdsAndEncodedFragmentsResolveWithoutChangingSpine() = runBlocking<Unit> {
        val fixture = epub2Fixture().apply {
            change("OPS/chapter.xhtml") { it.replace("id=\"start\"", "id=\"123\"") }
            change("OPS/Nav/toc.ncx") { it.replace("#start", "#%31%32%33") }
        }
        prepared(fixture) { doc ->
            val parser = BoundedEpubParser(); val toc = parser.toc(doc)
            assertEquals("123", toc.single().target.anchor)
            val chapter = parser.chapter(doc, toc.single().target.path)
            assertEquals(EpubPosition(listOf(0, 0), 0), chapter.anchors["123"])
            assertEquals(listOf("chapter"), doc.spine.map { it.itemId })
        }
    }
    @Test fun inlineSvgRasterCoverUsesOnlyItsOwnedImage() = runBlocking<Unit> {
        prepared(rasterCoverFixture()) { doc ->
            val chapter = BoundedEpubParser().chapter(doc, EpubEntryPath("OPS/chapter.xhtml"))
            val image = assertNotNull(chapter.blocks.single().image)
            assertEquals(EpubBlockKind.IMAGE, chapter.blocks.single().kind)
            assertEquals(EpubEntryPath("OPS/Images/cover image.png"), image.path)
            assertEquals("image/png", image.mediaType)
            assertContentEquals(developmentPng(), doc.openResource(image.path).readBytes(4096))
        }
    }
    @Test fun standardXhtmlAndNcxDeclarationsAndNbspNeedNoExternalDtd() = runBlocking<Unit> {
        val fixture = epub2Fixture().apply {
            entries["OPS/chapter.xhtml"] = (XHTML_DECLARATION +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Original</title></head>" +
                "<body><p id=\"start\">Original&nbsp;test</p></body></html>").encodeToByteArray()
            change("OPS/Nav/toc.ncx") { NCX_DECLARATION + it }
        }
        prepared(fixture) { doc ->
            val parser = BoundedEpubParser(); assertEquals(1, parser.toc(doc).size)
            assertEquals("Original\u00a0test", parser.chapter(doc, EpubEntryPath("OPS/chapter.xhtml")).blocks.single().text)
        }
    }
    @Test fun legacyCoverWorksWithLiteralSpaceAndSvg2Href() = runBlocking<Unit> {
        val fixture = rasterCoverFixture().apply {
            change("OPS/chapter.xhtml") { it.replace("xlink:href", "href").replace("cover%20image.png", "cover image.png") }
        }
        prepared(fixture) { doc -> assertEquals(EpubEntryPath("OPS/Images/cover image.png"), BoundedEpubParser().chapter(doc, EpubEntryPath("OPS/chapter.xhtml")).blocks.single().image!!.path) }
    }
    @Test fun coverProjectionLeavesSurroundingTextAndSemanticLocatorsStable() = runBlocking<Unit> {
        val fixture = rasterCoverFixture().apply {
            change("OPS/chapter.xhtml") { it.replace("<div>", "<p id=\"before\">Original before</p><div>").replace("</div>", "</div><p id=\"123\">Original after 📖</p>") }
        }
        prepared(fixture) { doc ->
            val parser = BoundedEpubParser(); val path = EpubEntryPath("OPS/chapter.xhtml")
            val chapter = parser.chapter(doc, path)
            assertEquals(listOf(EpubBlockKind.PARAGRAPH, EpubBlockKind.IMAGE, EpubBlockKind.PARAGRAPH), chapter.blocks.map { it.kind })
            val locator = chapter.locator(2, 9); val reparsed = parser.chapter(doc, path)
            assertEquals(2 to 9, reparsed.locate(locator)); assertEquals(chapter.blocks[2].elementPath, reparsed.anchors["123"]!!.elementPath)
        }
    }
    @Test fun svgScriptAnimationForeignObjectAndVectorShapesRemainRejected() = runBlocking<Unit> {
        for (element in listOf("script", "animate", "foreignObject", "use", "path", "rect")) {
            rejected(rasterCoverFixture().apply { change("OPS/chapter.xhtml") { it.replace("</svg>", "<$element/></svg>") } })
        }
    }
    @Test fun svgEventsTransformsStylesAndClippingRemainRejected() = runBlocking<Unit> {
        for (attribute in listOf("onload=\"execute()\"", "transform=\"translate(10)\"", "style=\"fill:url(https://example.org)\"", "clip-path=\"url(#clip)\"")) {
            rejected(rasterCoverFixture().apply { change("OPS/chapter.xhtml") { it.replace("<image width", "<image $attribute width") } })
        }
    }
    @Test fun svgRemoteTraversalDoubleEncodedAndMissingRasterTargetsRemainRejected() = runBlocking<Unit> {
        for (target in listOf("https://example.org/image.png", "file:///image.png", "//host/image.png", "../../../image.png", "%2e%2e/image.png", "%252e%252e/image.png", "missing.png", "Images/cover%20image.png#fragment", "Images/cover%20image.png?query")) {
            rejected(rasterCoverFixture().apply { change("OPS/chapter.xhtml") { it.replace("Images/cover%20image.png", target) } })
        }
    }
    @Test fun svgCannotAuthorizeAnUnmanifestedImage() = runBlocking<Unit> {
        rejected(rasterCoverFixture().apply { opf { it.replace(Regex("<item id=\"cover\"[^>]*/>"), "") } })
    }
    @Test fun svgCannotBecomeASvgDecoderOrChooseBetweenTwoReferences() = runBlocking<Unit> {
        rejected(rasterCoverFixture().apply { opf { it.replace("media-type=\"image/png\"", "media-type=\"image/svg+xml\"") } })
        rejected(rasterCoverFixture().apply { change("OPS/chapter.xhtml") { it.replace("xlink:href=", "href=\"Images/cover%20image.png\" xlink:href=") } })
    }
    @Test fun svgMultipleImagesNestedImagesAndNonzeroOffsetsRemainUnsupported() = runBlocking<Unit> {
        for (transform in listOf<(String) -> String>(
            { it.replace("</svg>", "<image href=\"Images/cover%20image.png\"/></svg>") },
            { it.replace("<image width", "<g><image width").replace("</svg>", "</g></svg>") },
            { it.replace("<image width", "<image x=\"1\" width") },
        )) rejected(rasterCoverFixture().apply { change("OPS/chapter.xhtml", transform) })
    }
    @Test fun svgNonfiniteUnboundedAndMalformedGeometryIsRejected() = runBlocking<Unit> {
        for (viewBox in listOf("0 0 NaN 64", "0 0 Infinity 64", "0 0 -1 64", "0 0 1000001 64", "1 0 96 64", "0 0 96")) {
            rejected(rasterCoverFixture().apply { change("OPS/chapter.xhtml") { it.replace("0 0 96 64", viewBox) } })
        }
    }
    @Test fun standardDeclarationDoesNotPermitCustomInternalOrExternalEntities() {
        for (declaration in listOf(
            XHTML_DECLARATION.trimEnd().dropLast(1) + " [<!ENTITY x SYSTEM 'file:///private'>]>",
            XHTML_DECLARATION.trimEnd().dropLast(1) + " [<!ENTITY x 'expansion'>]>",
            "<!DOCTYPE html SYSTEM 'https://example.org/evil'>",
            XHTML_DECLARATION.replace("http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd", "file:///private"),
            XHTML_DECLARATION.replace("-//W3C//DTD XHTML 1.1//EN", "custom"),
            XHTML_DECLARATION + XHTML_DECLARATION,
        )) assertFailsWith<EpubException> { parseEpubXml((declaration + "<html xmlns=\"http://www.w3.org/1999/xhtml\"/>").encodeToByteArray(), EpubLimits(), null) }
    }
    @Test fun standardDeclarationMustMatchTheDocumentRoot() {
        assertFailsWith<EpubException> { parseEpubXml((XHTML_DECLARATION + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\"/>").encodeToByteArray(), EpubLimits(), null) }
    }
    @Test fun entityRecoveryDoesNotChangeCdataOrEnableOtherEntities() {
        val root = parseEpubXml((XHTML_DECLARATION + "<html xmlns=\"http://www.w3.org/1999/xhtml\">&nbsp;<![CDATA[&nbsp;]]><!-- &nbsp; --></html>").encodeToByteArray(), EpubLimits(), null)
        assertEquals("\u00a0&nbsp;", root.text.toString())
        for (entity in listOf("nbsp", "custom")) assertFailsWith<EpubException> { parseEpubXml("<html>&$entity;</html>".encodeToByteArray(), EpubLimits(), null) }
        assertFailsWith<EpubException> { parseEpubXml((XHTML_DECLARATION + "<html xmlns=\"http://www.w3.org/1999/xhtml\">&custom;</html>").encodeToByteArray(), EpubLimits(), null) }
    }
    @Test fun numericRecoveryDoesNotAllowDuplicateOrUnsafeFragmentIds() = runBlocking<Unit> {
        for (id in listOf("123", "bad id", "../escape", "bad%31", "bad:scheme")) {
            rejected(epub2Fixture().apply { change("OPS/chapter.xhtml") { it.replace("</body>", "<p id=\"$id\">one</p><p id=\"$id\">two</p></body>") } })
        }
        for (fragment in listOf("..", "%2fescape", "%2531", "bad%20id", "a:b")) {
            assertFailsWith<EpubException> { resolveEpubTarget(EpubEntryPath("OPS/chapter.xhtml"), "#$fragment", listOf(EpubManifestItem("chapter", EpubEntryPath("OPS/chapter.xhtml"), "application/xhtml+xml"))) }
        }
    }
    @Test fun numericXhtmlRecoveryDoesNotRelaxOpfOrNcxIds() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { opf { it.replace("id=\"chapter\"", "id=\"123\"").replace("idref=\"chapter\"", "idref=\"123\"") } })
        rejected(epub2Fixture().apply { change("OPS/Nav/toc.ncx") { it.replace("id=\"one\"", "id=\"123\"") } })
    }
    @Test fun validNonMimetypeEntryOrderStillOpens() = runBlocking<Unit> {
        val fixture = rasterCoverFixture().apply {
            val container = entries.remove("META-INF/container.xml")!!
            entries["META-INF/container.xml"] = container
        }
        prepared(fixture) { assertEquals(1, BoundedEpubParser().chapter(it, EpubEntryPath("OPS/chapter.xhtml")).blocks.size) }
    }
    @Test fun legacyRecoveryRetainsTheChapterBlockLimit() = runBlocking<Unit> {
        val fixture = epub2Fixture().apply { change("OPS/chapter.xhtml") { it.replace("</body>", "<p>Original bounded text</p>".repeat(2048) + "</body>") } }
        prepared(fixture) { assertEquals(EpubFailure.LIMIT, assertFailsWith<EpubException> { BoundedEpubParser().chapter(it, EpubEntryPath("OPS/chapter.xhtml")) }.failure) }
    }
    @Test fun declaredEncryptionStillFailsBeforeAnyDocumentIsPublished() = runBlocking<Unit> {
        rejected(epub2Fixture().apply { entries["META-INF/encryption.xml"] = "<encryption/>".encodeToByteArray() }, EpubFailure.ENCRYPTED)
    }
}

internal const val XHTML_DECLARATION = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n"
internal const val NCX_DECLARATION = "<!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\" \"http://www.daisy.org/z3986/2005/ncx-2005-1.dtd\">\n"
internal fun rasterCoverFixture(): EpubFixture = epub2Fixture().apply {
    entries["OPS/Images/cover image.png"] = developmentPng()
    opf { it.replace("</manifest>", "<item id=\"cover\" href=\"Images/cover%20image.png\" media-type=\"image/png\"/></manifest>") }
    entries["OPS/chapter.xhtml"] = """
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Original cover</title></head><body>
        <div><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
            width="100%" height="100%" viewBox="0 0 96 64" preserveAspectRatio="xMidYMid meet" version="1.1">
            <image width="96" height="64" xlink:href="Images/cover%20image.png"/>
        </svg></div></body></html>
    """.trimIndent().encodeToByteArray()
    change("OPS/Nav/toc.ncx") { it.replace("#start", "") }
}
