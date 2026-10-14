// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.contextmenu.provider.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.CompletableDeferred
import org.infinilect.app.ui.*

/** Public provider only; a newer selection replaces the current menu. */
private class ReaderSelectionMenu : TextContextMenuProvider {
    class Session(val data: TextContextMenuDataProvider, val finished: CompletableDeferred<Unit> = CompletableDeferred())
    var session by mutableStateOf<Session?>(null)
        private set
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val next = Session(dataProvider)
        session?.finished?.complete(Unit); session = next
        try { next.finished.await() } finally { if (session === next) session = null }
    }
    fun close() { session?.finished?.complete(Unit) }
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpubSelectionToolbar(selection: SelectionState, enabled: Boolean, translate: () -> Unit, content: @Composable () -> Unit) {
    val strings = LocalAppStrings.current
    val clipboard = LocalClipboardManager.current
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val provider = remember { ReaderSelectionMenu() }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(provider) { onDispose { provider.close() } }
    CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides provider, LocalTextContextMenuDropdownProvider provides provider) {
        Box(Modifier.onGloballyPositioned { anchor = it }, propagateMinConstraints = true) {
            content()
            val current = provider.session
            val coordinates = anchor
            if (current != null && coordinates != null && coordinates.isAttached) {
                val bounds = current.data.contentBounds(coordinates)
                val point = coordinates.localToWindow(bounds.topLeft)
                val viewport = coordinates.localToWindow(Offset.Zero)
                val viewportSize = coordinates.size
                val position = remember(point, bounds, viewport, viewportSize, gap) { object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                        val left = viewport.x.toInt() + gap; val top = viewport.y.toInt() + gap
                        val right = minOf(windowSize.width, (viewport.x + viewportSize.width).toInt()) - popupContentSize.width - gap
                        val bottom = minOf(windowSize.height, (viewport.y + viewportSize.height).toInt()) - popupContentSize.height - gap
                        val above = (point.y - popupContentSize.height - gap).toInt()
                        val y = if (above >= top) above else (point.y + bounds.height + gap).toInt()
                        return IntOffset((point.x + (bounds.width - popupContentSize.width) / 2).toInt().coerceIn(left, right.coerceAtLeast(left)), y.coerceIn(top, bottom.coerceAtLeast(top)))
                    }
                } }
                Popup(popupPositionProvider = position, onDismissRequest = provider::close, properties = PopupProperties(focusable = true)) {
                    Surface(shape = MaterialTheme.shapes.medium, elevation = 4.dp) {
                        FlowRow(Modifier.widthIn(max = 360.dp).padding(4.dp), verticalArrangement = Arrangement.Center) {
                            if (enabled) IconButton({ translate(); provider.close() }, Modifier.size(48.dp).semantics { contentDescription = strings[UiText.TRANSLATE] }) {
                                Text("A↔文", modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.subtitle2)
                            }
                            TextButton({ clipboard.setText(AnnotatedString(selection.selectedTexts.joinToString("\n") { it.text })); provider.close() }, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.COPY]) }
                            TextButton({ selection.selectAll() }, Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.SELECT_ALL]) }
                        }
                    }
                }
            }
        }
    }
}
