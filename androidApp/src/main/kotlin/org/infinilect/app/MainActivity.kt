// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private lateinit var sources: ApplicationSources

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sources = createApplicationSources()
        setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                App(sources, backHandler = { enabled, onBack ->
                    BackHandler(enabled = enabled, onBack = onBack)
                })
            }
        }
    }

    override fun onDestroy() {
        // Cancels the current session first; aborts streaming/client/engine afterward.
        // Re-creation deliberately starts a new session; no retained Activity/clients.
        if (::sources.isInitialized) sources.close()
        super.onDestroy()
    }
}
