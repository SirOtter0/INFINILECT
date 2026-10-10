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
import org.infinilect.app.reader.page.*

internal fun createSources(
    cache: DiskResourceCache,
    progressDirectory: Path? = null,
    progressDiagnostics: (ProgressStorageFailure) -> Unit = {},
    collections: ApplicationCollections? = null,
    textDirectory: Path? = org.infinilect.app.reader.desktopTextDirectory(),
    developmentEpubEnabled: Boolean = false,
    epubDirectory: Path? = org.infinilect.app.epub.desktopEpubDirectory(),
    epubSettingsDirectory: Path? = progressDirectory?.parent?.resolve(EPUB_SETTINGS_DIRECTORY_NAME),
    developmentComicEnabled: Boolean = false,
    pageSettingsDirectory: Path? = progressDirectory?.parent?.resolve(PAGE_SETTINGS_DIRECTORY_NAME),
    cbzPreparationDirectory: Path? = null,
    pdfDirectory: Path? = textDirectory?.parent?.resolve(org.infinilect.app.reader.pdf.PDF_PREPARATION_DIRECTORY),
    appearanceDirectory: Path? = progressDirectory?.parent?.resolve(org.infinilect.app.ui.APPLICATION_APPEARANCE_DIRECTORY),
    importDirectory: Path? = progressDirectory?.parent?.resolve(org.infinilect.app.imports.IMPORT_DIRECTORY_NAME),
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
    val pageSettings = PageSettingsPersistence(FilePageReaderSettingsStore(pageSettingsDirectory), clock = ::pagePreferenceTime)
    val pagePreparer = org.infinilect.app.reader.page.CbzPagePreparer(cbzPreparationDirectory)
    val pdfPreparer = org.infinilect.app.reader.pdf.FilePdfPreparer(pdfDirectory)
    val local = org.infinilect.app.imports.FileLocalPublicationSource(importDirectory, textPreparer, epubPreparer, pagePreparer, pdf = pdfPreparer)
    return ApplicationSources(listOf(
        SourceOption("Internet Archive", archive, textReadingEnabled = true),
        SourceOption("Project Gutenberg (experimental)", gutenberg, textReadingEnabled = false),
        SourceOption("Imported files", local, textReadingEnabled = true, epubReadingEnabled = true, pageReadingEnabled = true, pdfReadingEnabled = true),
    ) + (if (developmentEpubEnabled) listOf(SourceOption("EPUB development demo", org.infinilect.app.epub.DevelopmentEpubSource(), epubReadingEnabled = true)) else emptyList()) + (if (developmentComicEnabled) listOf(SourceOption("Comic development demo", org.infinilect.app.page.DevelopmentComicSource(), pageReadingEnabled = true), SourceOption("CBZ development demo", org.infinilect.app.page.DevelopmentCbzSource(), pageReadingEnabled = true)) else emptyList()), createLoader = { if(it === local) DirectResourceLoader(it) else cache.loader(it.id, DirectResourceLoader(it)) }, progress = progress, collections = collections, textPreparer = textPreparer, epubPreparer = epubPreparer, epubSettings = epubSettings, pagePreparer = pagePreparer, pageSettings = pageSettings, localImports = local, covers = org.infinilect.app.covers.PublicationCovers(load = { id -> when (id.sourceId) { local.id -> local.cover(id); gutenberg.id -> gutenberg.coverThumbnail(id); else -> null } }), descriptions = org.infinilect.app.ui.PublicationDescriptions(local::description), appearance = org.infinilect.app.ui.ApplicationAppearancePreferences(org.infinilect.app.ui.FileApplicationAppearanceStore(appearanceDirectory)), pdfPreparer = pdfPreparer) {
        try { cache.close() } finally { try { gutenberg.close() } finally { archive.close() } }
    }
}
