// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.runtime.*
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.*

internal val interfaceLanguages = listOf("en", "es", "ca")
internal fun resolveInterfaceLanguage(preference: String?, system: String): String = preference
    ?: system.substringBefore('-').lowercase().takeIf { it in interfaceLanguages } ?: "en"
internal expect fun deviceInterfaceLanguage(): String
internal expect fun applyInterfaceLanguage(language: String)
internal val LocalInterfaceLanguage = staticCompositionLocalOf { "en" }

/** Public locale APIs + a composition key for resource lookup only, never the reader session. */
@Composable
internal fun InterfaceLanguage(preference: String?, content: @Composable () -> Unit) {
    val desired = resolveInterfaceLanguage(preference, deviceInterfaceLanguage())
    var applied by remember { mutableStateOf(resolveInterfaceLanguage(null, deviceInterfaceLanguage())) }
    var strings by remember { mutableStateOf(LocalizedStrings()) }
    LaunchedEffect(desired) {
        applyInterfaceLanguage(desired)
        strings = try { withContext(Dispatchers.IO) { LocalizedStrings(UiText.entries.associateWith { getString(it.resource) }) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { LocalizedStrings(failed = true) }
        applied = desired
    }
    CompositionLocalProvider(LocalInterfaceLanguage provides applied, LocalAppStrings provides strings, content = content)
}
