// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.infinilect.app.gutenberg.GutenbergSource
import org.infinilect.app.archive.InternetArchiveSource

fun main() = application {
    val gutenberg = remember { GutenbergSource() }
    val archive = remember { InternetArchiveSource() }
    val sources = remember(gutenberg, archive) { listOf(
        SourceOption("Project Gutenberg", gutenberg),
        SourceOption("Internet Archive", archive, textReadingEnabled = true),
    ) }
    DisposableEffect(gutenberg, archive) { onDispose { gutenberg.close(); archive.close() } }
    Window(onCloseRequest = ::exitApplication, title = "INFINILECT") {
        App(sources)
    }
}
