// SPDX-License-Identifier: GPL-3.0-only
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

/** Stable namespace owned by a source; never a display name. */
data class SourceId(val value: String) {
    init { require(value.isNotBlank()) { "Source ID must not be blank" } }
}

/** Identity is the pair, not a concatenated string: local IDs can collide across sources. */
data class PublicationId(val sourceId: SourceId, val localId: String) {
    init { require(localId.isNotBlank()) { "Publication local ID must not be blank" } }
}

enum class PublicationType { BOOK, COMIC, MAGAZINE, ARTICLE, DOCUMENT }
enum class PublicationFormat { EPUB, PDF, PAGES, HTML, TEXT }

/** Opaque source-owned reference. Readers must resolve it through ResourceLoader. */
data class PublicationResource(
    val publicationId: PublicationId,
    val key: String,
    val format: PublicationFormat,
    val mediaType: String,
) {
    init {
        require(key.isNotBlank()) { "Resource key must not be blank" }
        require(mediaType.isNotBlank()) { "Media type must not be blank" }
    }
}

/** Semantic type is independent of the available representations. */
data class Publication(
    val id: PublicationId,
    val title: String,
    val type: PublicationType,
    val authors: List<String> = emptyList(),
    val resources: List<PublicationResource> = emptyList(),
) {
    init {
        require(title.isNotBlank()) { "Title must not be blank" }
        require(resources.all { it.publicationId == id }) { "Resources must belong to this publication" }
        require(resources.map { it.key }.distinct().size == resources.size) { "Resource keys must be unique" }
    }
}
