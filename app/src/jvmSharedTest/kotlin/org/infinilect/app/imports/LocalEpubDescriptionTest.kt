// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.epub.*
import org.infinilect.app.ui.DescriptionPolicy
import kotlin.test.*

class LocalEpubDescriptionTest {
    private suspend fun extract(f: EpubFixture): String? {
        val file=Files.createTempFile("original-description", ".epub")
        try {Files.write(file,f.zip());return localEpubDescription(file)} finally {Files.delete(file)}
    }
    private fun fixture(value:String, epub2:Boolean=false)= (if(epub2)epub2Fixture() else EpubFixture()).apply {
        opf {it.replace("</metadata>", "$value</metadata>")}
    }
    @Test fun epub2And3RepeatedDescriptionsPreserveNonAsciiParagraphsAndSkipEmptyDuplicates()=runBlocking<Unit> {
        for(epub2 in listOf(false,true)) {
            val f=fixture("<dc:description>Primera descripción &amp; historia.\n\nSegundo párrafo 📖.</dc:description><dc:description> </dc:description><dc:description>Otra perspectiva.</dc:description><dc:description>Otra perspectiva.</dc:description>",epub2)
            assertEquals("Primera descripción & historia.\n\nSegundo párrafo 📖.\n\nOtra perspectiva.",extract(f))
        }
    }
    @Test fun escapedHtmlAndEmbeddedMarkupArePassivePlainTextWithoutActiveBodiesOrUris()=runBlocking<Unit> {
        val f=fixture("<dc:description>&lt;p&gt;An &lt;b&gt;original&lt;/b&gt; story&amp;nbsp; here.&lt;/p&gt;&lt;script&gt;doBadThings()&lt;/script&gt;&lt;p&gt;Second paragraph.&lt;img src='https://example.test/a'/&gt;&lt;/p&gt;</dc:description>")
        assertEquals("An original story here.\n\nSecond paragraph.",extract(f))
        val embedded=fixture("<dc:description><p xmlns='http://www.w3.org/1999/xhtml'>First <b>paragraph</b>.</p><p xmlns='http://www.w3.org/1999/xhtml'>Second.</p></dc:description>")
        assertEquals("First paragraph.\n\nSecond.",extract(embedded))
    }
    @Test fun absentBlankAndWrongNamespaceDescriptionsAreOmitted()=runBlocking<Unit> {
        assertNull(extract(EpubFixture()));assertNull(extract(fixture("<dc:description> \n </dc:description>")))
        assertNull(extract(fixture("<description xmlns='urn:untrusted'>Not DC</description>")))
    }
    @Test fun extractionNeverParsesChapterContentOrDecodesImages()=runBlocking<Unit> {
        val f=fixture("<dc:description>Original metadata.</dc:description>")
        f.entries["OPS/chapter.xhtml"]="Not XML. Not required for optional metadata after import validation.".repeat(3000).encodeToByteArray()
        assertEquals("Original metadata.",extract(f))
    }
    @Test fun longDescriptionWithinXmlBoundsAndAggregateLimitAreExplicit()=runBlocking<Unit> {
        val value="A long original paragraph. ".repeat(280)
        assertEquals(value.trim(),extract(fixture("<dc:description>$value</dc:description>")))
        assertFails {extract(fixture("<dc:description>${"x".repeat(8193)}</dc:description>"))}
        assertFails {extract(fixture((1..3).joinToString(""){"<dc:description>${"x".repeat(6000)}</dc:description>"}))}
        assertFails {extract(fixture((1..DescriptionPolicy.VALUES+1).joinToString(""){"<dc:description>Value $it</dc:description>"}))}
    }
    @Test fun unsafeXmlEncryptionAndExternalRootfilesRemainRejected()=runBlocking<Unit> {
        val dtd=fixture("<dc:description>&steal;</dc:description>")
        dtd.opf {"<!DOCTYPE package [<!ENTITY steal SYSTEM 'file:///private/not-opened'>]>$it"};assertFails {extract(dtd)}
        val encrypted=fixture("<dc:description>Text</dc:description>");encrypted.entries["META-INF/encryption.xml"]="<encryption/>".encodeToByteArray();assertFails{extract(encrypted)}
        val external=fixture("<dc:description>Text</dc:description>")
        external.change("META-INF/container.xml"){it.replace("OPS/package.opf","https://example.test/package.opf")};assertFails{extract(external)}
    }
    @Test fun malformedMetadataTraversalAndCanonicalCollisionsFailClosed()=runBlocking<Unit> {
        assertFails {extract(fixture("<dc:description>broken</metadata>"))}
        val traversal=fixture("<dc:description>Text</dc:description>");traversal.entries["../bad"]="bad".encodeToByteArray();assertFails{extract(traversal)}
        val collision=fixture("<dc:description>Text</dc:description>");collision.entries["é.txt"]="a".encodeToByteArray();collision.entries["e\u0301.txt"]="b".encodeToByteArray();assertFails{extract(collision)}
    }
    @Test fun exactMimetypeAndCrcAreRequired()=runBlocking<Unit> {
        val f=fixture("<dc:description>Text</dc:description>");f.entries["mimetype"]="wrong".encodeToByteArray();assertFails{extract(f)}
        val bytes=fixture("<dc:description>Original CRC marker</dc:description>").zip()
        val needle="Original CRC marker".encodeToByteArray()
        val at=(0..bytes.size-needle.size).first{bytes.copyOfRange(it,it+needle.size).contentEquals(needle)}
        bytes[at]='X'.code.toByte() // Valid XML, same size, incorrect declared CRC.
        val file=Files.createTempFile("original-description-crc", ".epub")
        try {Files.write(file,bytes);assertFails{localEpubDescription(file)}} finally {Files.delete(file)}
    }
    @Test fun numericEntitiesUnknownEntitiesAndOrdinaryComparisonsStaySafeText() {
        assertEquals("A & B 📖 &unknown; 2 < 3 > 1",plainDescription("A &amp; B &#x1f4d6; &unknown; 2 < 3 > 1"))
        assertEquals("&lt;p&gt;",plainDescription("&amp;lt;p&amp;gt;")) // no recursive decoding
        assertEquals("Safe\n\nLast",plainDescription("<p>Safe</p><style>hidden</style><p>Last</p>"))
        assertEquals("One wrapped line\n\nNext paragraph",plainDescription("One\r\nwrapped line\r\n\r\nNext paragraph"))
    }
}
