// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.reader.EpubException
import org.infinilect.app.reader.EpubFailure
import org.infinilect.core.*
import kotlin.test.*

/** Original synthetic packages; no downloaded book or attribution is rewritten. */
class EpubRightsMetadataTest {
    private fun fixture(epub2:Boolean=false, rights:String)=
        (if(epub2)epub2Fixture() else EpubFixture()).apply {
            opf {it.replace("<dc:rights>Original test fixture</dc:rights>",rights)}
        }
    private suspend fun prepared(fixture:EpubFixture, action:suspend (EpubDocument)->Unit) {
        val root=Files.createTempDirectory("epub-rights-test")
        val owner=FileEpubPreparer(root)
        try {
            val original=fixture.zip()
            val document=owner.prepare(epubPublication,epubResource,epubLoader(EpubBytes(original)))
            assertContentEquals(original,Files.readAllBytes(payloads(root).single()),"Attribution and license links in the original ZIP must remain untouched")
            try {action(document)} finally {document.close()}
        } finally {owner.close();owner.awaitClosed();assertTrue(payloads(root).isEmpty());root.toFile().deleteRecursively()}
    }
    private suspend fun rejected(fixture:EpubFixture, expected:EpubFailure, limits:EpubLimits=EpubLimits()) {
        val root=Files.createTempDirectory("epub-rights-rejected")
        val owner=FileEpubPreparer(root,limits)
        try {
            val error=assertFailsWith<EpubException> {owner.prepare(epubPublication,epubResource,epubLoader(EpubBytes(fixture.zip())))}
            assertEquals(expected,error.failure)
            assertTrue(payloads(root).isEmpty())
        } finally {owner.close();owner.awaitClosed();root.toFile().deleteRecursively()}
    }
    @Test fun epub3AcceptsMultipleDeclarationsWithoutRewritingChapterBytes()=runBlocking<Unit> {
        val fixture=fixture(rights="<dc:rights>Original license A</dc:rights><dc:rights>Original license B</dc:rights>")
        prepared(fixture) {document ->
            assertEquals(listOf(EpubRightsStatement("Original license A"),EpubRightsStatement("Original license B")),document.metadata.rights)
            assertContentEquals(fixture.entries.getValue("OPS/chapter.xhtml"),document.openResource(EpubEntryPath("OPS/chapter.xhtml")).readBytes(4096))
        }
    }
    @Test fun epub2WithoutRightsKeepsAnEmptyList()=runBlocking<Unit> {
        prepared(fixture(epub2=true,rights="")) {assertTrue(it.metadata.rights.isEmpty())}
    }
    @Test fun epub2SingleDeclarationRetainsItsLiteralValue()=runBlocking<Unit> {
        prepared(fixture(epub2=true,rights="<dc:rights>Original single statement</dc:rights>")) {
            assertEquals(listOf(EpubRightsStatement("Original single statement")),it.metadata.rights)
        }
    }
    @Test fun epub2MultipleStatementsKeepOrderAcrossOtherMetadataElements()=runBlocking<Unit> {
        prepared(fixture(epub2=true,rights="<dc:rights>First</dc:rights><dc:publisher>Original publisher</dc:publisher><dc:rights>Second</dc:rights>")) {
            assertEquals(listOf("First","Second"),it.metadata.rights.map {statement->statement.text})
        }
    }
    @Test fun epub3WithoutRightsRemainsSupported()=runBlocking<Unit> {
        prepared(fixture(rights="")) {assertTrue(it.metadata.rights.isEmpty())}
    }
    @Test fun duplicatesWhitespaceUnicodeAndDecodedEntitiesAreNotDroppedOrCombined()=runBlocking<Unit> {
        for(epub2 in listOf(false,true)) prepared(fixture(epub2,"<dc:rights>  Créditos 李 📚 &amp; autores\n</dc:rights><dc:rights>Duplicate</dc:rights><dc:rights>Duplicate</dc:rights>")) {
            assertEquals(listOf("  Créditos 李 📚 & autores\n","Duplicate","Duplicate"),it.metadata.rights.map {statement->statement.text})
        }
    }
    @Test fun identifiersAndExplicitOrInheritedLanguageAndDirectionRemainSeparate()=runBlocking<Unit> {
        for(epub2 in listOf(false,true)) {
            val fixture=fixture(epub2,"<dc:rights id=\"credits\" xml:lang=\"ca\" dir=\"ltr\">Crèdits</dc:rights><dc:rights>Inherited</dc:rights>").apply {
                opf {it.replace("unique-identifier=\"book\"","unique-identifier=\"book\" xml:lang=\"en\" dir=\"ltr\"")
                    .replace("<metadata ","<metadata xml:lang=\"es\" dir=\"rtl\" ")}
            }
            prepared(fixture) {
                assertEquals(listOf(EpubRightsStatement("Crèdits","ca","credits","ltr"),EpubRightsStatement("Inherited","es",direction="rtl")),it.metadata.rights)
            }
        }
    }
    @Test fun statementsDoNotMakeMissingRequiredMetadataValid()=runBlocking<Unit> {
        for(epub2 in listOf(false,true)) for(tag in listOf("identifier","title","language")) {
            rejected(fixture(epub2,"<dc:rights>A</dc:rights><dc:rights>B</dc:rights>").apply {
                opf {it.replace(Regex("<dc:$tag[^>]*>.*?</dc:$tag>"),"")}
            },EpubFailure.INVALID)
        }
    }
    @Test fun blankOrNonTextDeclarationsRemainInvalid()=runBlocking<Unit> {
        for(rights in listOf("<dc:rights> </dc:rights>","<dc:rights>A<dc:creator>B</dc:creator>C</dc:rights>"))
            rejected(fixture(rights=rights),EpubFailure.INVALID)
    }
    @Test fun declarationCountHasAnInclusiveFiniteCeiling()=runBlocking<Unit> {
        for(epub2 in listOf(false,true)) {
            prepared(fixture(epub2,"<dc:rights>A</dc:rights>".repeat(32))) {assertEquals(32,it.metadata.rights.size)}
            rejected(fixture(epub2,"<dc:rights>A</dc:rights>".repeat(33)),EpubFailure.LIMIT)
        }
    }
    @Test fun aggregateRightsTextIsBoundedInAdditionToExistingXmlAndPerElementLimits()=runBlocking<Unit> {
        val declaration="<dc:rights>${"A".repeat(8192)}</dc:rights>"
        prepared(fixture(rights=declaration.repeat(4))) {assertEquals(32768,it.metadata.rights.sumOf {statement->statement.text.length})}
        rejected(fixture(rights=declaration.repeat(4)+"<dc:rights>B</dc:rights>"),EpubFailure.LIMIT)
        rejected(fixture(rights="<dc:rights>${"A".repeat(8193)}</dc:rights>"),EpubFailure.LIMIT)
        rejected(fixture(rights=declaration),EpubFailure.LIMIT,EpubLimits(xmlBytes=1024))
    }
    @Test fun testsCanLowerMetadataLimitsButCannotRaiseProductionCeilings()=runBlocking<Unit> {
        rejected(fixture(rights="<dc:rights>A</dc:rights><dc:rights>B</dc:rights>"),EpubFailure.LIMIT,EpubLimits(rightsDeclarations=1))
        rejected(fixture(rights="<dc:rights>ABC</dc:rights>"),EpubFailure.LIMIT,EpubLimits(rightsTextUnits=2))
        assertFailsWith<IllegalArgumentException>{EpubLimits(rightsDeclarations=33)}
        assertFailsWith<IllegalArgumentException>{EpubLimits(rightsTextUnits=32769)}
    }
    @Test fun xmlEntitiesProcessingInstructionsAndXincludeCannotBypassMetadataValidation()=runBlocking<Unit> {
        val malicious=fixture(rights="<dc:rights>&credits;</dc:rights>").apply {
            opf {"<!DOCTYPE package [<!ENTITY credits SYSTEM 'file:///not-authorized-original-fixture'>]>"+it}
        }
        rejected(malicious,EpubFailure.INVALID)
        rejected(fixture(rights="<dc:rights>A<?untrusted instruction?>B</dc:rights>"),EpubFailure.INVALID)
        rejected(fixture(rights="<dc:rights><xi:include xmlns:xi=\"http://www.w3.org/2001/XInclude\" href=\"https://example.invalid/fixture\"/></dc:rights>"),EpubFailure.INVALID)
    }
    @Test fun acceptingMultipleRightsDoesNotAuthorizeExternalContentOrAttributionTargets()=runBlocking<Unit> {
        for(element in listOf("<a href=\"https://example.invalid/original-license\">Original attribution</a>","<img src=\"https://example.invalid/original.png\"/>")) {
            rejected(fixture(rights="<dc:rights>A</dc:rights><dc:rights>B</dc:rights>").apply {
                change("OPS/chapter.xhtml") {it.replace("</body>","$element</body>")}
            },EpubFailure.INVALID)
        }
    }
    @Test fun multipleRightsAndInertHtml5DoctypePreserveTheOriginalArchive()=runBlocking<Unit> {
        prepared(fixture(rights="<dc:rights>A</dc:rights><dc:rights>B</dc:rights>").apply {
            change("OPS/nav.xhtml") {"<!DOCTYPE html>\n"+it}
        }) { assertEquals(listOf("A","B"),it.metadata.rights.map {statement->statement.text}) }
    }
}
