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
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf(profile.displayName.orEmpty()) }
    var country by remember { mutableStateOf(profile.countryCode) }
    var language by remember { mutableStateOf(profile.interfaceLanguage) }
    var countriesVisible by remember { mutableStateOf(false) }
    val countries = remember(LocalInterfaceLanguage.current) { residenceCountries() }
    val focus = remember { FocusRequester() }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth(.94f).heightIn(max = maxHeight-24.dp)
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false }, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (profile.setupHandled) strings[UiText.PROFILE] else strings[UiText.LOCAL_PROFILE], style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                    Text(strings[UiText.LOCAL_PROFILE_NOTICE])
                    OutlinedTextField(name, { value ->
                        if (value.length <= 80 && value.none { it.code < 32 || it.code in 127..159 || it == '\u2028' || it == '\u2029' }) name = value
                    }, label = { Text(strings[UiText.DISPLAY_NAME]) }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    OutlinedButton({ countriesVisible = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { contentDescription = strings[UiText.COUNTRY] }) {
                        Text(country?.let { code -> countries.firstOrNull { it.code == code }?.label } ?: strings[UiText.CHOOSE_COUNTRY])
                    }
                    Text(strings[UiText.COUNTRY_NOTICE], style = MaterialTheme.typography.caption)
                    Text(strings[UiText.INTERFACE_LANGUAGE], style = MaterialTheme.typography.subtitle2)
                    LanguageChoices(language, true) { language = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ save(LocalProfile(name.trim().ifEmpty { null }, country, language, setupHandled = true)); close() },
                            Modifier.heightIn(min = 48.dp), enabled = country != null) { Text(strings[UiText.SAVE_PROFILE]) }
                        TextButton(close, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.CANCEL]) }
                    }
                    if (!profile.setupHandled) TextButton({ save(profile.copy(interfaceLanguage = language, setupHandled = true)); close() }, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.SET_UP_LATER]) }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
    if (countriesVisible) CountryPicker(countries, { country = it; countriesVisible = false }, { countriesVisible = false })
}

@Composable
private fun CountryPicker(countries: List<ResidenceCountry>, choose: (String) -> Unit, close: () -> Unit) {
    val strings = LocalAppStrings.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val height = maxHeight * .8f
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(.94f).heightIn(max = height)
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false }, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp)) {
                    Text(strings[UiText.COUNTRY], style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                    OutlinedTextField(query, { if (it.length <= 80) query = it }, label = { Text(strings[UiText.FIND_COUNTRY]) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).focusRequester(focus))
                    val matching = countries.filter { it.label.contains(query.trim(), ignoreCase = true) || it.code.equals(query.trim(), ignoreCase = true) }
                        .sortedBy { if (it.code.equals(query.trim(), ignoreCase = true)) 0 else 1 }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        if (matching.isEmpty()) item { Text(strings[UiText.NO_COUNTRY]) }
                        items(matching, key = { it.code }) { item ->
                            TextButton({ choose(item.code) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = strings.format(UiText.CHOOSE_COUNTRY_CODE, item.code) }) {
                                Text(item.label, Modifier.weight(1f)); Text(item.code, style = MaterialTheme.typography.caption)
                            }
                        }
                    }
                    TextButton(close, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.CANCEL]) }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
