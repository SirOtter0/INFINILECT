// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.infinilect.app.gutenberg.GutenbergSource
import org.infinilect.app.search.SearchController

fun main() = application {
    val source = remember { GutenbergSource() }
    val controller = remember(source) { SearchController(source) }
    DisposableEffect(source) { onDispose { source.close() } }
    Window(onCloseRequest = ::exitApplication, title = "INFINILECT") {
        App(controller)
    }
}
