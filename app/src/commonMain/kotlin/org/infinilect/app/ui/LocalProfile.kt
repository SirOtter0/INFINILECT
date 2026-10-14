// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

internal data class ResidenceCountry(val code: String, val label: String)
internal expect fun residenceCountries(): List<ResidenceCountry>
internal fun validResidenceCode(code: String) = code.length == 2 && code.all { it in 'A'..'Z' } && residenceCountries().any { it.code == code }

/** User-declared local preference, never legal eligibility or acquisition authorization. */
internal data class LocalProfile(val displayName: String? = null, val countryCode: String? = null,
    val interfaceLanguage: String? = null, val setupHandled: Boolean = false) {
    init {
        require(displayName == null || displayName.isNotBlank() && displayName == displayName.trim() && displayName.length <= 80 &&
            displayName.none { it.code < 32 || it.code in 127..159 || it == '\u2028' || it == '\u2029' })
        require(countryCode == null || validResidenceCode(countryCode))
        require(interfaceLanguage == null || interfaceLanguage in setOf("en", "es", "ca"))
    }
    val hasDeclaredResidence get() = countryCode != null
}
internal data class ApplicationPreferences(val mode: ApplicationThemeMode = ApplicationThemeMode.SYSTEM, val profile: LocalProfile = LocalProfile(), val discovery: org.infinilect.app.discovery.DiscoveryPreferences = org.infinilect.app.discovery.DiscoveryPreferences(), val readingLanguage: String? = null) {
    init { require(readingLanguage == null || readingLanguage in setOf("en", "es", "ca")) }
}
internal fun welcomeGreeting(profile: LocalProfile) = profile.displayName?.let { "Hello, $it!" } ?: "Hello!"
