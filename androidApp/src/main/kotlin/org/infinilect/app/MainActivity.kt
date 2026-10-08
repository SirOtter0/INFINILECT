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
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                App(sources, localFilePicker = picker, backHandler = { enabled, onBack ->
                    BackHandler(enabled = enabled, onBack = onBack)
                })
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
