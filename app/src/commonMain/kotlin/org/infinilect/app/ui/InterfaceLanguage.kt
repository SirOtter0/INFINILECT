// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.runtime.*
import org.jetbrains.compose.resources.*
import kotlinx.coroutines.*

internal val interfaceLanguages = listOf("en", "es", "ca")
internal fun resolveInterfaceLanguage(preference: String?, system: String): String = preference
    ?: system.substringBefore('-').lowercase().takeIf { it in interfaceLanguages } ?: "en"
internal expect fun deviceInterfaceLanguage(): String
internal expect fun applyInterfaceLanguage(language: String)
internal expect fun reportInterfaceLanguageFailure(language: String, exception: Exception)
internal val LocalInterfaceLanguage = staticCompositionLocalOf { "en" }
internal val LocalRetryInterfaceLanguage = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalInterfaceLanguageLoading = staticCompositionLocalOf { false }

internal suspend fun loadInterfaceStrings(environment: ResourceEnvironment): LocalizedStrings = withContext(Dispatchers.IO) {
    // Snapshot the public resource environment once: a concurrent locale change cannot mix a table.
    LocalizedStrings(UiText.entries.associateWith { getString(environment, it.resource) })
}

/** A resource table is applied atomically; a genuine load failure retains the last good language. */
@Composable
internal fun InterfaceLanguage(preference: String?, loader: suspend (ResourceEnvironment) -> LocalizedStrings = ::loadInterfaceStrings, content: @Composable () -> Unit) {
    val desired = resolveInterfaceLanguage(preference, deviceInterfaceLanguage())
    var applied by remember { mutableStateOf("en") }
    var strings by remember { mutableStateOf(LocalizedStrings()) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var revision by remember { mutableLongStateOf(0L) }
    LaunchedEffect(desired, retry) {
        val request = ++revision
        loading = true
        try {
            applyInterfaceLanguage(desired)
            val environment = getSystemResourceEnvironment()
            val loaded = loader(environment)
            ensureActive()
            strings = loaded
            applied = desired
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            ensureActive()
            reportInterfaceLanguageFailure(desired, e)
            applyInterfaceLanguage(applied)
            strings = strings.withLoadFailure()
        } finally { if (request == revision) loading = false }
    }
    CompositionLocalProvider(LocalInterfaceLanguage provides applied, LocalAppStrings provides strings,
        LocalInterfaceLanguageLoading provides loading, LocalRetryInterfaceLanguage provides { retry++ }, content = content)
}
