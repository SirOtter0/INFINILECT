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
    private var localDiagnostics: org.infinilect.app.diagnostics.AndroidLocalDiagnostics? = null
    private lateinit var picker: AndroidDocumentPicker
    private var frameDiagnostics: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only application-context sources are retained. The Activity/picker/UI are not.
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ReaderRuntime::class.java)
                return ReaderRuntime(createApplicationSources(applicationContext), applicationContext) as T
            }
        }
        val runtime = ViewModelProvider(this, factory)[ReaderRuntime::class.java]
        sources = runtime.sources
        localDiagnostics = runtime.localDiagnostics
        picker = AndroidDocumentPicker(this)
        setContent {
            var appearance by remember { mutableStateOf<ReaderAppearance?>(null) }
            var appAppearance by remember { mutableStateOf(ReaderAppearance(false, Color.White)) }
            val systemAppearance = resolveSystemAppearance(appAppearance, appearance)
            // Draw opaque reading paper behind transparent bars, including Android
            // 15+ enforced edge-to-edge. Insets remain consumed once by this owner.
            DisposableEffect(systemAppearance) {
                val reader = systemAppearance
                // EPUB appearance overrides the application only for the reader lifetime.
                val style = if (reader.dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    // Our opaque backdrop supplies contrast in both navigation modes.
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose { }
            }
            Box(Modifier.fillMaxSize().background(systemAppearance.background).safeDrawingPadding().imePadding()) {
                App(sources, localFilePicker = picker, backHandler = { enabled, onBack ->
                    BackHandler(enabled = enabled, onBack = onBack)
                }, readerAppearance = { appearance = it }, applicationAppearance = { appAppearance = it },
                    developmentSettings = { localDiagnostics?.let { org.infinilect.app.diagnostics.LocalDiagnosticsControls(it) } })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("infinilect.performance", false)) {
            @Suppress("DEPRECATION")
            val refreshRate = if (android.os.Build.VERSION.SDK_INT >= 30) display?.refreshRate ?: 60f else windowManager.defaultDisplay.refreshRate
            frameDiagnostics = org.infinilect.app.diagnostics.startFrameDiagnostics(window, refreshRate)
        }
        localDiagnostics?.foreground()
        if (::sources.isInitialized) sources.resumeDiscovery()
    }

    override fun onStop() {
        localDiagnostics?.background()
        frameDiagnostics?.close(); frameDiagnostics = null
        if (::sources.isInitialized) { sources.pauseDiscovery(); sources.flushProgress() }
        super.onStop()
    }

    override fun onDestroy() {
        if (::picker.isInitialized) picker.close()
        super.onDestroy()
    }

    private class ReaderRuntime(val sources: ApplicationSources, context: android.content.Context) : ViewModel() {
        val localDiagnostics = if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            org.infinilect.app.diagnostics.AndroidLocalDiagnostics(context, sources) else null
        override fun onCleared() {
            // Real finish releases readers before their providers; rotation only replaces UI.
            sources.close()
            localDiagnostics?.close()
            super.onCleared()
        }
    }
}
