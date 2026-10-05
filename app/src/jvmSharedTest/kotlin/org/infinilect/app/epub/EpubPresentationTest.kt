// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import kotlinx.coroutines.*
import kotlin.test.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*

/** Host tests of the real shared parser, no browser fake or Internet. */
class EpubPresentationTest {
    private val chapter = EpubEntryPath("OPS/chapter.xhtml")
    private fun xhtml(body: String) = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Title</title></head><body>$body</body></html>"
    private class Document(val files: Map<String, String>, val beforeRead: suspend () -> Unit = {}) : EpubDocument {
        override val publicationId = epubId
        override val packagePath = EpubEntryPath("OPS/package.opf")
        override val metadata = EpubMetadata("id", "Title", listOf("en"), "2026-10-04T00:00:00Z")
        override val manifest = files.keys.mapIndexed { index, path -> EpubManifestItem(if (path.endsWith("nav.xhtml")) "nav" else "chapter$index", EpubEntryPath(path), "application/xhtml+xml") }
        override val spine = manifest.filter { it.id != "nav" }.map { EpubSpineItem(it.id) }
        override val navigationItemId = "nav"
        var opened = 0; var handlesClosed = 0
        override suspend fun openResource(path: EpubEntryPath): ResourceContent {
            opened++
            val bytes = files.getValue(path.value).encodeToByteArray()
            val handle = EpubBytes(bytes, chunk = 3, beforeRead = beforeRead)
            return object : ResourceContent by handle { override fun close() { if (!handle.closed) handlesClosed++; handle.close() } }
        }
        override fun close() {}
    }
    private suspend fun parse(body: String): EpubChapter {
        val doc = Document(mapOf(chapter.value to xhtml(body)))
        return try { BoundedEpubParser().chapter(doc, chapter) } finally { assertEquals(doc.opened, doc.handlesClosed) }
    }
    private suspend fun reject(body: String) { assertFailsWith<EpubException> { parse(body) } }
    @Test fun singleSpineParagraphRendersSemanticText() = runBlocking { assertEquals("Hello", parse("<p>Hello</p>").blocks.single().text) }
    @Test fun mixedContentPreservesOrderAndInlineStyle() = runBlocking {
        val block = parse("<p>A<em>B<strong>📖</strong>C</em>D</p>").blocks.single()
        assertEquals("AB📖CD", block.text)
        assertTrue(block.runs.single { it.text == "📖" }.strong)
        assertTrue(block.runs.single { it.text == "📖" }.emphasis)
    }
    @Test fun unicodeAndSupplementaryOffsetsCountCodePoints() = runBlocking {
        val value = parse("<p id=\"a\">世界📖é</p>")
        assertEquals(4, value.codePoints)
        assertEquals(3, value.blocks.single().text.epubPointAtUtf16(4))
        assertEquals(2, value.blocks.single().text.epubPointAtUtf16(3))
    }
    @Test fun headingsListsAndQuotesAreBlocks() = runBlocking {
        val value = parse("<h2>Title</h2><ul><li>One</li><li>Two</li></ul><blockquote><p>Quote</p></blockquote>")
        assertEquals(listOf(EpubBlockKind.HEADING, EpubBlockKind.LIST_ITEM, EpubBlockKind.LIST_ITEM, EpubBlockKind.QUOTE), value.blocks.map { it.kind })
    }
    @Test fun nestedBlockOrderDoesNotMoveTrailingTextBeforeChildren() = runBlocking {
        assertEquals(listOf("Before", "Middle", "After"), parse("<div>Before<p>Middle</p>After</div>").blocks.map { it.text })
    }
    @Test fun basicSectionsAndUnknownPassiveElementsPreserveContent() = runBlocking { assertEquals("ABC", parse("<section><p>A<mark>B</mark>C</p></section>").blocks.single().text) }
    @Test fun lineBreaksRemainLineBreaks() = runBlocking { assertEquals("A\nB", parse("<p>A<br/>B</p>").blocks.single().text) }
    @Test fun internalLinksAreOwnedSemanticTargets() = runBlocking {
        val value = parse("<p id=\"start\">Hello <a href=\"#start\">return</a></p>")
        assertEquals(EpubTarget(chapter, "start"), value.blocks.single().runs.last().target)
        assertEquals(EpubPosition(listOf(0, 0), 0), value.anchors["start"])
    }
    @Test fun anchorsInsideParagraphHaveLogicalOffsets() = runBlocking { assertEquals(2, parse("<p>A📖<span id=\"anchor\">B</span></p>").anchors["anchor"]?.codePointOffset) }
    @Test fun externalNetworkLinkIsRejected() = runBlocking { reject("<p><a href=\"https://example.org/book\">link</a></p>") }
    @Test fun fileLinkIsRejected() = runBlocking { reject("<p><a href=\"file:///private/book\">link</a></p>") }
    @Test fun contentSchemeIsRejected() = runBlocking { reject("<p><a href=\"content://provider/book\">link</a></p>") }
    @Test fun javascriptLinkCannotExecute() = runBlocking { reject("<p><a href=\"javascript:alert(1)\">link</a></p>") }
    @Test fun traversalAndEncodedAliasesAreRejected() = runBlocking { for (href in listOf("../../escape.xhtml", "%2e%2e/book", "chapter.xhtml?x=1", "//evil.example/book")) reject("<p><a href=\"$href\">link</a></p>") }
    @Test fun undeclaredManifestResourceIsRejected() = runBlocking { reject("<p><a href=\"missing.xhtml\">link</a></p>") }
    @Test fun activeElementsCannotReachRenderer() = runBlocking { for (tag in listOf("script", "iframe", "object", "embed", "form", "video", "input")) reject("<p>text</p><$tag/>") }
    @Test fun eventAttributesAreRejected() = runBlocking { reject("<p onclick=\"alert(1)\">text</p>") }
    @Test fun foreignSvgCannotBecomeExecutableContent() = runBlocking { reject("<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>") }
    @Test fun cssDoesNotFetchOrBecomeAnEngine() = runBlocking { assertEquals("Hello", parse("<p style=\"background:url(https://evil.example)\">Hello</p>").blocks.single().text) }
    @Test fun malformedXhtmlIsRejectedAndHandleCloses() = runBlocking { reject("<p>unfinished") }
    @Test fun dtdAndExternalEntitiesAreRejected() = runBlocking {
        val doc = Document(mapOf(chapter.value to "<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///secret'>]>${xhtml("<p>&x;</p>")}"))
        assertFailsWith<EpubException> { BoundedEpubParser().chapter(doc, chapter) }; assertEquals(1, doc.handlesClosed)
    }
    @Test fun xmlBaseAndXincludeAreRejected() = runBlocking { reject("<p xml:base=\"https://evil.example\">x</p>"); reject("<include xmlns=\"http://www.w3.org/2001/XInclude\" href=\"secret\"/>") }
    @Test fun excessiveNestingIsRejected() = runBlocking { reject("<div>".repeat(33) + "x" + "</div>".repeat(33)) }
    @Test fun excessiveModelBlocksAreRejected() = runBlocking { reject("<p>x</p>".repeat(2049)) }
    @Test fun excessiveSingleBlockTextIsRejected() = runBlocking { reject("<p>${"x".repeat(8193)}</p>") }
    @Test fun totalChapterTextIsBounded() = runBlocking { reject((1..40).joinToString("") { "<p>${"x".repeat(7000)}</p>" }) }
    @Test fun excessiveLinksAreRejected() = runBlocking { reject((1..513).joinToString("") { "<p id=\"p$it\"><a href=\"#p$it\">x</a></p>" }) }
    @Test fun emptyBodyHasClearUnsupportedFailure() = runBlocking { reject("<p> </p>") }
    @Test fun duplicateAnchorIsRejected() = runBlocking { reject("<p id=\"same\">a</p><p id=\"same\">b</p>") }
    @Test fun codePointRestorationClampsStaleOffsets() = runBlocking {
        val c = parse("<p>A📖BC</p><p>Later</p>")
        assertEquals(0 to 4, c.locate(ReadingLocator.Epub(chapter, c.blocks.first().elementPath, Long.MAX_VALUE, 0.0)))
    }
    @Test fun structuralChangeFallsBackToChapterProgression() = runBlocking {
        val c = parse("<p>12345</p><p>67890</p>")
        assertEquals(1 to 2, c.locate(ReadingLocator.Epub(chapter, listOf(9), 0, 0.7)))
    }
    @Test fun progressionDoesNotDependOnPixelsOrViewport() = runBlocking {
        val c = parse("<p>A📖BC</p><p>Later</p>")
        val locator = c.locator(0, 2)
        assertEquals(0 to 2, c.locate(locator)); assertEquals(2.0 / 9, locator.chapterProgression)
    }
    @Test fun tocPreservesUnicodeOrderAndOwnedTargets() = runBlocking {
        val nav = "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>TOC</title></head><body><nav epub:type=\"toc\"><ol><li><a href=\"chapter.xhtml#start\">世界 <em>📖</em></a></li></ol></nav></body></html>"
        val doc = Document(mapOf(chapter.value to xhtml("<p id=\"start\">Hi</p>"), "OPS/nav.xhtml" to nav))
        assertEquals("世界 📖", BoundedEpubParser().toc(doc).single().label); assertEquals(doc.opened, doc.handlesClosed)
    }
    @Test fun imagesAreAltTextOnlyAndNeverDecoded() = runBlocking {
        val doc = Document(mapOf(chapter.value to xhtml("<p><img src=\"image.xhtml\" alt=\"Original illustration\"/></p>"), "OPS/image.xhtml" to xhtml("<p>unused</p>")))
        assertEquals("[Image: Original illustration]", BoundedEpubParser().chapter(doc, chapter).blocks.single().text)
        assertEquals(1, doc.opened)
    }
    @Test fun cancellationBeforeLaunchOpensNoResource() = runBlocking {
        val doc = Document(mapOf(chapter.value to xhtml("<p>Hello</p>")))
        val work = launch(start = CoroutineStart.LAZY) { BoundedEpubParser().chapter(doc, chapter) }
        work.cancel(); work.join(); assertEquals(0, doc.opened)
    }
    @Test fun cancellationDuringResourceReadClosesHandle() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val doc = Document(mapOf(chapter.value to xhtml("<p>Hello</p>"))) { entered.complete(Unit); awaitCancellation() }
        val work = launch { BoundedEpubParser().chapter(doc, chapter) }
        entered.await(); work.cancelAndJoin(); assertEquals(1, doc.handlesClosed)
    }
    @Test fun cancelledXmlJobStopsBeforeModelPublication() = runBlocking<Unit> {
        val job = Job(); job.cancel()
        assertFailsWith<CancellationException> { parseEpubXml(xhtml("<p>Never published</p>").encodeToByteArray(), EpubLimits(), job) }
    }
    @Test fun tocCountAndLabelsAreBounded() = runBlocking<Unit> {
        fun nav(links: String) = "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>TOC</title></head><body><nav epub:type=\"toc\"><ol>$links</ol></nav></body></html>"
        for (links in listOf("<li><a href=\"chapter.xhtml\">${"x".repeat(513)}</a></li>", "<li><a href=\"chapter.xhtml\">x</a></li>".repeat(257))) {
            val doc = Document(mapOf(chapter.value to xhtml("<p>Hello</p>"), "OPS/nav.xhtml" to nav(links)))
            assertFailsWith<EpubException> { BoundedEpubParser().toc(doc) }; assertEquals(doc.opened, doc.handlesClosed)
        }
    }
    @Test fun oversizedXmlClosesHandleAndReturnsLimitCategory() = runBlocking<Unit> {
        val doc = Document(mapOf(chapter.value to "x".repeat(1024 * 1024 + 1)))
        assertEquals(EpubFailure.LIMIT, assertFailsWith<EpubException> { BoundedEpubParser().chapter(doc, chapter) }.failure)
        assertEquals(1, doc.handlesClosed)
    }
    @Test fun orderedXmlCoalescesCharacterEventsWithoutPerCharacterNodes() = runBlocking<Unit> {
        val parsed = parseEpubXml(xhtml("<p>A&amp;B<![CDATA[C]]>D<em>E</em>F</p>").encodeToByteArray(), EpubLimits(), coroutineContext[Job])
        val paragraph = parsed.children("http://www.w3.org/1999/xhtml", "body").single().children.single()
        assertEquals(3, paragraph.content.size)
        assertEquals("A&BCDF", paragraph.text.toString())
        assertEquals("A&BCDEF", parse("<p>A&amp;B<![CDATA[C]]>D<em>E</em>F</p>").blocks.single().text)
    }

    @Test fun boundedAltTextNeverCutsSupplementaryCodePoint() = runBlocking<Unit> {
        val alt = "a".repeat(255) + "📖" + "b"
        val doc = Document(mapOf(chapter.value to xhtml("<p><img src=\"image.xhtml\" alt=\"$alt\"/></p>"), "OPS/image.xhtml" to xhtml("<p>unused</p>")))
        val rendered = BoundedEpubParser().chapter(doc, chapter).blocks.single().text
        assertEquals("[Image: ${"a".repeat(255)}]", rendered)
        assertFalse(rendered.any { it.isSurrogate() })
    }

}
