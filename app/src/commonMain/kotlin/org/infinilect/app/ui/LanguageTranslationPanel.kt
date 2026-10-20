// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import org.infinilect.app.reader.epub.*

internal fun LocalizedStrings.language(code: String?): String = this[when(code) {
    "en" -> UiText.ENGLISH; "es" -> UiText.SPANISH; "ca" -> UiText.CATALAN; else -> UiText.SYSTEM
}]

@Composable
internal fun LanguageTranslationPanel(preferences: ApplicationAppearancePreferences, service: EpubTranslationService?, close: () -> Unit) {
    val strings = LocalAppStrings.current
    val state by preferences.state.collectAsState()
    LanguageTranslationDialog(close) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings[UiText.INTERFACE_LANGUAGE], style = MaterialTheme.typography.subtitle2)
            val retryLanguage = LocalRetryInterfaceLanguage.current
            if (LocalInterfaceLanguageLoading.current) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (strings.failed) {
                Text(strings[UiText.LOCALIZATION_ERROR], color = MaterialTheme.colors.error)
                TextButton(retryLanguage) { Text(strings[UiText.RETRY]) }
            }
            LanguageChoices(state.profile.interfaceLanguage, true) { preferences.changeProfile(state.profile.copy(interfaceLanguage = it)) }
            Text(strings[UiText.DEFAULT_READING_LANGUAGE], style = MaterialTheme.typography.subtitle2)
            LanguageChoices(state.readingLanguage, true, strings[UiText.FOLLOW_INTERFACE], preferences::changeReadingLanguage)
            val destination = state.readingLanguage ?: resolveInterfaceLanguage(state.profile.interfaceLanguage, deviceInterfaceLanguage())
            if (service?.engine?.models?.none { it.pair.target == destination } != false) Text(strings[UiText.NO_PAIR], style = MaterialTheme.typography.caption)
            Divider()
            if (service == null || service.engine.unavailable != null) Text(strings.message(service?.engine?.unavailable ?: UnavailableTranslation.unavailable))
            else TranslationModelList(service.models)
        }
    }
}

/** The scroll viewport is measured inside the modal's actual safe/IME-constrained window. */
@Composable
private fun LanguageTranslationDialog(close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val strings = LocalAppStrings.current
    val title = strings[UiText.LANGUAGE_TRANSLATION]
    val closeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight).semantics { paneTitle = title }
                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false },
                shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.h6)
                        EpubIconButton(strings.format(UiText.CLOSE_PANEL, title), EpubControlIcon.CLOSE, close, Modifier.focusRequester(closeFocus))
                    }
                    content()
                    TextButton(close, Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(strings[UiText.DONE]) }
                }
            }
        }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
    }
}

@Composable
internal fun LanguageChoices(value: String?, system: Boolean, systemLabel: String = LocalAppStrings.current[UiText.SYSTEM], change: (String?) -> Unit) {
    val strings = LocalAppStrings.current
    Column(Modifier.selectableGroup()) {
        (if (system) listOf(null) + interfaceLanguages else interfaceLanguages).forEach { language ->
            val label = if (language == null) systemLabel else strings.language(language)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(value == language, role = Role.RadioButton, onClick = { change(language) })
                .semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(value == language, null)
                Text(label)
            }
        }
    }
}

@Composable
private fun TranslationModelList(manager: TranslationModels) {
    val strings = LocalAppStrings.current
    val state by manager.state.collectAsState()
    var delete by remember { mutableStateOf<TranslationModel?>(null) }
    var clean by remember { mutableStateOf(false) }
    LaunchedEffect(manager, state.working) { if (!state.loaded && !state.working && state.error == null) manager.refresh() }
    Text(strings[UiText.MODELS_AVAILABLE], style = MaterialTheme.typography.subtitle2)
    if (state.models.map { it.model }.toSet() == foxletVerifiedModels.toSet())
        Text(strings[UiText.DIRECT_MODEL_LIMITS], style = MaterialTheme.typography.caption)
    state.storageBytes?.let { Text(strings.format(UiText.STORAGE, (it + 1_048_575) / 1_048_576), style = MaterialTheme.typography.caption) }
        ?: if (state.loaded) Text(strings[UiText.STORAGE_UNKNOWN], style = MaterialTheme.typography.caption) else Unit
    state.models.forEach { row ->
        val model = row.model
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${strings.language(model.pair.source)} → ${strings.language(model.pair.target)}", style = MaterialTheme.typography.subtitle1)
            Text(strings[if (row.installed) UiText.INSTALLED else UiText.NOT_INSTALLED], style = MaterialTheme.typography.caption)
            Text(strings.format(UiText.DOWNLOAD_SIZE, (model.bytes + 1_048_575) / 1_048_576), style = MaterialTheme.typography.caption)
            if (row.installed) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings[if (row.retention.pinned) UiText.PINNED else UiText.TEMPORARY], Modifier.weight(1f), style = MaterialTheme.typography.body2)
                    Switch(row.retention.pinned, { manager.pin(model, it) }, enabled = !state.working,
                        modifier = Modifier.semantics { contentDescription = strings[UiText.PIN_MODEL] })
                }
                if (row.inUse) Text(strings[UiText.MODEL_IN_USE], style = MaterialTheme.typography.caption)
                TextButton({ delete = model }, enabled = !state.working && state.models.none { it.inUse }) { Text(strings[UiText.DELETE_MODEL]) }
            } else TextButton({ manager.install(model, pinned = true) }, enabled = !state.working) { Text(strings[UiText.DOWNLOAD]) }
            Divider()
        }
    }
    if (state.working) {
        if (state.total > 0) LinearProgressIndicator((state.downloaded.toFloat() / state.total).coerceIn(0f, 1f), Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(manager::cancel) { Text(strings[UiText.CANCEL]) }
    }
    state.error?.let {
        Text(strings.message(it), color = MaterialTheme.colors.error)
        TextButton({ manager.retry() }, enabled = !state.working) { Text(strings[UiText.RETRY]) }
    }
    TextButton({ clean = true }, enabled = !state.working) { Text(strings[UiText.CLEAN_TEMPORARY]) }
    Text(strings[UiText.PRIVACY_MODELS], style = MaterialTheme.typography.caption)
    Text("github.com/yinvoke/foxlet-translate/tree/0eff2a15032362115848a496ce1df8ed9fc68650 · github.com/mozilla/firefox-translations-models", style = MaterialTheme.typography.caption)
    if (delete != null || clean) AlertDialog(onDismissRequest = { delete = null; clean = false },
        title = { Text(strings[if (clean) UiText.CLEAN_TEMPORARY else UiText.DELETE_MODEL]) },
        text = { Text(strings[if (clean) UiText.CLEAN_TEMPORARY_CONFIRM else UiText.DELETE_MODEL_CONFIRM]) },
        confirmButton = { TextButton({
            if (clean) manager.cleanTemporary() else delete?.let(manager::remove)
            delete = null; clean = false
        }) { Text(strings[if (clean) UiText.CLEAN else UiText.DELETE_MODEL]) } },
        dismissButton = { TextButton({ delete = null; clean = false }) { Text(strings[UiText.CANCEL]) } })
}
