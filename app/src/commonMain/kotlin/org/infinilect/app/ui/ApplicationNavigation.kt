// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.infinilect.app.Destination

internal fun Destination.label(): String = when (this) {
    Destination.HOME -> "Home"; Destination.LIBRARY -> "Library"; Destination.HISTORY -> "History"; Destination.SEARCH -> "Search"; Destination.SETTINGS -> "Settings"
}
internal val applicationDestinations = listOf(Destination.HOME, Destination.LIBRARY, Destination.HISTORY, Destination.SEARCH, Destination.SETTINGS)
internal fun applicationNavigationWide(width: Float) = width >= 840f

private fun Destination.labelKey() = when (this) {
    Destination.HOME -> UiText.HOME; Destination.LIBRARY -> UiText.LIBRARY; Destination.HISTORY -> UiText.HISTORY
    Destination.SEARCH -> UiText.SEARCH; Destination.SETTINGS -> UiText.SETTINGS
}

/** Share spare width with long translated labels before resorting to ellipsis.
 * Measure only on locale/geometry/style changes, never on scroll or cover updates. */
@Composable
private fun compactNavigationWeights(width: androidx.compose.ui.unit.Dp): List<Float> {
    val strings = LocalAppStrings.current
    val density = LocalDensity.current
    val style = MaterialTheme.typography.caption.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val measurer = rememberTextMeasurer(cacheSize = 5)
    return remember(width, strings, density, style, measurer) {
        val available = with(density) { width.toPx() }
        val minimum = with(density) { 48.dp.toPx() }
        val padding = with(density) { 8.dp.toPx() } + 2f // Pixel rounding must not truncate a fitting word.
        val desired = applicationDestinations.map { target ->
            (measurer.measure(strings[target.labelKey()], style, softWrap = false, maxLines = 1).size.width + padding).coerceAtLeast(minimum)
        }
        val sum = desired.sum()
        when {
            available <= minimum * desired.size -> List(desired.size) { 1f }
            sum <= available -> desired.map { it + (available - sum) / desired.size }
            else -> desired.map { minimum + (it - minimum) * (available - minimum * desired.size) / (sum - minimum * desired.size) }
        }
    }
}

/** One hierarchy: a labeled bottom bar in compact windows, a labeled rail in wide windows.
 * Alt+1..5 select the same destinations; tab/enter use standard selectable semantics. */
@Composable
internal fun ApplicationShell(destination: Destination, busy: Boolean, navigate: (Destination) -> Unit,
    importAction: (@Composable () -> Unit)? = null, onEscape: () -> Boolean = { false }, content: @Composable () -> Unit) {
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(rootFocus).onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && onEscape()) return@onPreviewKeyEvent true
        val target = when (it.key) { Key.One -> Destination.HOME; Key.Two -> Destination.LIBRARY; Key.Three -> Destination.HISTORY; Key.Four -> Destination.SEARCH; Key.Five -> Destination.SETTINGS; else -> null }
        if (!busy && it.type == KeyEventType.KeyDown && it.isAltPressed && target != null) { navigate(target); true } else false
    }.focusable()) {
        val wide = applicationNavigationWide(maxWidth.value)
        val compactWeights = if (!wide) compactNavigationWeights(maxWidth) else emptyList()
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colors.background) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("INFINILECT", style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f))
                    importAction?.invoke()
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide) Surface(Modifier.width(176.dp).fillMaxHeight(), color = MaterialTheme.colors.background) {
                    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        applicationDestinations.forEach { NavigationItem(it, destination == it, !busy, { navigate(it) }, Modifier.fillMaxWidth(), wide = true) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = AppSpace.contentWidth).fillMaxSize()) { content() }
                }
            }
            if (!wide) Surface(color = MaterialTheme.colors.surface, elevation = 0.dp) {
                Column {
                    Divider(color = MaterialTheme.colors.onSurface.copy(alpha = .10f))
                    Row(Modifier.fillMaxWidth().selectableGroup()) {
                        applicationDestinations.forEachIndexed { index, target ->
                            NavigationItem(target, destination == target, !busy, { navigate(target) }, Modifier.weight(compactWeights[index]))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(target: Destination, selected: Boolean, enabled: Boolean, click: () -> Unit, modifier: Modifier, wide: Boolean = false) {
    val strings = LocalAppStrings.current
    val label = strings[target.labelKey()]
    val color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = if (enabled) .75f else .38f)
    Surface(modifier, shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colors.primary.copy(alpha = .12f) else MaterialTheme.colors.surface.copy(alpha = 0f)) {
        Box(Modifier.heightIn(min = 64.dp).selectable(selected, enabled = enabled, role = Role.Tab, onClick = click)
            .semantics { contentDescription = strings.format(UiText.NAVIGATE_TO, label) }.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DestinationIcon(target,color);Text(label, style = MaterialTheme.typography.button)
            } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DestinationIcon(target,color)
                Text(label, color=color, style=MaterialTheme.typography.caption.copy(fontSize=12.sp, lineHeight=16.sp),
                    textAlign=TextAlign.Center, maxLines=1, softWrap=false, overflow=TextOverflow.Ellipsis)
            }
        }
    }
}
