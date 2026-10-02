// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.infinilect.core.PublicationFormat
import org.infinilect.core.PublicationId
import org.infinilect.core.PublicationType
import org.infinilect.core.SourceId

internal fun fixture(name: String): ByteArray =
    checkNotNull(GutenbergOpdsParserTest::class.java.getResourceAsStream("/opds/$name.xml")).use { it.readBytes() }

class GutenbergOpdsParserTest {
    private val parser = GutenbergOpdsParser()
    private val pageUrl = GutenbergUrls.search("books")
    private fun parse(xml: String) = parser.parse(xml.toByteArray(), pageUrl, "books")

    @Test fun mapsObservedNavigationFeedWithoutInventingMetadata() {
        val page = parser.parse(fixture("search"), pageUrl, "books")
        val book = page.publications.single()
        assertEquals(PublicationId(SourceId("gutenberg"), "1342"), book.id)
        assertEquals("Pride and Prejudice", book.title)
        assertEquals(PublicationType.BOOK, book.type)
        assertEquals(listOf("Jane Austen"), book.authors)
        assertTrue(book.languages.isEmpty()) // Feed xml:lang describes metadata, not the book.
        assertTrue(book.resources.isEmpty()) // Subsection and thumbnail are not acquisitions.
        assertNull(book.rights)
        assertEquals("https://www.gutenberg.org/ebooks/1342", book.sourceUrl)
        val next = assertNotNull(page.nextPageToken)
        assertTrue(!next.startsWith("https://"))
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=books&start_index=26", GutenbergUrls.pageUrl(next, "books"))
    }

    @Test fun preservesMultipleAuthorsLanguagesRightsAndSupportedAcquisitions() {
        val book = parser.parse(fixture("metadata"), pageUrl, "books").publications.first()
        assertEquals(listOf("First Author", "Second Author"), book.authors)
        assertEquals(listOf("en", "es"), book.languages)
        assertEquals("Source-supplied statement; check local law.", book.rights)
        assertEquals("Two Authors & Two Languages", book.title)
        assertEquals(listOf(PublicationFormat.TEXT, PublicationFormat.EPUB, PublicationFormat.PDF, PublicationFormat.HTML), book.resources.map { it.format })
        assertEquals("text/plain; charset=utf-8", book.resources.first().mediaType)
        assertTrue(book.resources.all { it.publicationId == book.id && it.revision == null && it.cacheKey == null })
        assertTrue(book.resources.all { it.key.startsWith("https://www.gutenberg.org/") })
    }

    @Test fun missingOptionalFieldsRemainAbsent() {
        val book = parser.parse(fixture("metadata"), pageUrl, "books").publications.last()
        assertTrue(book.authors.isEmpty())
        assertTrue(book.languages.isEmpty())
        assertTrue(book.resources.isEmpty())
        assertNull(book.rights)
    }

    @Test fun resourceUrlEscapesAreNotDecodedOrReinterpreted() {
        val page = parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry>
          <id>https://www.gutenberg.org/ebooks/11.opds</id><title>A Book</title>
          <link rel="http://opds-spec.org/acquisition" type="text/plain"
                href="http://www.gutenberg.org/files/11/a%2Fb%25c.txt?label=a%26b"/>
        </entry></feed>""")
        assertEquals("https://www.gutenberg.org/files/11/a%2Fb%25c.txt?label=a%26b", page.publications.single().resources.single().key)
    }

    @Test fun emptyFeedIsValidAndHasNoNextPage() {
        val page = parser.parse(fixture("empty"), pageUrl, "books")
        assertTrue(page.publications.isEmpty())
        assertNull(page.nextPageToken)
    }

    @Test fun malformedXmlWrongNamespaceAndIncompleteBooksFail() {
        for (xml in listOf("<feed>", "<html/>", "<feed xmlns='urn:other'/>",
            "<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>https://www.gutenberg.org/ebooks/11.opds</id></entry></feed>")) {
            assertFailsWith<InvalidOpdsException> { parse(xml) }
        }
    }

    @Test fun rejectsDtdExternalEntitiesAndEntityExpansion() {
        for (declaration in listOf(
            "<!DOCTYPE feed SYSTEM 'file:///definitely-not-a-publication'>",
            "<!DOCTYPE feed [<!ENTITY stolen SYSTEM 'https://example.org/secret'>]>",
            "<!DOCTYPE feed [<!ENTITY a 'aaaaaaaa'><!ENTITY b '&a;&a;&a;&a;'>]>")) {
            assertFailsWith<InvalidOpdsException> { parse("$declaration<feed xmlns='http://www.w3.org/2005/Atom'/>") }
        }
    }

    @Test fun rejectsXmlBaseOversizedDeepAndOverpopulatedFeeds() {
        assertFailsWith<InvalidOpdsException> { parse("<feed xmlns='http://www.w3.org/2005/Atom' xml:base='https://evil.example/'/>") }
        assertFailsWith<InvalidOpdsException> { parser.parse(ByteArray(MAX_FEED_BYTES + 1), pageUrl, "books") }
        assertFailsWith<InvalidOpdsException> { parse("<feed xmlns='http://www.w3.org/2005/Atom'>" + "<x>".repeat(33) + "</x>".repeat(33) + "</feed>") }
        val entries = (1..101).joinToString("") { "<entry><id>https://www.gutenberg.org/ebooks/$it.opds</id><title>Book $it</title></entry>" }
        assertFailsWith<InvalidOpdsException> { parse("<feed xmlns='http://www.w3.org/2005/Atom'>$entries</feed>") }
        val namespaces = (1..17).joinToString(" ") { "xmlns:n$it='urn:example:$it'" }
        assertFailsWith<InvalidOpdsException> { parse("<feed xmlns='http://www.w3.org/2005/Atom' $namespaces/>") }
    }

    @Test fun namespaceLookalikesCannotSupplyAuthorsOrResources() {
        val page = parse("""<feed xmlns="http://www.w3.org/2005/Atom" xmlns:x="urn:fake"><entry>
          <id>https://www.gutenberg.org/ebooks/11.opds</id><title>A Book</title>
          <x:author><x:name>Forged Author</x:name></x:author>
          <x:link rel="http://opds-spec.org/acquisition" href="/book.txt" type="text/plain"/>
        </entry></feed>""")
        assertTrue(page.publications.single().authors.isEmpty())
        assertTrue(page.publications.single().resources.isEmpty())
    }

    @Test fun nextLinksCannotLeaveSearchChangeQueryOrLoop() {
        for (href in listOf("https://evil.example/ebooks/search.opds/?query=books&amp;start_index=26",
            "/ebooks/11.opds?query=books&amp;start_index=26", "/ebooks/search.opds/?query=other&amp;start_index=26",
            "/ebooks/search.opds/?query=books&amp;start_index=1")) {
            assertFailsWith<InvalidOpdsException> { parse("""<feed xmlns="http://www.w3.org/2005/Atom"><link rel="next" type="application/atom+xml" href="$href"/></feed>""") }
        }
    }

    @Test fun cancellationSignalIsPreserved() {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            parser.parse(fixture("empty"), pageUrl, "books") { throw kotlinx.coroutines.CancellationException("cancelled") }
        }
    }
}
