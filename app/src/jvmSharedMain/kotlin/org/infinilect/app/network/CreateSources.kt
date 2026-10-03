// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import org.infinilect.app.ApplicationSources
import org.infinilect.app.SourceOption
import org.infinilect.app.archive.InternetArchiveSource
import org.infinilect.app.gutenberg.GutenbergSource
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.cache.DiskResourceCache

internal fun createSources(cache: DiskResourceCache): ApplicationSources {
    val gutenberg = try { GutenbergSource() } catch (error: Throwable) { cache.close(); throw error }
    val archive = try { InternetArchiveSource() }
    catch (error: Throwable) { try { gutenberg.close() } finally { cache.close() }; throw error }
    return ApplicationSources(listOf(
        SourceOption("Project Gutenberg", gutenberg),
        SourceOption("Internet Archive", archive, textReadingEnabled = true),
    ), createLoader = { cache.loader(it.id, DirectResourceLoader(it)) }) {
        try { cache.close() } finally { try { gutenberg.close() } finally { archive.close() } }
    }
}
