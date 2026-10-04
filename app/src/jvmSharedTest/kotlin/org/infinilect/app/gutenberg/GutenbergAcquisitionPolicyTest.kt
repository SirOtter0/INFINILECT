// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import kotlin.test.*
import kotlinx.coroutines.CancellationException
import org.infinilect.core.*

class GutenbergAcquisitionPolicyTest {
    private val parser = GutenbergRdfParser(::testGutenbergXml)
    private fun parse(xml: String = rdfFixture()) = parser.parse(xml.encodeToByteArray(), testGutenbergId)
    @Test fun mapsFreshRdfWithOriginalRightsAndCanonicalIdentity() {
        val book = parse(); val p = book.publication
        assertEquals(testGutenbergId,p.id); assertEquals("A Book & 🦦",p.title)
        assertEquals(listOf("First Author","Second Author"),p.authors);assertEquals(listOf("en"),p.languages)
        assertEquals("Public domain in the USA.",p.rights) // metadata CC0 is not the book license
        assertEquals("https://www.gutenberg.org/ebooks/84",p.sourceUrl)
        assertEquals(GutenbergAcquisition.resource(p.id),p.resources.single());assertNull(p.resources.single().revision)
        assertNull(p.resources.single().cacheKey);assertEquals(12,book.text!!.size)
    }
    @Test fun directExplicitUtf8PreferredIndependentlyOfOrder() {
        val generated = rdfFixture(url="https://www.gutenberg.org/ebooks/84.txt.utf-8")
            .substringAfter("<dcterms:hasFormat>").substringBefore("</dcterms:hasFormat>")
        val extra="<dcterms:hasFormat>$generated</dcterms:hasFormat>"
        assertEquals("https://www.gutenberg.org/files/84/84-0.txt",parse(rdfFixture(extra=extra)).text!!.url)
    }
    @Test fun metadataOptionalFieldsAreNotInvented() {
        val p=parse("""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:pg="http://www.gutenberg.org/2009/pgterms/" xmlns:dc="http://purl.org/dc/terms/"><pg:ebook rdf:about="ebooks/84"><dc:title>Title</dc:title></pg:ebook></rdf:RDF>""").publication
        assertTrue(p.authors.isEmpty());assertTrue(p.languages.isEmpty());assertTrue(p.resources.isEmpty());assertNull(p.rights)
    }
    @Test fun nonUtf8MissingCharsetCompressedAndUnknownFormatsNotOffered() {
        for (mime in listOf("text/plain","text/plain; charset=iso-8859-1","text/plain; charset=us-ascii","application/zip","text/html","text/plain; charset=utf-8; charset=utf-8")) {
            assertNull(parse(rdfFixture(mime=mime)).text);assertTrue(parse(rdfFixture(mime=mime)).publication.resources.isEmpty())
        }
    }
    @Test fun foreignAndInvalidIdsRejected() {
        assertFailsWith<IllegalArgumentException> { parser.parse(byteArrayOf(),testGutenbergId.copy(sourceId=SourceId("other"))) }
        for(id in listOf("0","-1","01","1/2","2147483648","99999999999999","1?x","١")) assertFailsWith<IllegalArgumentException>{GutenbergAcquisition.identifier(id)}
    }
    @Test fun wrongEbookOrWrongFileOwnershipRejected() {
        assertFailsWith<InvalidOpdsException>{parse(rdfFixture(id="85"))}
        assertNull(parse(rdfFixture().replace("ebooks/84\"/>","ebooks/85\"/>")).text)
        assertNull(parse(rdfFixture(url="https://www.gutenberg.org/files/85/85-0.txt")).text)
    }
    @Test fun namespaceLookalikesDoNotSupplyTitle() {
        assertFailsWith<InvalidOpdsException>{parse(rdfFixture().replace("<dcterms:title>","<evil:title xmlns:evil='urn:evil'>").replace("</dcterms:title>","</evil:title>"))}
    }
    @Test fun malformedMissingDuplicateAndNestedMetadataRejected() {
        for(xml in listOf("<rdf>","<html/>",rdfFixture().replace("<dcterms:title>","<dcterms:title><x>").replace("</dcterms:title>","</x></dcterms:title>"),rdfFixture(extra="<dcterms:title>Second</dcterms:title>"),rdfFixture().replace("<dcterms:extent>12</dcterms:extent>","<dcterms:extent>12</dcterms:extent><dcterms:extent>12</dcterms:extent>"))) assertFailsWith<InvalidOpdsException>{parse(xml)}
    }
    @Test fun rdfBaseCannotExpandTrust() {
        for(base in listOf("https://evil.invalid/","file:///","https://www.gutenberg.org/ebooks/")) assertFailsWith<InvalidOpdsException>{parse(rdfFixture().replace("xml:base=\"http://www.gutenberg.org/\"", "xml:base='$base'"))}
        assertFailsWith<InvalidOpdsException>{parse(rdfFixture().replace("<dcterms:title>","<dcterms:title xml:base='https://www.gutenberg.org/'>"))}
    }
    @Test fun dtdEntitiesDeepAndOversizedRdfRejected() {
        for(dtd in listOf("<!DOCTYPE rdf:RDF SYSTEM 'file:///private'>","<!DOCTYPE rdf:RDF [<!ENTITY x SYSTEM 'https://evil.invalid/'>]>","<!DOCTYPE rdf:RDF [<!ENTITY x 'expansion'>]>")) assertFailsWith<InvalidOpdsException>{parse(dtd+rdfFixture())}
        assertFailsWith<InvalidOpdsException>{parse(rdfFixture(extra="<x>".repeat(33)+"</x>".repeat(33)))}
        assertFailsWith<InvalidOpdsException>{parser.parse(ByteArray(MAX_FEED_BYTES+1),testGutenbergId)}
    }
    @Test fun cancellationInRdfParserPreserved() {assertFailsWith<CancellationException>{parser.parse(rdfFixture().encodeToByteArray(),testGutenbergId){throw CancellationException()}}}
    @Test fun invalidAndUnknownSizesNeverInvented() {
        assertNull(parse(rdfFixture(size=-1)).text);assertNull(parse(rdfFixture(size=0)).text)
        assertNull(parse(rdfFixture().replace("<dcterms:extent>12</dcterms:extent>","")).text)
        assertFailsWith<InvalidOpdsException>{parse(rdfFixture().replace("<dcterms:extent>12</dcterms:extent>","<dcterms:extent>overflow</dcterms:extent>"))}
    }
    @Test fun officialLocationsAcceptedButOnlyFreshMetadataAuthorizes() {
        for(path in listOf("/ebooks/84.txt.utf-8","/cache/epub/84/pg84.txt","/files/84/84-0.txt")) assertNotNull(GutenbergAcquisition.location("https://www.gutenberg.org$path","84"))
    }
    @Test fun wrongSchemesUserinfoPortsConfusableHostsRejected() {
        for(url in listOf("http://www.gutenberg.org/files/84/84-0.txt","ftp://www.gutenberg.org/files/84/84-0.txt","https://user@www.gutenberg.org/files/84/84-0.txt","https://www.gutenberg.org:443/files/84/84-0.txt","https://www.gutenberg.org:8080/files/84/84-0.txt","https://gutenberg.org.attacker.example/files/84/84-0.txt","https://www.gutenberg.org.evil/files/84/84-0.txt","https://www.gutеnberg.org/files/84/84-0.txt","https://aleph.gutenberg.org/files/84/84-0.txt","https://www.gutenberg.org./files/84/84-0.txt")) assertNull(GutenbergAcquisition.location(url,"84"))
    }
    @Test fun traversalDoubleEncodingQueryFragmentAndWrongIdRejected() {
        for(path in listOf("/files/84/../84-0.txt","/files/84/%2e%2e/84-0.txt","/files/84/%252e%252e.txt","/files/84/84%2d0.txt","/files/84/84-0.txt?x=1","/files/84/84-0.txt#x","/files/85/84-0.txt","/files/84/a\\b.txt","/files/84//84-0.txt")) assertNull(GutenbergAcquisition.location("https://www.gutenberg.org$path","84"))
    }
    @Test fun itemScopedGeneratedRedirectAccepted() {
        val selected="https://www.gutenberg.org/ebooks/84.txt.utf-8"
        assertEquals("https://www.gutenberg.org/cache/epub/84/pg84.txt",GutenbergAcquisition.redirect(selected,"/cache/epub/84/pg84.txt","84",selected))
    }
    @Test fun sameHostDoesNotAuthorizeAnotherItemOrUnselectedFile() {
        val selected="https://www.gutenberg.org/files/84/84-0.txt"
        for(url in listOf("https://evil.invalid/x","//www.gutenberg.org/files/84/84-0.txt","http://www.gutenberg.org/files/84/84-0.txt","https://www.gutenberg.org/files/84/other.txt","https://www.gutenberg.org/cache/epub/85/pg85.txt","http://www.gutenberg.org/https,%20http://www.gutenberg.org/cache/epub/84/pg84.txt")) assertFailsWith<InvalidOpdsException>{GutenbergAcquisition.redirect(selected,url,"84",selected)}
    }
    @Test fun conflictingDuplicateFileExtentsRejected() {
        val f=rdfFixture(size=13).substringAfter("<dcterms:hasFormat>").substringBefore("</dcterms:hasFormat>")
        assertFailsWith<InvalidOpdsException>{parse(rdfFixture(extra="<dcterms:hasFormat>$f</dcterms:hasFormat>"))}
    }

}
