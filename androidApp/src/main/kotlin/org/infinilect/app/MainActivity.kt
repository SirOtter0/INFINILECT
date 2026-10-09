// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var sources: ApplicationSources
    private lateinit var picker: AndroidDocumentPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only application-context sources are retained. The Activity/picker/UI are not.
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ReaderRuntime::class.java)
                return ReaderRuntime(createApplicationSources(applicationContext)) as T
            }
        }
        sources = ViewModelProvider(this, factory)[ReaderRuntime::class.java].sources
        picker = AndroidDocumentPicker(this)
        setContent {
            var appearance by remember { mutableStateOf<ReaderAppearance?>(null) }
            // Draw opaque reading paper behind transparent bars, including Android
            // 15+ enforced edge-to-edge. Insets remain consumed once by this owner.
            DisposableEffect(appearance) {
                val reader = appearance
                // The existing application MaterialTheme is light; reader appearance
                // is scoped to EPUB and must not carry over to other destinations.
                val style = if (reader?.dark == true) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                if (reader != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        // Our opaque backdrop supplies contrast in both navigation modes.
                        window.isNavigationBarContrastEnforced = false
                    }
                }
                onDispose { }
            }
            Box(Modifier.fillMaxSize().background(appearance?.background ?: Color.White).safeDrawingPadding().imePadding()) {
                App(sources, localFilePicker = picker, backHandler = { enabled, onBack ->
                    BackHandler(enabled = enabled, onBack = onBack)
                }, readerAppearance = { appearance = it })
            }
        }
    }

    override fun onStop() {
        if (::sources.isInitialized) sources.flushProgress()
        super.onStop()
    }

    override fun onDestroy() {
        if (::picker.isInitialized) picker.close()
        super.onDestroy()
    }

    private class ReaderRuntime(val sources: ApplicationSources) : ViewModel() {
        override fun onCleared() {
            // Real finish releases readers before their providers; rotation only replaces UI.
            sources.close()
            super.onCleared()
        }
    }
}
