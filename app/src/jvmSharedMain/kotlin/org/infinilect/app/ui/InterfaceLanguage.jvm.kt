// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import java.util.Locale

// Capture the device preference before applying the application's explicit locale.
private val deviceLanguage = Locale.getDefault().language
internal actual fun deviceInterfaceLanguage() = deviceLanguage
internal actual fun applyInterfaceLanguage(language: String) {
    require(language in interfaceLanguages)
    val requested = Locale.forLanguageTag(language)
    if (Locale.getDefault().language != language) Locale.setDefault(requested)
}

internal actual fun reportInterfaceLanguageFailure(language: String, exception: Exception) {
    // No exception message, resource path, profile or publication text in diagnostic output.
    java.util.logging.Logger.getLogger("INFINILECT.Locale").warning(
        "stage=load-interface-resources language=$language exception=${exception.javaClass.simpleName}")
}
