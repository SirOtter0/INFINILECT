// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val sources = remember { createApplicationSources() }
    val picker = remember { org.infinilect.app.imports.DesktopLocalFilePicker() }
    DisposableEffect(sources) { onDispose { sources.close() } }
    val scope = rememberCoroutineScope()
    Window(onCloseRequest = {
        sources.close()
        scope.launch {
            // A quick close must drain the final global preference choice before process exit.
            sources.awaitPreferencesClosed()
            withTimeoutOrNull(3_000) { sources.awaitProgressClosed() }
            exitApplication()
        }
    }, title = "INFINILECT") {
        App(sources,localFilePicker=picker)
    }
}
