// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun ApplicationSettingsScreen(mode: ApplicationThemeMode, change: (ApplicationThemeMode) -> Unit, failed: Boolean, profile: LocalProfile = LocalProfile(), editProfile: () -> Unit = {}, profileReady: Boolean = true, discovery: org.infinilect.app.discovery.DiscoveryPreferences = org.infinilect.app.discovery.DiscoveryPreferences(), changeDiscovery: ((org.infinilect.app.discovery.DiscoveryPreferences) -> Unit)? = null,
    preferences: ApplicationAppearancePreferences? = null, translation: org.infinilect.app.reader.epub.EpubTranslationService? = null, developmentSettings: @Composable () -> Unit = {}) {
    val strings = LocalAppStrings.current
    var languages by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().semantics { paneTitle=strings[UiText.SETTINGS] }.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings[UiText.PROFILE], style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
            Text(profile.displayName ?: strings[UiText.NO_NAME])
            Text(profile.countryCode?.let { code -> residenceCountries().firstOrNull { it.code == code }?.label } ?: strings[UiText.NO_RESIDENCE], style = MaterialTheme.typography.body2)
            Text(strings[UiText.LOCAL_ACCOUNT], style = MaterialTheme.typography.caption)
            SettingsAccessRow(strings[UiText.EDIT_PROFILE], strings[UiText.LOCAL_ACCOUNT], profileReady, editProfile) {
                val color = MaterialTheme.colors.primary
                Canvas(Modifier.size(28.dp)) {
                    drawCircle(color, radius = size.width * .16f, center = Offset(size.width / 2, size.height * .28f))
                    drawArc(color, 180f, 180f, false, topLeft = Offset(size.width * .16f, size.height * .5f), size = androidx.compose.ui.geometry.Size(size.width * .68f, size.height * .48f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
        }
        if (preferences != null) SettingsAccessRow(strings[UiText.LANGUAGE_TRANSLATION], strings[UiText.LANGUAGE_TRANSLATION_DESCRIPTION], profileReady, { languages = true }) {
            Text("A↔文", color = MaterialTheme.colors.primary, style = MaterialTheme.typography.subtitle2)
        }
        Surface(shape = MaterialTheme.shapes.medium, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings[UiText.APP_APPEARANCE], style = MaterialTheme.typography.h6)
                Text(strings[UiText.APP_APPEARANCE_NOTICE], style = MaterialTheme.typography.body2)
                Column(Modifier.selectableGroup()) {
                    ApplicationThemeMode.entries.forEach { choice ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(mode == choice, role = Role.RadioButton, onClick = { change(choice) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(mode == choice, null)
                            Text(when (choice) { ApplicationThemeMode.SYSTEM -> strings[UiText.SYSTEM]; ApplicationThemeMode.LIGHT -> strings[UiText.LIGHT]; ApplicationThemeMode.DARK -> strings[UiText.DARK] }, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
        if (failed) FeedbackCard(strings[UiText.SETTINGS_SAVE_ERROR], strings[UiText.SETTINGS_SAVE_NOTICE], error = true,
            action = strings[UiText.RETRY_SETTINGS], onAction = { change(mode) })
        if (changeDiscovery != null && profileReady) org.infinilect.app.discovery.DiscoveryPreferenceControls(discovery, changeDiscovery)
        FeedbackCard(strings[UiText.READING_PREFS], strings[UiText.READING_PREFS_NOTICE])
        Text(strings[UiText.FREE_READING], style = MaterialTheme.typography.caption)
        developmentSettings()
    }
    if (languages && preferences != null) LanguageTranslationPanel(preferences, translation) { languages = false }
}

/** Whole-row click/focus/semantics, with a decorative icon and visible disclosure affordance. */
@Composable
private fun SettingsAccessRow(title: String, subtitle: String, enabled: Boolean, click: () -> Unit, icon: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colors.onSurface.copy(alpha = .045f)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button, onClick = click)
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(36.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.subtitle1)
                Text(subtitle, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = .75f))
            }
            val color = MaterialTheme.colors.onSurface
            Canvas(Modifier.size(20.dp).clearAndSetSemantics {}) {
                drawLine(color, Offset(size.width * .35f, size.height * .2f), Offset(size.width * .65f, size.height * .5f), 2.dp.toPx())
                drawLine(color, Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .8f), 2.dp.toPx())
            }
        }
    }
}
