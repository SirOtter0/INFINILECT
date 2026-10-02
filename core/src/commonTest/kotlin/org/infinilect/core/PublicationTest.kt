// SPDX-License-Identifier: GPL-3.0-only
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PublicationTest {
    private val id = PublicationId(SourceId("gutenberg"), "1342")

    @Test fun localIdsAreNamespacedBySource() {
        assertNotEquals(id, PublicationId(SourceId("other"), "1342"))
        assertEquals(id, PublicationId(SourceId("gutenberg"), "1342"))
        // Avoid ambiguous separator-based identity encodings.
        assertNotEquals(PublicationId(SourceId("a:b"), "c"), PublicationId(SourceId("a"), "b:c"))
    }

    @Test fun semanticTypeDoesNotRestrictFormats() {
        val book = Publication(id, "Pride and Prejudice", PublicationType.BOOK, resources = listOf(
            PublicationResource(id, "epub", PublicationFormat.EPUB, "application/epub+zip"),
            PublicationResource(id, "text", PublicationFormat.TEXT, "text/plain"),
        ))
        assertEquals(PublicationType.BOOK, book.type)
        assertEquals(listOf(PublicationFormat.EPUB, PublicationFormat.TEXT), book.resources.map { it.format })
    }

    @Test fun publicationRejectsForeignAndDuplicateResources() {
        val foreign = PublicationResource(PublicationId(SourceId("other"), "1342"), "text", PublicationFormat.TEXT, "text/plain")
        assertFailsWith<IllegalArgumentException> { Publication(id, "Book", PublicationType.BOOK, resources = listOf(foreign)) }
        val resource = PublicationResource(id, "text", PublicationFormat.TEXT, "text/plain")
        assertFailsWith<IllegalArgumentException> { Publication(id, "Book", PublicationType.BOOK, resources = listOf(resource, resource)) }
    }

    @Test fun blankIdentityAndMetadataAreRejected() {
        assertFailsWith<IllegalArgumentException> { SourceId(" ") }
        assertFailsWith<IllegalArgumentException> { PublicationId(id.sourceId, "") }
        assertFailsWith<IllegalArgumentException> { Publication(id, "", PublicationType.BOOK) }
        assertFailsWith<IllegalArgumentException> { PublicationResource(id, "", PublicationFormat.TEXT, "text/plain") }
        assertFailsWith<IllegalArgumentException> { PublicationResource(id, "text", PublicationFormat.TEXT, "") }
    }
}
