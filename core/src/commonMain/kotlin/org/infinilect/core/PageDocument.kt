// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

/** Untrusted source geometry, not a decoder allocation instruction. Reader policy is stricter. */
data class PageDimensions(val width: Int, val height: Int) {
    init { require(width in 1..16_384 && height in 1..16_384) }
}

/** One declared image reference. Ordering belongs to PageDocument, not lexical key sorting. */
data class PageEntry(val resource: PublicationResource) {
    init { require(resource.format == PublicationFormat.PAGES && resource.pageDimensions != null) }
    val key: String get() = resource.key
    val dimensions: PageDimensions get() = requireNotNull(resource.pageDimensions)
}

/** Reader-facing ordered pages; acquisition/archives/storage are adapter responsibilities.
 * A future CBZ adapter can expose the same contract; a PDF reader remains separate.
 * Caller owns close, including opening failure. Handles are fresh, sequential, single-consumer.
 */
interface PageDocument {
    val publicationId: PublicationId
    val title: String
    val progressId: ReadingProgressId
    val pages: List<PageEntry>
    suspend fun openPage(page: PageEntry): ResourceContent
    fun close()
}
