// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import java.text.NumberFormat
import java.util.Locale

internal actual fun formatInterfaceNumber(value: Double, language: String, fractionalDigits: Int): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag(language)).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = fractionalDigits
        isGroupingUsed = false // Short counters/settings remain compact and unambiguous.
    }.format(value)
