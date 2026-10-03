// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import org.infinilect.app.network.createSources
import android.content.Context
import java.io.File
import java.nio.file.Path
import org.infinilect.app.cache.CACHE_DIRECTORY_NAME
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.progress.PROGRESS_DIRECTORY_NAME

/** Extract the application-private path immediately; neither cache nor sources retain Context. */
fun createApplicationSources(context: Context): ApplicationSources {
    val directory = try { androidCacheDirectory(context.applicationContext.cacheDir) } catch (_: Exception) { null }
    val progressDirectory = try { androidProgressDirectory(context.applicationContext.filesDir) } catch (_: Exception) { null }
    return createSources(DiskResourceCache(directory), progressDirectory)
}

internal fun androidCacheDirectory(privateCacheDir: File): Path = privateCacheDir.toPath().resolve(CACHE_DIRECTORY_NAME)

internal fun createApplicationSources(privateCacheDir: File, privateFilesDir: File? = null): ApplicationSources =
    createSources(DiskResourceCache(androidCacheDirectory(privateCacheDir)), privateFilesDir?.let(::androidProgressDirectory))

internal fun androidProgressDirectory(privateFilesDir: File): Path =
    privateFilesDir.toPath().resolve(PROGRESS_DIRECTORY_NAME)
