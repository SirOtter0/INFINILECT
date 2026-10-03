// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import org.infinilect.app.network.createSources
import android.content.Context
import java.io.File
import java.nio.file.Path
import org.infinilect.app.cache.CACHE_DIRECTORY_NAME
import org.infinilect.app.cache.DiskResourceCache

/** Extract the application-private path immediately; neither cache nor sources retain Context. */
fun createApplicationSources(context: Context): ApplicationSources {
    val directory = try { androidCacheDirectory(context.applicationContext.cacheDir) } catch (_: Exception) { null }
    return createSources(DiskResourceCache(directory))
}

internal fun androidCacheDirectory(privateCacheDir: File): Path = privateCacheDir.toPath().resolve(CACHE_DIRECTORY_NAME)

internal fun createApplicationSources(privateCacheDir: File): ApplicationSources =
    createSources(DiskResourceCache(androidCacheDirectory(privateCacheDir)))
