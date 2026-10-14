// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal expect fun Modifier.epubTranslationMenu(enabled: Boolean, translate: () -> Unit): Modifier

private fun language(code: String) = if (code == "en") "English" else "Spanish"

@Composable
internal fun EpubTranslationSettings(reader: EpubReaderController) {
    val translation = reader.translation
    val state by translation.state.collectAsState()
    LaunchedEffect(translation) { translation.refresh() }
    Divider()
    Text("Reading language", style = MaterialTheme.typography.subtitle2)
    val unavailable = translation.unavailable
    if (unavailable != null) { Text(unavailable, style = MaterialTheme.typography.body2); return }
    Text("Local translation · Foxlet / Bergamot", style = MaterialTheme.typography.caption)
    Text("EPUB language: ${reader.document.metadata.languages.joinToString().ifBlank { "not provided" }}. Metadata is a suggestion, not language detection.", style = MaterialTheme.typography.caption)
    if (epubTranslationLanguage(reader.document.metadata.languages.firstOrNull()) != null) {
        TextButton(onClick = reader::translationMetadata, enabled = !state.busy) { Text("Use EPUB language metadata") }
    }
    if (state.model == null) Text("Choose a supported pair. When source and target match, continue with Original.", style = MaterialTheme.typography.caption)
    translation.service?.engine?.models?.forEach { model ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            RadioButton(state.model == model, onClick = { reader.translationModel(model) }, enabled = !state.busy)
            TextButton(onClick = { reader.translationModel(model) }, enabled = !state.busy) {
                Text("${language(model.pair.source)} → ${language(model.pair.target)}")
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = { reader.translatedReading(false) }) { Text(if (!state.translated) "✓ Original" else "Original") }
        TextButton(onClick = { reader.translatedReading(true) }, enabled = state.model != null && state.installed && !state.busy) {
            Text(if (state.translated) "✓ Translated" else "Translated")
        }
    }
    state.model?.let { model ->
        Text(if (state.installed) "Model installed · available offline" else "Model required · approximately ${(model.bytes + 1_048_575) / 1_048_576} MiB", style = MaterialTheme.typography.caption)
        if (!state.installed) TextButton(onClick = translation::install, enabled = !state.busy) { Text("Download model") }
        else TextButton(onClick = translation::remove, enabled = !state.busy) { Text("Delete model") }
    }
    if (state.busy) {
        if (state.total > 0) LinearProgressIndicator((state.downloaded.toFloat() / state.total).coerceIn(0f, 1f), Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = translation::cancel) { Text("Cancel") }
    }
    state.message?.let {
        Text(it, color = MaterialTheme.colors.error)
        TextButton(onClick = translation::retry, enabled = !state.busy) { Text("Retry") }
    }
    TextButton(onClick = translation::clear, enabled = !state.busy) { Text("Clear saved translations") }
    Text("Model downloads contact Mozilla. Book text stays on this device. Models and Bergamot: MPL-2.0; Foxlet: MIT. Mozilla does not endorse this application.", style = MaterialTheme.typography.caption)
    Text("Corresponding source and licenses: github.com/yinvoke/foxlet-translate/tree/0eff2a15032362115848a496ce1df8ed9fc68650; github.com/mozilla/firefox-translations-models", style = MaterialTheme.typography.caption)
}

@Suppress("DEPRECATION")
@Composable
internal fun EpubSelectionTranslationPanel(translation: EpubTranslationController, maxHeight: Dp) {
    val state by translation.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    EpubControlPanel("Translate selection", maxHeight, translation::dismissSelection) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.model?.let { Text("${language(it.pair.source)} → ${language(it.pair.target)}", style = MaterialTheme.typography.subtitle2) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.selectionResult?.let { result ->
                Text(result)
                TextButton(onClick = { clipboard.setText(AnnotatedString(result)) }) { Text("Copy translation") }
            }
            state.message?.let { Text(it, color = MaterialTheme.colors.error) }
            if (!state.busy && state.selectionResult == null) {
                translation.unavailable?.let { Text(it) }
                Text(if (state.selection.isNullOrBlank()) "Close and select a shorter passage. Original text is unchanged." else "Prepare the language model in reading settings, then retry. Original text is unchanged.", style = MaterialTheme.typography.body2)
                TextButton(onClick = translation::retrySelection, enabled = !state.selection.isNullOrBlank()) { Text("Retry") }
            }
            if (state.busy) TextButton(onClick = translation::cancel) { Text("Cancel") }
        }
    }
}
