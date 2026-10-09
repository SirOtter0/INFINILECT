// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ProfileEditor(profile: LocalProfile, save: (LocalProfile) -> Unit, close: () -> Unit) {
    var name by remember { mutableStateOf(profile.displayName.orEmpty()) }
    var country by remember { mutableStateOf(profile.countryCode) }
    var language by remember { mutableStateOf(profile.interfaceLanguage) }
    var countriesVisible by remember { mutableStateOf(false) }
    val countries = remember { residenceCountries() }
    val focus = remember { FocusRequester() }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth(.94f).heightIn(max = maxHeight-24.dp)
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false }, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (profile.setupHandled) "Profile" else "Your local profile", style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                    Text("Personalize your welcome. This stays on your device; no online account, email or password is needed.")
                    OutlinedTextField(name, { value ->
                        if (value.length <= 80 && value.none { it.code < 32 || it.code in 127..159 || it == '\u2028' || it == '\u2029' }) name = value
                    }, label = { Text("Display name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    OutlinedButton({ countriesVisible = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { contentDescription = "Country of residence" }) {
                        Text(country?.let { code -> countries.firstOrNull { it.code == code }?.label } ?: "Choose country of residence")
                    }
                    Text("Residence is requested for future country-dependent discovery. Your choice is a declaration, not proof of legal eligibility. Local reading remains available without it.", style = MaterialTheme.typography.caption)
                    Text("Interface language", style = MaterialTheme.typography.subtitle2)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ language = null }, Modifier.heightIn(min = 48.dp).semantics { selected = language == null }) { Text("App default") }
                        TextButton({ language = "en" }, Modifier.heightIn(min = 48.dp).semantics { selected = language == "en" }) { Text("English") }
                    }
                    Text("English is currently the available interface language.", style = MaterialTheme.typography.caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ save(LocalProfile(name.trim().ifEmpty { null }, country, language, setupHandled = true)); close() },
                            Modifier.heightIn(min = 48.dp), enabled = country != null) { Text("Save profile") }
                        TextButton(close, Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    }
                    if (!profile.setupHandled) TextButton({ save(profile.copy(setupHandled = true)); close() }, Modifier.heightIn(min = 48.dp)) { Text("Set up later") }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
    if (countriesVisible) CountryPicker(countries, { country = it; countriesVisible = false }, { countriesVisible = false })
}

@Composable
private fun CountryPicker(countries: List<ResidenceCountry>, choose: (String) -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val height = maxHeight * .8f
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(.94f).heightIn(max = height)
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false }, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp)) {
                    Text("Country of residence", style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                    OutlinedTextField(query, { if (it.length <= 80) query = it }, label = { Text("Find country") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).focusRequester(focus))
                    val matching = countries.filter { it.label.contains(query.trim(), ignoreCase = true) || it.code.equals(query.trim(), ignoreCase = true) }
                        .sortedBy { if (it.code.equals(query.trim(), ignoreCase = true)) 0 else 1 }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        if (matching.isEmpty()) item { Text("No matching country") }
                        items(matching, key = { it.code }) { item ->
                            TextButton({ choose(item.code) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Choose country ${item.code}" }) {
                                Text(item.label, Modifier.weight(1f)); Text(item.code, style = MaterialTheme.typography.caption)
                            }
                        }
                    }
                    TextButton(close, Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
