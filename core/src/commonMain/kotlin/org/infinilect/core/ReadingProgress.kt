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
    /** Stable page resource key, ordered-page fallback and normalized intra-page fraction.
     * No UI list index/pixels. Key wins on reorder; removed keys fall back to pageIndex. */
    data class Page(val pageKey: String, val pageIndex: Int, val pageProgression: Double) : ReadingLocator {
        init {
            require(pageKey.isNotBlank() && pageKey.length <= 512)
            require(pageIndex in 0..99_999)
            require(pageProgression.isFinite() && pageProgression in 0.0..1.0)
        }
    }
    /** Canonical spine path + XHTML element-child ordinals from body + Unicode offset
     * within that semantic block. Layout-independent; chapterProgression is a fallback
     * when publication structure changes, never an authorization or byte revision. */
    data class Epub(
        val spinePath: EpubEntryPath,
        val elementPath: List<Int>,
        val codePointOffset: Long,
        val chapterProgression: Double,
    ) : ReadingLocator {
        init {
            require(elementPath.size in 1..32 && elementPath.all { it in 0..19_999 })
            require(codePointOffset >= 0)
            require(chapterProgression.isFinite() && chapterProgression in 0.0..1.0)
        }
    }

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
        require(locator !is ReadingLocator.Epub || id.format == PublicationFormat.EPUB)
        require(locator !is ReadingLocator.Text || id.format == PublicationFormat.TEXT)
        require(locator !is ReadingLocator.Page || id.format == PublicationFormat.PAGES)
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
