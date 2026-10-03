// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import java.nio.file.Path
import java.nio.file.Paths

/** Persistent user data, never the cache, cwd, repository or a relative env path. */
internal fun desktopProgressDirectory(
    os: String = System.getProperty("os.name", ""),
    home: String? = System.getProperty("user.home"),
    environment: Map<String, String> = System.getenv(),
): Path? = try {
    fun absolute(value: String?): Path? = value?.takeIf { it.isNotBlank() }?.let(Paths::get)?.takeIf { it.isAbsolute }
    val user = absolute(home)
    val base = when {
        os.startsWith("Windows", ignoreCase = true) -> absolute(environment["LOCALAPPDATA"]) ?: user?.resolve("AppData/Local")
        os.startsWith("Mac", ignoreCase = true) -> user?.resolve("Library/Application Support")
        else -> absolute(environment["XDG_DATA_HOME"]) ?: user?.resolve(".local/share")
    }
    base?.resolve("org.infinilect.app")?.resolve(PROGRESS_DIRECTORY_NAME)
} catch (_: Exception) { null }
