// SPDX-License-Identifier: GPL-3.0-only
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("INFINILECT", style = MaterialTheme.typography.h4)
                Text("Open knowledge. Infinite reading.")
                Text("Early development: publication search and reading are not available yet.")
            }
        }
    }
}
