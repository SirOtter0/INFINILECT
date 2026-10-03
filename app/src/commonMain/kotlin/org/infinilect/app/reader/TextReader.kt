// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop

/** Layout pixels are transient: only the visible line's Unicode text position is reported.
 * Restore to that line's top in the current layout; no promise of pixel precision.
 */
@Composable
internal fun TextReader(
    document: TextDocument,
    reading: TextReadingProgress?,
    saveFailed: Boolean,
    onBack: () -> Unit,
) {
    val scroll = rememberScrollState()
    var layout by remember(document) { mutableStateOf<TextLayoutResult?>(null) }
    val offset by (reading?.utf16Offset ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0) }).collectAsState()
    LaunchedEffect(document, layout) {
        val current = snapshotFlow { layout }.filterNotNull().first()
        snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
        val target = reading?.utf16Offset?.value ?: 0
        scroll.scrollTo(restoreScrollTop(target.coerceIn(0, document.text.length), scroll.maxValue,
            current::getLineForOffset, current::getLineTop))
        // Do not save opening/restoration as a fresh movement or overwrite an exact
        // stored position with its rounded layout line. Only actual later scrolling reports.
        snapshotFlow { scroll.value }.drop(1).collect { y ->
            val position = visibleTextOffset(y, scroll.maxValue, document.text.length,
                current::getLineForVerticalPosition, current::getLineStart)
            reading?.report(position)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(document.title, style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
            Button(onClick = onBack) { Text("Back to results") }
        }
        if (reading != null) {
            val percent = (document.locations.progression(document.locations.locator(offset)) * 100).toInt()
            Text("$percent% · approximate position", style = MaterialTheme.typography.caption)
        }
        if (saveFailed) Text("Reading position could not be saved on this device.", style = MaterialTheme.typography.caption)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Text(document.text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.body1,
                onTextLayout = { layout = it })
        }
    }
}
