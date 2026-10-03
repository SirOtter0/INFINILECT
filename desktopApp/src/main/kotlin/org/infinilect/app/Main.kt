// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val sources = remember { createApplicationSources() }
    DisposableEffect(sources) { onDispose { sources.close() } }
    Window(onCloseRequest = ::exitApplication, title = "INFINILECT") {
        App(sources)
    }
}
