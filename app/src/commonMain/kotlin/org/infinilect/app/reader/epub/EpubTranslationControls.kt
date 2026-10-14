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
import org.infinilect.app.ui.*

@Composable
internal fun EpubTranslationSettings(reader: EpubReaderController) {
    val strings = LocalAppStrings.current
    val translation = reader.translation
    val state by translation.state.collectAsState()
    LaunchedEffect(translation) { translation.refresh() }
    Divider()
    Text(strings[UiText.READING_LANGUAGE], style = MaterialTheme.typography.subtitle2)
    translation.unavailable?.let { Text(strings.message(it), style = MaterialTheme.typography.body2); return }
    Text(strings.format(UiText.METADATA_LANGUAGE, reader.document.metadata.languages.joinToString().ifBlank { strings[UiText.NOT_PROVIDED] }), style = MaterialTheme.typography.caption)
    if (epubTranslationLanguage(reader.document.metadata.languages.firstOrNull()) != null)
        TextButton(reader::translationMetadata, enabled = !state.busy) { Text(strings[UiText.USE_METADATA]) }
    TextButton(reader::translationDefault, enabled = !state.busy) { Text(strings[UiText.USE_DEFAULT]) }
    if (state.model == null) Text(strings[UiText.NO_PAIR], style = MaterialTheme.typography.caption)
    translation.service?.engine?.models?.forEach { model ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            RadioButton(state.model == model, { reader.translationModel(model) }, enabled = !state.busy)
            TextButton({ reader.translationModel(model) }, enabled = !state.busy) {
                Text("${strings.language(model.pair.source)} → ${strings.language(model.pair.target)}")
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        TextButton({ reader.translatedReading(false) }) { Text((if (!state.translated) "✓ " else "") + strings[UiText.ORIGINAL]) }
        TextButton({ reader.translatedReading(true) }, enabled = state.model != null && !state.busy) {
            Text((if (state.translated) "✓ " else "") + strings[UiText.TRANSLATED])
        }
    }
    if (state.installed) Text(strings[UiText.MODEL_OFFLINE], style = MaterialTheme.typography.caption)
    Text(strings[UiText.MANAGE_MODELS], style = MaterialTheme.typography.caption)
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(translation::cancel) { Text(strings[UiText.CANCEL]) }
    }
    state.message?.let {
        Text(strings.message(it), color = MaterialTheme.colors.error)
        TextButton(translation::retry, enabled = !state.busy) { Text(strings[UiText.RETRY]) }
    }
    TextButton(translation::clear, enabled = !state.busy) { Text(strings[UiText.CLEAR_TRANSLATIONS]) }
    Text(strings[UiText.PRIVACY_MODELS], style = MaterialTheme.typography.caption)
}

@Composable
internal fun EpubDownloadPrompt(reader: EpubReaderController) {
    val strings = LocalAppStrings.current
    val state by reader.translation.state.collectAsState()
    val model = state.requiredModel ?: return
    val cancel = { reader.translatedReading(false); reader.translation.dismissSelection() }
    AlertDialog(onDismissRequest = cancel,
        title = { Text(strings[UiText.MODEL_REQUIRED]) },
        text = { Text(strings.format(UiText.MODEL_DOWNLOAD_QUESTION, strings.language(model.pair.source), strings.language(model.pair.target), (model.bytes + 1_048_575) / 1_048_576)) },
        confirmButton = { TextButton(reader.translation::install) { Text(strings[UiText.DOWNLOAD]) } },
        dismissButton = { TextButton(cancel) { Text(strings[UiText.KEEP_ORIGINAL]) } })
}

@Suppress("DEPRECATION")
@Composable
internal fun EpubSelectionTranslationPanel(translation: EpubTranslationController, maxHeight: Dp) {
    val strings = LocalAppStrings.current
    val state by translation.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    EpubControlPanel(strings[UiText.TRANSLATION_SELECTION], maxHeight, translation::dismissSelection) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.model?.let { Text("${strings.language(it.pair.source)} → ${strings.language(it.pair.target)}", style = MaterialTheme.typography.subtitle2) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.selectionResult?.let { result ->
                Text(result)
                TextButton({ clipboard.setText(AnnotatedString(result)) }) { Text(strings[UiText.COPY_TRANSLATION]) }
            }
            state.message?.let { Text(strings.message(it), color = MaterialTheme.colors.error) }
            if (!state.busy && state.selectionResult == null) {
                Text(strings[if (state.selection.isNullOrBlank()) UiText.SHORTER_SELECTION else UiText.PREPARE_MODEL], style = MaterialTheme.typography.body2)
                TextButton(translation::retrySelection, enabled = !state.selection.isNullOrBlank()) { Text(strings[UiText.RETRY]) }
            }
            if (state.busy) TextButton(translation::cancel) { Text(strings[UiText.CANCEL]) }
        }
    }
}
