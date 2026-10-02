// SPDX-License-Identifier: GPL-3.0-or-later
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
    /** Source-owned version that must change whenever the bytes change; null means unknown. */
    val revision: String? = null,
) {
    init {
        require(key.isNotBlank()) { "Resource key must not be blank" }
        require(mediaType.isNotBlank()) { "Media type must not be blank" }
        require(revision == null || revision.isNotBlank()) { "Revision must be absent or nonblank" }
    }

    /** Unknown revisions have no key for unconditional cache reuse. */
    val cacheKey: ResourceCacheKey?
        get() = revision?.let { ResourceCacheKey(publicationId, key, format, mediaType, it) }
}

/** Semantic type is independent of the available representations. */
data class Publication(
    val id: PublicationId,
    val title: String,
    val type: PublicationType,
    val authors: List<String> = emptyList(),
    val resources: List<PublicationResource> = emptyList(),
    /** Source-supplied language tags; empty means unknown, not language-neutral. */
    val languages: List<String> = emptyList(),
    /** Publication detail/canonical URL validated by the source adapter, not an acquisition URL. */
    val sourceUrl: String? = null,
    /** Verbatim source rights/license statement; absence implies no rights determination. */
    val rights: String? = null,
) {
    init {
        require(title.isNotBlank()) { "Title must not be blank" }
        require(languages.all { it.isNotBlank() }) { "Language tags must not be blank" }
        require(sourceUrl == null || sourceUrl.isNotBlank()) { "Source URL must be absent or nonblank" }
        require(rights == null || rights.isNotBlank()) { "Rights must be absent or nonblank" }
        require(resources.all { it.publicationId == id }) { "Resources must belong to this publication" }
        require(resources.map { it.key }.distinct().size == resources.size) { "Resource keys must be unique" }
    }
}
