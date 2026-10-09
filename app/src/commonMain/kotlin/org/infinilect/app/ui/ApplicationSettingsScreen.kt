// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun ApplicationSettingsScreen(mode: ApplicationThemeMode, change: (ApplicationThemeMode) -> Unit, failed: Boolean) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ScreenHeading("Settings", "Make the application comfortable for you.")
        Surface(shape = MaterialTheme.shapes.medium, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Application appearance", style = MaterialTheme.typography.h6)
                Text("System follows your device. Reader appearance and typography remain in each reader's settings.", style = MaterialTheme.typography.body2)
                Column(Modifier.selectableGroup()) {
                    ApplicationThemeMode.entries.forEach { choice ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(mode == choice, role = Role.RadioButton, onClick = { change(choice) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(mode == choice, null)
                            Text(when (choice) { ApplicationThemeMode.SYSTEM -> "System"; ApplicationThemeMode.LIGHT -> "Light"; ApplicationThemeMode.DARK -> "Dark" }, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
        if (failed) FeedbackCard("Appearance could not be saved", "Your choice applies for now. Retry to keep it after restarting.", error = true,
            action = "Retry saving appearance", onAction = { change(mode) })
        FeedbackCard("Reading preferences", "Font size, margins and reading modes are available inside the reader. Changing application appearance does not reset your position or reader preferences.")
        Text("INFINILECT · Free, open-source reading", style = MaterialTheme.typography.caption)
    }
}
