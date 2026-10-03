// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import org.infinilect.app.ApplicationSources
import org.infinilect.app.SourceOption
import org.infinilect.app.archive.InternetArchiveSource
import org.infinilect.app.gutenberg.GutenbergSource

internal fun createSources(): ApplicationSources {
    val gutenberg = GutenbergSource()
    val archive = try { InternetArchiveSource() }
    catch (error: Throwable) { gutenberg.close(); throw error }
    return ApplicationSources(listOf(
        SourceOption("Project Gutenberg", gutenberg),
        SourceOption("Internet Archive", archive, textReadingEnabled = true),
    )) { try { gutenberg.close() } finally { archive.close() } }
}
