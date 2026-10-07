// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

internal enum class PageReadingMode { PAGED_RTL, PAGED_LTR, VERTICAL, WEBTOON }
internal data class PageReaderSettings(val mode: PageReadingMode = PageReadingMode.PAGED_LTR)
internal data class PageReaderPreferences(val settings: PageReaderSettings = PageReaderSettings(), val updatedAtEpochMillis: Long = 0) {
    init { require(updatedAtEpochMillis >= 0) }
}
/** Global page-reader user preferences, independent of EPUB, progress and cache. */
internal interface PageReaderSettingsStore {
    suspend fun load(): PageReaderPreferences
    suspend fun save(preferences: PageReaderPreferences): Boolean
}
