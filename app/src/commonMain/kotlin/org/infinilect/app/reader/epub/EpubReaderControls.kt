// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal fun epubReaderColors(dark: Boolean): Colors = if (dark) darkColors(
    primary = Color(0xFFA2CCD9), secondary = Color(0xFFA2CCD9),
    background = Color(0xFF171A1D), surface = Color(0xFF171A1D),
    onBackground = Color(0xFFE3E6E8), onSurface = Color(0xFFE3E6E8),
) else lightColors(
    primary = Color(0xFF365A69), secondary = Color(0xFF365A69),
    background = Color(0xFFF8F7F4), surface = Color(0xFFF8F7F4),
    onBackground = Color(0xFF252823), onSurface = Color(0xFF252823),
)

internal enum class EpubControlIcon { BACK, CONTENTS, SETTINGS, CLOSE }

/** Small vectors drawn in place: no icon dependency, bitmap or image-provider ownership. */
@Composable
internal fun EpubIconButton(label: String, icon: EpubControlIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = label }) {
        if (icon == EpubControlIcon.SETTINGS) Text("Aa", style = MaterialTheme.typography.subtitle1,
            modifier = Modifier.clearAndSetSemantics {})
        else {
            val color = LocalContentColor.current
            Canvas(Modifier.size(24.dp)) {
                fun line(x: Float, y: Float, ex: Float, ey: Float) = drawLine(color,
                    Offset(size.width * x / 24, size.height * y / 24), Offset(size.width * ex / 24, size.height * ey / 24),
                    strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                when (icon) {
                    EpubControlIcon.BACK -> { line(19f,12f,5f,12f); line(5f,12f,11f,6f); line(5f,12f,11f,18f) }
                    EpubControlIcon.CONTENTS -> { line(5f,6f,19f,6f); line(5f,12f,19f,12f); line(5f,18f,15f,18f) }
                    EpubControlIcon.CLOSE -> { line(6f,6f,18f,18f); line(6f,18f,18f,6f) }
                    EpubControlIcon.SETTINGS -> Unit
                }
            }
        }
    }
}

@Composable
internal fun EpubNavigationBar(
    chapter: Int, chapters: Int, progression: Double, previous: Boolean, next: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onContents: () -> Unit, onSettings: () -> Unit,
    contentsFocus: FocusRequester, settingsFocus: FocusRequester, modifier: Modifier = Modifier,
) {
    Surface(modifier.widthIn(max = 760.dp).fillMaxWidth(), elevation = 0.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = .12f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onPrevious, enabled = previous, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous") }
                EpubIconButton("Contents", EpubControlIcon.CONTENTS, onContents, Modifier.focusRequester(contentsFocus))
                EpubIconButton("Settings", EpubControlIcon.SETTINGS, onSettings, Modifier.focusRequester(settingsFocus))
                TextButton(onNext, enabled = next, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Section $chapter of $chapters", style = MaterialTheme.typography.caption)
                Text("${epubProgressPercent(progression)}% of book", style = MaterialTheme.typography.caption)
            }
            LinearProgressIndicator(progress = progression.toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(2.dp),
                backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = .08f))
        }
    }
}

internal fun epubProgressPercent(progression: Double): Int = (progression.coerceIn(0.0, 1.0) * 100).toInt()

/** Native modal focus/dismissal, constrained to the actual reader viewport on both platforms. */
@Composable
internal fun EpubControlPanel(title: String, maxHeight: Dp, close: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val closeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(.92f).heightIn(max = (maxHeight - 32.dp).coerceAtLeast(120.dp))
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false },
            shape = MaterialTheme.shapes.medium, elevation = 0.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, modifier = Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.h6)
                    EpubIconButton("Close $title", EpubControlIcon.CLOSE, close, Modifier.focusRequester(closeFocus))
                }
                body()
                TextButton(close, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("Done") }
            }
        }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
    }
}

