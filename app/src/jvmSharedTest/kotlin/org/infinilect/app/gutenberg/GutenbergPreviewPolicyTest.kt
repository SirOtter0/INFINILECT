// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import kotlinx.coroutines.CancellationException
import kotlin.test.*
import org.infinilect.core.*

class GutenbergPreviewPolicyTest {
    private val id=PublicationId(GUTENBERG_ID,"84")
    private fun book(value: String=previewPublication())=GutenbergOpds2Parser.detail(previewBytes(value),id)
    @Test fun rootAcceptsObservedSearchTemplateAndDoesNotFollowNavigation() {
        assertEquals(GUTENBERG_SEARCH_TEMPLATE,GutenbergOpds2Parser.root(previewBytes(previewRoot)))
    }
    @Test fun rootRejectsMissingWrongHostOrDifferentTemplate() {
        for(value in listOf("{}",previewRoot.replace("{?query,title,author}","{?q}"),previewRoot.replace("opds-test.pglaf.org","evil.example"),previewRoot.replace("true","\"true\"")))
            assertFailsWith<InvalidOpdsException>{GutenbergOpds2Parser.root(previewBytes(value))}
    }
    @Test fun mapsStableIdentityCanonicalMetadataAndDescriptiveEpub() {
        val publication=book();assertEquals(id,publication.id);assertEquals("https://www.gutenberg.org/ebooks/84",publication.sourceUrl)
        assertEquals(listOf("Author, One","Autor 二"),publication.authors);assertEquals(listOf("en","es"),publication.languages)
        assertEquals(PublicationType.BOOK,publication.type);assertEquals(PublicationFormat.EPUB,publication.resources.single().format)
        assertEquals("epub",publication.resources.single().key);assertNull(publication.resources.single().revision);assertNull(publication.resources.single().cacheKey)
    }
    @Test fun singletonAuthorLanguageAndMissingOptionalMetadata() {
        val value=previewPublication().replace("[\"en\",\"es\"]","\"en\"").replace("[{\"name\":\"Author, One\"},{\"name\":\"Autor 二\"}]","{\"name\":\"Single\"}")
        assertEquals(listOf("Single"),book(value).authors);assertEquals(listOf("en"),book(value).languages)
        val minimal=previewPublication().replace(",\"language\":[\"en\",\"es\"],\"author\":[{\"name\":\"Author, One\"},{\"name\":\"Autor 二\"}]","")
        assertTrue(book(minimal).authors.isEmpty());assertTrue(book(minimal).languages.isEmpty());assertNull(book(minimal).rights)
    }
    @Test fun doesNotInventRightsFromDescriptionOrMetadataLicense() {
        assertNull(book(previewPublication(extra=",\"description\":\"Rights: Public domain in the USA.\",\"license\":\"CC0 metadata\"")).rights)
        assertEquals("Source statement USA only",book(previewPublication(extra=",\"rights\":\"Source statement USA only\"")).rights)
    }
    @Test fun emptySearchAndExplicitPagination() {
        assertTrue(GutenbergOpds2Parser.search(previewBytes(previewPage(publications="")),"books",1).publications.isEmpty())
        val page=GutenbergOpds2Parser.search(previewBytes(previewPage(next=true)),"books",1)
        val token=assertNotNull(page.nextPageToken);assertFalse(token.startsWith("https:"))
        assertEquals("https://opds-test.pglaf.org/opds/search?limit=25&query=books&page=2",GutenbergUrls.pageUrl(token,"books"))
    }
    @Test fun queryEncodingCannotInjectPaginationOrAuthority() {
        val query="café & page=9 / ? 🦦"
        val url=io.ktor.http.Url(GutenbergUrls.search(query));assertEquals(query,url.parameters["query"]);assertEquals(setOf("query"),url.parameters.names())
    }
    @Test fun rejectsInvalidQueries() {
        for(query in listOf(" ","x".repeat(257),"hello\nworld"))assertFailsWith<IllegalArgumentException>{GutenbergUrls.search(query)}
    }
    @Test fun rejectsInvalidStableIdsAndForeignCanonicalUrls() {
        for(id in listOf("0","01","-1","2147483648","84/../11"))assertFailsWith<IllegalArgumentException>{GutenbergUrls.identifier(id)}
        for(url in listOf("http://www.gutenberg.org/ebooks/84","https://www.gutenberg.org/ebooks/84?x=1","https://www.gutenberg.org.evil/ebooks/84"))assertNull(GutenbergUrls.localId(url))
        assertFailsWith<InvalidOpdsException>{book(previewPublication("11"))}
    }
    @Test fun wrongDetailSelfCrossItemAndForeignSelfAreRejected() {
        for(value in listOf(previewPublication().replace("publications?id=84","publications?id=11"),previewPublication().replace("https://opds-test.pglaf.org/opds/publications", "https://evil.example/opds/publications")))
            assertFailsWith<InvalidOpdsException>{book(value)}
    }
    @Test fun descriptiveEpubRejectsWrongItemAndHostileUrls() {
        val original="https://www.gutenberg.org/cache/epub/84/pg84-images-3.epub"
        for(url in listOf("http://www.gutenberg.org/cache/epub/84/pg84-images-3.epub","https://evilgutenberg.org/a","https://www.gutenberg.org.evil/a","https://foo.www.gutenberg.org/a","https://user@www.gutenberg.org/cache/epub/84/pg84-images-3.epub",original+"?x=1",original+"#x",original.replace(".org/",".org:443/"),original.replace("84/pg84","11/pg11"),original.replace("/84/","/84/../84/"),original.replace("/84/","/84/%2e%2e/84/"),original.replace("/84/","/84/%252e%252e/84/"),original.replace("/84/","/84\\")))
            assertFailsWith<InvalidOpdsException>{book(previewPublication().replace(original,url))}
    }
    @Test fun malformedAcquisitionRelationsCannotGrantPermission() {
        for(rel in listOf("http://opds-spec.org/acquisition","http://opds-spec.org/acquisition/borrow","http://opds-spec.org/acquisition/open-access evil"))
            assertFailsWith<InvalidOpdsException>{book(previewPublication().replace("http://opds-spec.org/acquisition/open-access",rel))}
    }
    @Test fun unknownMimeAndTextCharsetNeverBecomeReadableText() {
        for(mime in listOf("text/plain; charset=utf-8","text/plain; charset=latin1","text/plain","application/x-unknown"))
            assertTrue(book(previewPublication().replace("application/epub+zip",mime)).resources.isEmpty())
    }
    @Test fun staleAndMalformedTokensRejected() {
        val token=GutenbergUrls.nextToken("https://opds-test.pglaf.org/opds/search?limit=25&query=books&page=2","books",1)
        assertFailsWith<IllegalArgumentException>{GutenbergUrls.pageUrl(token,"other")}
        for(tokenValue in listOf("gutenberg-opds-1:anything","https://evil/","gutenberg-opds2-preview-1:%"))assertFailsWith<Exception>{GutenbergUrls.pageUrl(tokenValue,"books")}
    }
    @Test fun pageUrlsRejectAuthorityTraversalQueryConfusionAndLoops() {
        val original="https://opds-test.pglaf.org/opds/search?limit=25&query=books&page=2"
        for(value in listOf(original.replace("https:","http:"),original.replace("opds-test.pglaf.org","opds-test.pglaf.org.evil"),original.replace("opds-test.pglaf.org","user@opds-test.pglaf.org"),original.replace(".org/",".org:444/"),original+"#a",original+"&other=1",original+"&page=3",original.replace("page=2","page=1"),original.replace("page=2","page=3"),original.replace("/opds/search","/opds/../opds/search"),original.replace("/opds/search","/opds/%2e%2e/opds/search"),original.replace("/opds/search","/opds/%252e%252e/opds/search")))
            assertFailsWith<IllegalArgumentException>{GutenbergUrls.nextToken(value,"books",1)}
    }
    @Test fun malformedJsonInvalidUtf8WrongTypesAndXmlRejected() {
        for(value in listOf("<feed/>","{", "[]",previewPublication().replace("\"title\":\"A Book 🦦\"","\"title\":5"),previewPublication().replace("12345","-1"),previewPublication().replace("\"language\":[\"en\",\"es\"]","\"language\":{}")))
            assertFailsWith<InvalidOpdsException>{book(value)}
        assertFailsWith<InvalidOpdsException>{GutenbergOpds2Parser.root(byteArrayOf(0xc0.toByte(),0xaf.toByte()))}
    }
    @Test fun duplicateAndEscapedAliasKeysRejectedBeforeMapping() {
        for(value in listOf(previewPublication().replace("\"title\":","\"title\":\"Fake\",\"title\":"),previewPublication().replace("\"title\":","\"t\\u0069tle\":\"Fake\",\"title\":")))
            assertFailsWith<InvalidOpdsException>{book(value)}
    }
    @Test fun deepLargeStringNodeArrayAndPayloadLimits() {
        for(value in listOf("[".repeat(33)+"0"+"]".repeat(33),previewPublication(title="x".repeat(16385)),previewRoot.replace("[]","["+List(257){"0"}.joinToString()+"]"),"x".repeat(MAX_FEED_BYTES+1),"{\"nested\":["+List(101){"["+List(200){"0"}.joinToString()+"]"}.joinToString()+"]}"))
            assertFailsWith<InvalidOpdsException>{GutenbergOpds2Parser.root(previewBytes(value))}
    }
    @Test fun searchCountDuplicatePublicationAndPageBounds() {
        for(value in listOf(previewPage(publications=List(26){previewPublication()}.joinToString()),previewPage(publications=previewPublication()+","+previewPublication()),previewPage(page=2),previewPage().replace("\"itemsPerPage\":25","\"itemsPerPage\":100")))
            assertFailsWith<InvalidOpdsException>{GutenbergOpds2Parser.search(previewBytes(value),"books",1)}
    }
    @Test fun cancellationDuringPreflightAndMappingPropagates() {
        assertFailsWith<CancellationException>{GutenbergOpds2Parser.root(previewBytes(previewRoot)){throw CancellationException()}}
        var checks=0
        assertFailsWith<CancellationException>{GutenbergOpds2Parser.search(previewBytes(previewPage()),"books",1){if(++checks>=3)throw CancellationException()}}
    }
}
