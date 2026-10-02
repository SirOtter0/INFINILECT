// SPDX-License-Identifier: GPL-3.0-only
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

/** Opaque pagination token interpreted only by its source. */
data class SearchPage(val publications: List<Publication>, val nextPageToken: String? = null)

/** Obtains publications; does not render them or expose a networking client. */
interface PublicationSource {
    val id: SourceId

    /** Results and their resources must use [id] as their source namespace. */
    suspend fun search(query: String, pageToken: String? = null): SearchPage

    /** Returns null when absent; rejects an ID belonging to another source. */
    suspend fun getPublication(publicationId: PublicationId): Publication?

    /** Source fallback for ResourceLoader. Rejects a resource from another source. */
    suspend fun loadResource(resource: PublicationResource): ByteArray
}

/** Reader-facing boundary; implementations own caching and source routing. */
interface ResourceLoader {
    suspend fun load(resource: PublicationResource): ByteArray
}
