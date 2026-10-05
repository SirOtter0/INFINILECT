// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

/** Small global user preference record. Ordering prevents a draining old owner replacing
 * a newer choice after Activity recreation. Contains no publication/progress/content. */
internal data class EpubReaderPreferences(
    val settings: EpubReaderSettings = EpubReaderSettings(),
    val updatedAtEpochMillis: Long = 0,
) {
    init { require(updatedAtEpochMillis >= 0) }
}

/** Replaceable durable storage. Missing/invalid/unsupported state loads defaults.
 * Save returns true only after commit, or when a newer committed record already wins.
 * Implementations keep the last committed record on failure; never report RAM as durable.
 */
internal interface EpubReaderSettingsStore {
    suspend fun load(): EpubReaderPreferences
    suspend fun save(preferences: EpubReaderPreferences): Boolean
}
