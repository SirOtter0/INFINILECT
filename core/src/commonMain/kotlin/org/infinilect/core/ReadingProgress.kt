// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

/** User-state identity, independent of title, URL, MIME and disposable byte revisions. */
data class ReadingProgressId(
    val publicationId: PublicationId,
    val resourceKey: String,
    val format: PublicationFormat,
) {
    init { require(resourceKey.isNotBlank()) }
}

/** Logical reader locations. Add format-specific typed variants when their readers exist. */
sealed interface ReadingLocator {
    /** Unicode code points from the beginning of decoded text, after leading BOM removal.
     * documentCodePoints records the observed length, allowing approximate restoration
     * by progression if text changes. EOF is offset == length; empty text is (0, 0).
     */
    data class Text(val codePointOffset: Long, val documentCodePoints: Long) : ReadingLocator {
        init { require(documentCodePoints >= 0 && codePointOffset in 0..documentCodePoints) }
    }
}

/** Local user state only. No content, pixels, source policy or authoritative metadata. */
data class ReadingProgress(
    val id: ReadingProgressId,
    val locator: ReadingLocator,
    val progression: Double,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(progression.isFinite() && progression in 0.0..1.0)
        require(updatedAtEpochMillis >= 0)
        require(locator !is ReadingLocator.Text || id.format == PublicationFormat.TEXT)
    }
}

/** Small persistent user state, physically independent of resource caches.
 * Operations must be concurrency-safe and propagate cancellation. Unavailable/corrupt
 * storage returns null/false, never prevents reading. save must not replace newer
 * committed state with an older timestamp. No network operations or cache eviction.
 * Implementations needing resources must document their ownership separately.
 */
interface ReadingProgressStore {
    suspend fun get(id: ReadingProgressId): ReadingProgress?
    suspend fun save(progress: ReadingProgress): Boolean
    suspend fun remove(id: ReadingProgressId): Boolean
}
