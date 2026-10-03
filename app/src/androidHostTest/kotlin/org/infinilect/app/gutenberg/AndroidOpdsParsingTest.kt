// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import kotlin.test.*
import org.infinilect.core.PublicationFormat

/** Real XML tokenization on host using kXML, not a claim that an Android OS was run. */
class AndroidOpdsParsingTest {
    private class HostPull : KXmlParser() {
        override fun setFeature(feature: String, value: Boolean) {
            // Upstream kXML never processes DTDs and rejects this optional feature;
            // Android's fork supports setting it. Adapt only that configuration
            // difference, requiring the host parser's verified disabled default.
            if (feature == XmlPullParser.FEATURE_PROCESS_DOCDECL) {
                check(!value && !getFeature(feature))
            } else super.setFeature(feature, value)
        }
    }
    private val parser = GutenbergOpdsParser { AndroidOpdsXmlReader(it, HostPull()) }
    private val pageUrl = GutenbergUrls.search("books")
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/opds/$name.xml")).use { it.readBytes() }
    private fun parse(xml: String) = parser.parse(xml.encodeToByteArray(),pageUrl,"books")

    @Test fun mapsRealXmlFixtureWithMultipleAuthorsLanguagesAndResources() {
        val books = parser.parse(fixture("metadata"),pageUrl,"books").publications
        val book = books.first()
        assertEquals("Two Authors & Two Languages",book.title)
        assertEquals(listOf("First Author","Second Author"),book.authors)
        assertEquals(listOf("en","es"),book.languages)
        assertEquals("Source-supplied statement; check local law.",book.rights)
        assertEquals(listOf(PublicationFormat.TEXT,PublicationFormat.EPUB,PublicationFormat.PDF,PublicationFormat.HTML),book.resources.map { it.format })
        assertTrue(book.resources.all { it.publicationId == book.id && it.revision == null })
        assertTrue(books.last().resources.isEmpty())
    }
    @Test fun navigationFixtureUsesSourceOwnedOpaquePagination() {
        val page = parser.parse(fixture("search"),pageUrl,"books")
        assertEquals("1342",page.publications.single().id.localId)
        val token = assertNotNull(page.nextPageToken)
        assertFalse(token.startsWith("https:"))
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=books&start_index=26",GutenbergUrls.pageUrl(token,"books"))
    }
    @Test fun emptyFeedAndMissingOptionalMetadataAreSafe() {
        assertTrue(parser.parse(fixture("empty"),pageUrl,"books").publications.isEmpty())
        val book = parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>https://www.gutenberg.org/ebooks/11</id><title>Title</title></entry></feed>""").publications.single()
        assertTrue(book.authors.isEmpty()); assertTrue(book.languages.isEmpty()); assertNull(book.rights)
    }
    @Test fun rejectsExternalAndInternalDocumentDeclarationsAndEntities() {
        for (declaration in listOf("""<!DOCTYPE feed SYSTEM "https://attacker.invalid/external.dtd">""",
            """<!DOCTYPE feed [<!ENTITY external SYSTEM "file:///untrusted">]>""",
            """<!DOCTYPE feed [<!ENTITY a "expansion">]>""")) {
            assertFailsWith<InvalidOpdsException> { parse(declaration+"""<feed xmlns="http://www.w3.org/2005/Atom"/>""") }
        }
        assertFailsWith<InvalidOpdsException> { parse("""<feed xmlns="http://www.w3.org/2005/Atom">&external;</feed>""") }
    }
    @Test fun verifiesStandardEscapesNumericUtf8AndCdata() {
        val book = parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>https://www.gutenberg.org/ebooks/11</id><title>A &amp; B &#xE9; 🦦<![CDATA[ end]]></title></entry></feed>""").publications.single()
        assertEquals("A & B é 🦦 end",book.title)
    }
    @Test fun malformedXmlAndWrongRootAreRejected() {
        for (xml in listOf("<feed>","<feed/>","""<wrong xmlns="http://www.w3.org/2005/Atom"/>"""))
            assertFailsWith<InvalidOpdsException> { parse(xml) }
    }
    @Test fun namespacedRelCannotMasqueradeAsUnqualifiedAcquisition() {
        val book = parse("""<feed xmlns="http://www.w3.org/2005/Atom" xmlns:x="urn:untrusted"><entry><id>https://www.gutenberg.org/ebooks/11</id><title>Title</title><link x:rel="http://opds-spec.org/acquisition" type="text/plain" href="https://www.gutenberg.org/files/11/11.txt"/></entry></feed>""").publications.single()
        assertTrue(book.resources.isEmpty())
    }
}
