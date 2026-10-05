// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

/** Global EPUB presentation preferences, never a locator or publisher style. No disk/UI types. */
internal enum class EpubReadingTheme { SYSTEM, LIGHT, DARK }
internal data class EpubReaderSettings(
    val fontSize: Int = 18,
    val lineSpacingPercent: Int = 150,
    val margin: Int = 16,
    val theme: EpubReadingTheme = EpubReadingTheme.SYSTEM,
) {
    init { require(fontSize in 14..30 && lineSpacingPercent in 120..200 && margin in 8..40) }
}
