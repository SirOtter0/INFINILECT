// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import org.infinilect.app.network.createSources
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.nio.file.Path
import org.infinilect.app.cache.CACHE_DIRECTORY_NAME
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.progress.PROGRESS_DIRECTORY_NAME
import org.infinilect.app.progress.ProgressStorageFailure
import org.infinilect.app.collections.*

/** Extract the application-private path immediately; neither cache nor sources retain Context. */
fun createApplicationSources(context: Context): ApplicationSources {
    val appContext = context.applicationContext
    val directory = try { androidCacheDirectory(context.applicationContext.cacheDir) } catch (_: Exception) { null }
    val progressDirectory = try { androidProgressDirectory(context.applicationContext.filesDir) } catch (_: Exception) { null }
    val debuggable = context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val store = SqlCollectionsStore({ androidCollectionsDriver(appContext) },
        onFailure = androidCollectionsDiagnostics(debuggable))
    return createSources(DiskResourceCache(directory), progressDirectory, progressDiagnostics = { failure ->
        if (debuggable) Log.w("INFINILECTProgress", "${failure.operation}/${failure.stage}/${failure.reason}")
    }, collections = ApplicationCollections(store.library,store.history,release = store::close),
        textDirectory = org.infinilect.app.reader.androidTextDirectory(appContext.cacheDir.toPath()))
}

internal fun androidCacheDirectory(privateCacheDir: File): Path = privateCacheDir.toPath().resolve(CACHE_DIRECTORY_NAME)

internal fun createApplicationSources(
    privateCacheDir: File,
    privateFilesDir: File? = null,
    progressDiagnostics: (ProgressStorageFailure) -> Unit = {},
): ApplicationSources = createSources(
    DiskResourceCache(androidCacheDirectory(privateCacheDir)), privateFilesDir?.let(::androidProgressDirectory), progressDiagnostics,
    textDirectory = org.infinilect.app.reader.androidTextDirectory(privateCacheDir.toPath()),
)

internal fun androidProgressDirectory(privateFilesDir: File): Path =
    privateFilesDir.toPath().resolve(PROGRESS_DIRECTORY_NAME)
