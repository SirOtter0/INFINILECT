// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.reader.EpubException
import org.infinilect.app.reader.EpubFailure
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import kotlin.test.*

class EpubPassiveXhtmlTest {
    private fun html(body:String="<p id=\"start\">Original text</p>")="<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Original</title></head><body>$body</body></html>"
    private suspend fun prepared(fixture:EpubFixture,action:suspend(EpubDocument)->Unit) {
        val root=Files.createTempDirectory("passive-xhtml")
        val owner=FileEpubPreparer(root);val original=fixture.zip();val content=EpubBytes(original)
        try {
            val document=owner.prepare(epubPublication,epubResource,epubLoader(content))
            try {assertContentEquals(original,Files.readAllBytes(payloads(root).single()));action(document)} finally {document.close()}
        } finally {owner.close();owner.awaitClosed();assertTrue(content.closed);assertTrue(payloads(root).isEmpty());root.toFile().deleteRecursively()}
    }
    @Test fun html5DeclarationAcceptsOnlyXmlWhitespaceAndPreservesTheRequiredXhtmlRoot() {
        for(declaration in listOf("<!DOCTYPE html>","<!DOCTYPE\nhtml >","<!DOCTYPE\t\r\nhtml\n>")) {
            val root=parseEpubXml(("<?xml version=\"1.0\"?>\n"+declaration+html()).encodeToByteArray(),EpubLimits(),null)
            assertEquals(XmlName("http://www.w3.org/1999/xhtml","html"),root.name)
        }
        assertEquals("html",parseEpubXml(html().encodeToByteArray(),EpubLimits(),null).name.local)
    }
    @Test fun epub3AndPreviouslySupportedEpub2RemainReadableWithOriginalBytes()=runBlocking<Unit> {
        for(epub2 in listOf(false,true)) {
            val fixture=if(epub2)epub2Fixture() else EpubFixture()
            fixture.change("OPS/chapter.xhtml") {"<!DOCTYPE html>\n"+it}
            prepared(fixture) {document ->
                assertTrue(BoundedEpubParser().chapter(document,EpubEntryPath("OPS/chapter.xhtml")).codePoints>0)
                assertEquals(1,BoundedEpubParser().toc(document).size)
            }
        }
        val legacy="<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Strict//EN' 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd'>"
        assertEquals("html",parseEpubXml((legacy+html("<p>A&nbsp;B</p>")).encodeToByteArray(),EpubLimits(),null).name.local)
    }
    @Test fun shortDoctypeCannotBeUsedForOpfContainerNcxOrAnUnnamespacedHtmlRoot() {
        for(root in listOf("<package xmlns=\"http://www.idpf.org/2007/opf\"/>","<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"/>","<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\"/>","<html/>"))
            assertFailsWith<EpubException>{parseEpubXml(("<!DOCTYPE html>"+root).encodeToByteArray(),EpubLimits(),null)}
    }
    @Test fun externalDtdInternalSubsetsEntitiesAndMalformedDeclarationsStillFailClosed() {
        for(declaration in listOf(
            "<!DOCTYPE html SYSTEM 'file:///original-fixture'>","<!DOCTYPE html SYSTEM 'https://example.org/original.dtd'>",
            "<!DOCTYPE html []>","<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///original-fixture'>]>",
            "<!DOCTYPE html [<!ENTITY x SYSTEM 'https://example.org/original'>]>",
            "<!DOCTYPE html [<!ENTITY a 'aaaa'><!ENTITY b '&a;&a;'>]>","<!DOCTYPE html SYSTEM 'http://127.0.0.1/a'>",
            "<!doctype html>","<!DOCTYPE HTML>","<!DOCTYPE html unexpected>","<!DOCTYPE html", "<!DOCTYPE html><!DOCTYPE html>")) {
            assertFailsWith<EpubException>(declaration){parseEpubXml((declaration+html()).encodeToByteArray(),EpubLimits(),null)}
        }
        // HTML5's short declaration does not declare HTML named entities in XML.
        assertFailsWith<EpubException>{parseEpubXml(("<!DOCTYPE html>"+html("<p>&nbsp;</p>")).encodeToByteArray(),EpubLimits(),null)}
    }
    @Test fun limitsAreCheckedBeforeNormalizingAndOnlyOneBoundedDeclarationIsRecognized() {
        assertEquals(EpubFailure.LIMIT,assertFailsWith<EpubException>{parseEpubXml(("<!DOCTYPE html>"+html()).encodeToByteArray(),EpubLimits(xmlBytes=64),null)}.failure)
        assertFailsWith<EpubException>{parseEpubXml(("<!DOCTYPE "+" ".repeat(512)+"html>"+html()).encodeToByteArray(),EpubLimits(),null)}
    }
    @Test fun publicHttpsCitationsArePreservedAsNonActionableData() {
        for(value in listOf("https://example.org/license?edition=1#credits","HTTPS://EXAMPLE.ORG:443/%C3%A9","https://www.gnu.org/licenses/fdl.html"))
            assertEquals(EpubExternalReference(value),epubExternalReference(value))
        assertNull(epubExternalReference("chapter.xhtml#start"));assertNull(epubExternalReference("#start"))
    }
    @Test fun unsafeAmbiguousPrivateAndOversizedExternalDestinationsAreRejected() {
        for(value in listOf("http://example.org/license","javascript:alert(1)","data:text/html,x","file:///etc/passwd","content://a/x","intent://a/x","mailto:a@example.org",
            "https:example.org/x","https:///example.org/x","https://user@example.org/","https://example.org:80/","https://example.org:444/",
            "http://example.org:443/","https://example.org./","https://example.org\\@evil.org/","https://example.org/%0d%0a","https://example.org/%5c",
            "https://example.org/%ff","https://127.0.0.1/","https://10.0.0.1/","https://[::1]/","https://2130706433/","https://0x7f000001/",
            "https://localhost/","https://host.local/","https://host.internal/","https://a.localhost/","https://a.invalid/","https://example.org/a b",
            "https://example.org/%","https://example.org/"+"a".repeat(512)))
            assertFailsWith<EpubException>(value){epubExternalReference(value)}
    }
    @Test fun citationSemanticsCannotTurnExternalUrlsIntoResourcesOrTocNavigation()=runBlocking<Unit> {
        for(element in listOf("<img src=\"https://example.org/a.png\"/>","<link rel=\"stylesheet\" href=\"https://example.org/a.css\"/>","<script src=\"https://example.org/a.js\"/>")) {
            val fixture=EpubFixture().apply {change("OPS/chapter.xhtml") {it.replace("</body>","$element</body>")}}
            assertFailsWith<EpubException>{prepared(fixture){}}
        }
        val toc=EpubFixture().apply {change("OPS/nav.xhtml") {it.replace("chapter.xhtml#start","https://example.org/chapter")}}
        assertFailsWith<EpubException>{prepared(toc){}}
        for(value in listOf("//example.org/x","javascript:alert(1)","data:text/html,x","file:///a","content://a","intent://a")) {
            val fixture=EpubFixture().apply {change("OPS/chapter.xhtml") {it.replace("</body>","<a href=\"$value\">Original</a></body>")}}
            assertFailsWith<EpubException>{prepared(fixture){}}
        }
    }
    @Test fun citationsRemainSeparateFromInternalLinksThroughMixedRunsAndSemanticCache()=runBlocking<Unit> {
        val fixture=EpubFixture().apply {change("OPS/chapter.xhtml") {
            html("<p id=\"start\">Before <a href=\"https://example.org/license\">License <em>credits</em></a> <a href=\"#start\">Internal</a></p>")
        }}
        prepared(fixture) {document ->
            val parser=BoundedEpubParser();val path=EpubEntryPath("OPS/chapter.xhtml")
            val chapter=parser.chapter(document,path);val cold=parser.window(document,path);val warm=parser.window(document,path)
            assertEquals(chapter.blocks,cold.blocks);assertEquals(cold,warm)
            val runs=warm.blocks.single().runs
            assertEquals("Before License credits Internal",warm.blocks.single().text)
            assertEquals(2,runs.count {it.externalReference==EpubExternalReference("https://example.org/license")})
            assertTrue(runs.filter {it.externalReference!=null}.all {it.target==null})
            assertEquals(EpubTarget(path,"start"),runs.single {it.text=="Internal"}.target)
            assertTrue(runs.single {it.text=="credits"}.emphasis)
            assertEquals(1,(document as EpubSemanticCacheOwner).semanticCache().builds)
            assertFailsWith<IllegalArgumentException>{document.openResource(EpubEntryPath("OPS/not-in-manifest"))}
        }
    }
}
