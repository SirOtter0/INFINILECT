// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ResourceCacheKeyTest {
    private val resource = PublicationResource(
        PublicationId(SourceId("gutenberg"), "1342"), "text", PublicationFormat.TEXT, "text/plain", revision = "v1",
    )

    @Test fun unknownRevisionCannotProduceAnUnconditionallyReusableKey() {
        assertNull(resource.copy(revision = null).cacheKey)
        assertFailsWith<IllegalArgumentException> { resource.copy(revision = " ") }
    }

    @Test fun everyContentIdentityComponentSeparatesCachedBytes() {
        val key = resource.cacheKey
        assertEquals(key, resource.copy().cacheKey)
        val alternatives = listOf(
            resource.copy(publicationId = resource.publicationId.copy(sourceId = SourceId("other"))),
            resource.copy(publicationId = resource.publicationId.copy(localId = "1343")),
            resource.copy(key = "alternative"),
            resource.copy(format = PublicationFormat.HTML),
            resource.copy(mediaType = "text/plain; charset=ISO-8859-1"),
            resource.copy(revision = "v2"),
        )
        alternatives.forEach { assertNotEquals(key, it.cacheKey) }
    }

    @Test fun separatorCharactersDoNotCollapseStructuredIdentity() {
        val first = resource.copy(publicationId = PublicationId(SourceId("a:b"), "c"), key = "d:e", revision = "f")
        val second = resource.copy(publicationId = PublicationId(SourceId("a"), "b:c"), key = "d", revision = "e:f")
        assertNotEquals(first.cacheKey, second.cacheKey)
    }
}
