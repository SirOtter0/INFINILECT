// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.cache

import java.nio.file.Path
import java.nio.file.Paths

/** Per-user application namespace; never cwd/repository or a relative environment path. */
internal fun desktopCacheDirectory(
    os: String = System.getProperty("os.name", ""),
    home: String? = System.getProperty("user.home"),
    environment: Map<String, String> = System.getenv(),
): Path? = try {
    fun absolute(value: String?): Path? = value?.takeIf { it.isNotBlank() }?.let(Paths::get)?.takeIf { it.isAbsolute }
    val user = absolute(home)
    val base = when {
        os.startsWith("Windows", ignoreCase = true) -> absolute(environment["LOCALAPPDATA"]) ?: user?.resolve("AppData/Local")
        os.startsWith("Mac", ignoreCase = true) -> user?.resolve("Library/Caches")
        else -> absolute(environment["XDG_CACHE_HOME"]) ?: user?.resolve(".cache")
    }
    base?.resolve("org.infinilect.app")?.resolve(CACHE_DIRECTORY_NAME)
} catch (_: Exception) { null } // Missing/unusable platform storage disables caching only.
