// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

internal actual fun residenceCountries(): List<ResidenceCountry> = java.util.Locale.getISOCountries().map { code ->
    ResidenceCountry(code, java.util.Locale.Builder().setRegion(code).build().getDisplayCountry(java.util.Locale.getDefault()))
}.sortedBy { it.label }
