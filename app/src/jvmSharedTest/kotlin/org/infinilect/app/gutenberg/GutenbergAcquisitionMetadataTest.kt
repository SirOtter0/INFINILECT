// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

/** Delivery extensions are bounded JSON, never catalog identity or byte authority. */
class GutenbergAcquisitionMetadataTest {
    private val id=PublicationId(GUTENBERG_ID,"84")
    private fun length(value: String?) = previewPublication().replace(",\"length\":12345",value?.let { ",\"length\":$it" } ?: "")
    private fun inert(value: String) {
        val publication=GutenbergOpds2Parser.detail(previewBytes(value),id)
        assertEquals(id,publication.id)
        assertEquals("https://www.gutenberg.org/ebooks/84",publication.sourceUrl)
        assertEquals(listOf("Author, One","Autor 二"),publication.authors)
        assertTrue(publication.resources.isEmpty())
    }

    @Test fun realHtmlLinkWithoutLengthDoesNotInvalidateSearch() {
        val page=GutenbergOpds2Parser.search(previewBytes(previewPage(publications=previewPublication()+","+previewHtmlWithoutLength)),"books",1)
        assertEquals(2,page.publications.size)
        val html=page.publications.last()
        assertEquals(PublicationId(GUTENBERG_ID,"10414"),html.id)
        assertEquals("https://www.gutenberg.org/ebooks/10414",html.sourceUrl)
        assertEquals("I Love You, California\r\nMarch Song",html.title)
        assertEquals(listOf("en"),html.languages)
        assertTrue(page.publications.all { it.resources.isEmpty() })
    }
    @Test fun observedPositiveIntegerLengthIsNotResourceAuthority() = inert(length("474733"))
    @Test fun absentLengthOnEpubIsNotRequired() = inert(length(null))
    @Test fun nullLengthIsInert() = inert(length("null"))
    @Test fun negativeLengthIsInert() = inert(length("-1"))
    @Test fun zeroLengthIsInert() = inert(length("0"))
    @Test fun fractionalLengthIsInert() = inert(length("1.5"))
    @Test fun overflowAndHugeExponentLengthAreInert() {
        inert(length("9223372036854775808"));inert(length("1e999"))
    }
    @Test fun stringLengthIsNeverCoerced() = inert(length("\"474733\""))
    @Test fun booleanLengthIsInert() = inert(length("true"))
    @Test fun objectLengthIsInert() = inert(length("{\"untrusted\":1}"))
    @Test fun arrayLengthIsInert() = inert(length("[1,2]"))
    @Test fun normativeOptionalSizeIsNotResourceAuthority() {
        inert(length(null).replace("\"type\":\"application/epub+zip\"","\"type\":\"application/epub+zip\",\"size\":474733"))
    }
    @Test fun unsupportedSizeIsNotPromotedToByteMetadata() {
        for(value in listOf("null","0","-1","\"474733\"","1.5"))
            inert(length(null).replace("\"type\":\"application/epub+zip\"","\"type\":\"application/epub+zip\",\"size\":$value"))
    }
    @Test fun changedDeliveryLocationDoesNotBreakCatalog() {
        inert(length(null).replace("https://www.gutenberg.org/cache/epub/84/pg84-images-3.epub","https://future-delivery.example/book.epub"))
    }
    @Test fun hostileDeliverySchemesRemainInert() {
        for(url in listOf("javascript:untrusted()","file:///untrusted","ftp://evil.example/book","https://user@evil.example:444/book?x=1#x"))
            inert(length("null").replace("https://www.gutenberg.org/cache/epub/84/pg84-images-3.epub",url))
    }
    @Test fun malformedDeliveryStructureStillFailsClosed() {
        for(value in listOf(previewPublication().replace("\"href\":\"https://www.gutenberg.org/cache/epub/84/pg84-images-3.epub\"","\"href\":null"),
            previewPublication().replace("\"type\":\"application/epub+zip\"","\"type\":[]")))
            assertFailsWith<InvalidOpdsException> { GutenbergOpds2Parser.detail(previewBytes(value),id) }
    }
    @Test fun optionalDeliveryExtensionsStillObeyGlobalJsonBounds() {
        for(value in listOf(length("[".repeat(33)+"0"+"]".repeat(33)),length("\""+"x".repeat(16385)+"\""),
            length("["+List(257) {"0"}.joinToString()+"]")))
            assertFailsWith<InvalidOpdsException> { GutenbergOpds2Parser.detail(previewBytes(value),id) }
    }
    @Test fun inertDeliveryMetadataCannotRescueUnsafeIdentityOrSelf() {
        for(value in listOf(length("null").replace("ebooks/84","ebooks/01"),
            length("null").replace("publications?id=84","publications?id=11")))
            assertFailsWith<InvalidOpdsException> { GutenbergOpds2Parser.detail(previewBytes(value),id) }
    }
    @Test fun parsedOptionalDeliveryMetadataNeverEnablesAcquisition()=runTest {
        var requests=0
        GutenbergSource(MockEngine { request ->
            requests++
            respond(if(request.url.toString()==GUTENBERG_ROOT) previewRoot else previewPage(publications=length("null")+","+previewHtmlWithoutLength),
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }).use { source ->
            val page=source.search("books")
            assertEquals(2,requests);assertTrue(page.publications.all { it.resources.isEmpty() })
            for(publication in page.publications) for(format in listOf(PublicationFormat.EPUB,PublicationFormat.TEXT)) {
                val forged=PublicationResource(publication.id,"forged",format,"application/octet-stream")
                assertEquals(GUTENBERG_UNSUPPORTED,assertFailsWith<SearchException> { source.loadResource(forged) }.message)
            }
            assertEquals(2,requests) // Exactly root+search; loadResource made zero requests.
        }
    }
}
