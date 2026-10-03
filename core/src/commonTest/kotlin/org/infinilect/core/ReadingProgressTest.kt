// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.test.*

class ReadingProgressTest {
    private val id = ReadingProgressId(PublicationId(SourceId("source"), "book"), "text", PublicationFormat.TEXT)
    @Test fun identityIsSourcePublicationResourceAndFormat() {
        val identities = listOf(id, id.copy(publicationId = id.publicationId.copy(sourceId = SourceId("other"))),
            id.copy(publicationId = id.publicationId.copy(localId = "other")), id.copy(resourceKey = "other"),
            id.copy(format = PublicationFormat.EPUB))
        assertEquals(5, identities.toSet().size)
        assertEquals(id, id.copy())
    }
    @Test fun titleAuthorsAndRevisionsDoNotChangeUserIdentity() {
        val resource = PublicationResource(id.publicationId, id.resourceKey, id.format, "text/plain")
        fun key(r: PublicationResource) = ReadingProgressId(r.publicationId, r.key, r.format)
        assertEquals(key(resource), key(resource.copy(revision = "new", mediaType = "text/plain;charset=utf-8")))
        assertNull(resource.cacheKey)
        val book = Publication(id.publicationId, "Old", PublicationType.BOOK, resources = listOf(resource))
        assertEquals(book.id, book.copy(title = "New", authors = listOf("New author")).id)
    }
    @Test fun beginningMiddleAndEofAreExplicit() {
        listOf(0L, 50L, 100L).forEach { assertEquals(it, ReadingLocator.Text(it, 100).codePointOffset) }
    }
    @Test fun emptyLocatorIsDefined() { assertEquals(0, ReadingLocator.Text(0, 0).documentCodePoints) }
    @Test fun negativeAndOutOfRangeLocatorsRejected() {
        listOf(-1L to 10L, 11L to 10L, 0L to -1L).forEach { (a,b) ->
            assertFailsWith<IllegalArgumentException> { ReadingLocator.Text(a,b) }
        }
    }
    @Test fun progressionMustBeFiniteAndNormalized() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1).forEach {
            assertFailsWith<IllegalArgumentException> { ReadingProgress(id, ReadingLocator.Text(0,1),it,1) }
        }
        assertEquals(1.0, ReadingProgress(id,ReadingLocator.Text(1,1),1.0,1).progression)
    }
    @Test fun typedLocatorMustMatchFormat() {
        assertFailsWith<IllegalArgumentException> {
            ReadingProgress(id.copy(format = PublicationFormat.PDF), ReadingLocator.Text(1,2),0.5,1)
        }
    }
    @Test fun timestampAndResourceKeyValidated() {
        assertFailsWith<IllegalArgumentException> { id.copy(resourceKey = " ") }
        assertFailsWith<IllegalArgumentException> { ReadingProgress(id,ReadingLocator.Text(0,1),0.0,-1) }
    }
}
