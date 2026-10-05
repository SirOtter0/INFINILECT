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
import org.infinilect.app.reader.epub.*

internal fun createSources(
    cache: DiskResourceCache,
    progressDirectory: Path? = null,
    progressDiagnostics: (ProgressStorageFailure) -> Unit = {},
    collections: ApplicationCollections? = null,
    textDirectory: Path? = org.infinilect.app.reader.desktopTextDirectory(),
    developmentEpubEnabled: Boolean = false,
    epubDirectory: Path? = org.infinilect.app.epub.desktopEpubDirectory(),
    epubSettingsDirectory: Path? = progressDirectory?.parent?.resolve(EPUB_SETTINGS_DIRECTORY_NAME),
): ApplicationSources {
    val gutenberg = try { GutenbergSource() } catch (error: Throwable) { collections?.close(); cache.close(); throw error }
    val archive = try { InternetArchiveSource() }
    catch (error: Throwable) { collections?.close(); try { gutenberg.close() } finally { cache.close() }; throw error }
    val progress = ProgressPersistence(
        FileReadingProgressStore(progressDirectory, onFailure = progressDiagnostics), clock = ::progressTime,
    )
    val textPreparer = org.infinilect.app.reader.FileTextPreparer(textDirectory)
    val epubPreparer = org.infinilect.app.epub.FileEpubPreparer(epubDirectory)
    val epubSettings = EpubSettingsPersistence(FileEpubReaderSettingsStore(epubSettingsDirectory), clock = ::epubPreferenceTime)
    return ApplicationSources(listOf(
        SourceOption("Internet Archive", archive, textReadingEnabled = true),
        SourceOption("Project Gutenberg (experimental)", gutenberg, textReadingEnabled = false),
    ) + (if (developmentEpubEnabled) listOf(SourceOption("EPUB development demo", org.infinilect.app.epub.DevelopmentEpubSource(), epubReadingEnabled = true)) else emptyList()), createLoader = { cache.loader(it.id, DirectResourceLoader(it)) }, progress = progress, collections = collections, textPreparer = textPreparer, epubPreparer = epubPreparer, epubSettings = epubSettings) {
        try { cache.close() } finally { try { gutenberg.close() } finally { archive.close() } }
    }
}
