// SPDX-License-Identifier: GPL-3.0-or-later
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

    /**
     * Opens a fresh sequential resource handle for ResourceLoader; caller owns closing it.
     * Rejects a resource from another source. Cancellation while opening must release
     * any acquired handles; cancellation during a read must propagate to the caller.
     * If a revision is requested, bytes must match it; mismatch requires refreshing
     * the reference or failing, never returning new bytes under an old cache key.
     */
    suspend fun loadResource(resource: PublicationResource): ResourceContent
}

/** Reader-facing boundary; implementations own caching and source routing. */
interface ResourceLoader {
    /** Opens a fresh handle. The caller must close it, including on failure/cancellation. */
    suspend fun load(resource: PublicationResource): ResourceContent
}
