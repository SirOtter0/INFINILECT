// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import org.infinilect.app.ApplicationSources
import org.infinilect.app.SourceOption
import org.infinilect.app.archive.InternetArchiveSource
import org.infinilect.app.gutenberg.GutenbergSource
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.progress.FileReadingProgressStore
import org.infinilect.app.progress.progressTime
import org.infinilect.app.progress.ProgressStorageFailure
import java.nio.file.Path
import org.infinilect.app.collections.ApplicationCollections

internal fun createSources(
    cache: DiskResourceCache,
    progressDirectory: Path? = null,
    progressDiagnostics: (ProgressStorageFailure) -> Unit = {},
    collections: ApplicationCollections? = null,
    textDirectory: Path? = org.infinilect.app.reader.desktopTextDirectory(),
): ApplicationSources {
    val gutenberg = try { GutenbergSource() } catch (error: Throwable) { collections?.close(); cache.close(); throw error }
    val archive = try { InternetArchiveSource() }
    catch (error: Throwable) { collections?.close(); try { gutenberg.close() } finally { cache.close() }; throw error }
    val progress = ProgressPersistence(
        FileReadingProgressStore(progressDirectory, onFailure = progressDiagnostics), clock = ::progressTime,
    )
    val textPreparer = org.infinilect.app.reader.FileTextPreparer(textDirectory)
    return ApplicationSources(listOf(
        SourceOption("Internet Archive", archive, textReadingEnabled = true),
        SourceOption("Project Gutenberg (experimental)", gutenberg, textReadingEnabled = false),
    ), createLoader = { cache.loader(it.id, DirectResourceLoader(it)) }, progress = progress, collections = collections, textPreparer = textPreparer) {
        try { cache.close() } finally { try { gutenberg.close() } finally { archive.close() } }
    }
}