@Composable
internal fun EpubContentsPanel(reader: EpubReaderController, current: EpubReaderState.Ready?, maxHeight: Dp, close: () -> Unit) {
    val entries = remember(reader) {
        reader.toc.ifEmpty { reader.document.spine.mapIndexed { index, spine ->
            EpubTocEntry("Chapter ${index + 1}", EpubTarget(reader.document.manifest.single { it.id == spine.itemId }.path), 0)
        } }
    }
    EpubControlPanel("Contents", maxHeight, close) {
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            items(entries) { entry ->
                TextButton(onClick = { close(); reader.navigate(entry.target) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .padding(start = (entry.depth.coerceAtMost(8) * 12).dp).semantics {
                        if (entry.target.path == current?.chapter?.path) stateDescription = "In current chapter"
                    }) {
                    Text(entry.label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.body2)
                }
            }
        }
    }
}

@Composable
internal fun EpubSettingsPanel(settings: EpubReaderSettings, change: (EpubReaderSettings) -> Unit, maxHeight: Dp,
    publicationActions: (@Composable () -> Unit)?, close: () -> Unit) {
    EpubControlPanel("Reading settings", maxHeight, close) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EpubSettingStep("Font size", "${settings.fontSize}", settings.fontSize > 14, settings.fontSize < 30,
                { change(settings.copy(fontSize = (settings.fontSize - 2).coerceAtLeast(14))) }, { change(settings.copy(fontSize = (settings.fontSize + 2).coerceAtMost(30))) })
            EpubSettingStep("Line spacing", "${settings.lineSpacingPercent}%", settings.lineSpacingPercent > 120, settings.lineSpacingPercent < 200,
                { change(settings.copy(lineSpacingPercent = (settings.lineSpacingPercent - 10).coerceAtLeast(120))) }, { change(settings.copy(lineSpacingPercent = (settings.lineSpacingPercent + 10).coerceAtMost(200))) })
            EpubSettingStep("Margins", "${settings.margin}", settings.margin > 8, settings.margin < 40,
                { change(settings.copy(margin = (settings.margin - 4).coerceAtLeast(8))) }, { change(settings.copy(margin = (settings.margin + 4).coerceAtMost(40))) })
            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = .12f))
            Text("Appearance", style = MaterialTheme.typography.subtitle2, modifier = Modifier.semantics { heading() })
            Column(Modifier.selectableGroup()) {
                EpubReadingTheme.entries.forEach { theme ->
                    val label = when (theme) { EpubReadingTheme.SYSTEM -> "System"; EpubReadingTheme.LIGHT -> "Light"; EpubReadingTheme.DARK -> "Dark" }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = settings.theme == theme,
                        role = Role.RadioButton, onClick = { change(settings.copy(theme = theme)) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings.theme == theme, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
            Text("Saved for all EPUBs on this device.", style = MaterialTheme.typography.caption)
            publicationActions?.let {
                Divider(color = MaterialTheme.colors.onSurface.copy(alpha = .12f))
                Text("This publication", style = MaterialTheme.typography.subtitle2, modifier = Modifier.semantics { heading() })
                it()
            }
        }
    }
}

@Composable
private fun EpubSettingStep(label: String, value: String, less: Boolean, more: Boolean, decrease: () -> Unit, increase: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.subtitle2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(decrease, enabled = less, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Decrease $label" }) { EpubStepIcon(false) }
            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.body2)
            TextButton(increase, enabled = more, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Increase $label" }) { EpubStepIcon(true) }
        }
    }
}

@Composable
private fun EpubStepIcon(plus: Boolean) {
    val color = LocalContentColor.current.copy(alpha = LocalContentAlpha.current)
    Canvas(Modifier.size(16.dp)) {
        drawLine(color, Offset(2.dp.toPx(), size.height / 2), Offset(size.width - 2.dp.toPx(), size.height / 2), 1.6.dp.toPx(), StrokeCap.Round)
        if (plus) drawLine(color, Offset(size.width / 2, 2.dp.toPx()), Offset(size.width / 2, size.height - 2.dp.toPx()), 1.6.dp.toPx(), StrokeCap.Round)
    }
}
