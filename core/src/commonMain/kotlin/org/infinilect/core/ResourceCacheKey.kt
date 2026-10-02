// SPDX-License-Identifier: GPL-3.0-only
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

/**
 * Identity for bytes whose revision is known. Persist as structured fields, never
 * delimiter-concatenated strings. Source revisions must identify actual byte changes.
 * This key provides identity, not a freshness validator or a cache implementation.
 */
data class ResourceCacheKey(
    val publicationId: PublicationId,
    val resourceKey: String,
    val format: PublicationFormat,
    val mediaType: String,
    val revision: String,
) {
    init {
        require(resourceKey.isNotBlank()) { "Resource key must not be blank" }
        require(mediaType.isNotBlank()) { "Media type must not be blank" }
        require(revision.isNotBlank()) { "Revision must not be blank" }
    }
}
