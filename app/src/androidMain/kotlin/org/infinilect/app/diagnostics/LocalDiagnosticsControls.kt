// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.infinilect.app.ui.*

/** Only mounted by the debuggable Android runtime. Copy is explicit; no upload/share permissions. */
@Suppress("DEPRECATION")
@Composable
fun LocalDiagnosticsControls(diagnostics: AndroidLocalDiagnostics) {
    if (!diagnostics.available) return
    val state by diagnostics.state.collectAsState()
    val strings = LocalAppStrings.current
    var showing by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings[UiText.DEBUG_DIAGNOSTICS], style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
        Text(strings[UiText.DEBUG_DIAGNOSTICS_NOTICE], style = MaterialTheme.typography.body2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(strings[UiText.DEBUG_DIAGNOSTICS_ENABLE], Modifier.weight(1f))
            Switch(state.enabled, diagnostics::setEnabled, enabled = state.ready && !state.busy,
                modifier = Modifier.semantics { contentDescription = strings[UiText.DEBUG_DIAGNOSTICS_ENABLE] })
        }
        if (state.failed) Text(strings[UiText.DEBUG_DIAGNOSTICS_ERROR], color = MaterialTheme.colors.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton({ diagnostics.readSummary(); showing = true }, Modifier.heightIn(min = 48.dp), enabled = state.enabled && !state.busy) {
            Text(strings[UiText.DEBUG_DIAGNOSTICS_VIEW])
        }
    }
    if (showing) Dialog(onDismissRequest = { showing = false }) {
        BoxWithConstraints {
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.heightIn(max = maxHeight * .85f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings[UiText.DEBUG_DIAGNOSTICS], style = MaterialTheme.typography.h6)
                        Text(strings[UiText.DEBUG_DIAGNOSTICS_REPORT_NOTICE], style = MaterialTheme.typography.caption)
                        if (state.failed) Text(strings[UiText.DEBUG_DIAGNOSTICS_ERROR], color = MaterialTheme.colors.error)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(state.summary, style = MaterialTheme.typography.caption)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ clipboard.setText(AnnotatedString(state.summary)) }, Modifier.heightIn(min = 48.dp), enabled = state.summary.isNotEmpty()) { Text(strings[UiText.COPY]) }
                        TextButton({ diagnostics.readSummary() }, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.DEBUG_DIAGNOSTICS_REFRESH]) }
                        TextButton({ showing = false }, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.CLOSE]) }
                    }
                }
            }
        }
    }
}
