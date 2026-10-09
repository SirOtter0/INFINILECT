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
import org.infinilect.app.Destination

internal fun Destination.label(): String = when (this) {
    Destination.LIBRARY -> "Library"; Destination.HISTORY -> "History"; Destination.SEARCH -> "Search"; Destination.SETTINGS -> "Settings"
}
internal val applicationDestinations = listOf(Destination.LIBRARY, Destination.HISTORY, Destination.SEARCH, Destination.SETTINGS)
internal fun applicationNavigationWide(width: Float) = width >= 840f

/** One hierarchy: a labeled bottom bar in compact windows, a labeled rail in wide windows.
 * Alt+1..4 select the same destinations; tab/enter use standard selectable semantics. */
@Composable
internal fun ApplicationShell(destination: Destination, busy: Boolean, navigate: (Destination) -> Unit,
    importAction: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(rootFocus).onPreviewKeyEvent {
        val target = when (it.key) { Key.One -> Destination.LIBRARY; Key.Two -> Destination.HISTORY; Key.Three -> Destination.SEARCH; Key.Four -> Destination.SETTINGS; else -> null }
        if (!busy && it.type == KeyEventType.KeyDown && it.isAltPressed && target != null) { navigate(target); true } else false
    }.focusable()) {
        val wide = applicationNavigationWide(maxWidth.value)
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colors.background) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("INFINILECT", style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f))
                    importAction?.invoke()
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide) Surface(Modifier.width(176.dp).fillMaxHeight(), color = MaterialTheme.colors.background) {
                    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        applicationDestinations.forEach { NavigationItem(it, destination == it, !busy, { navigate(it) }, Modifier.fillMaxWidth()) }
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
                        applicationDestinations.forEach { NavigationItem(it, destination == it, !busy, { navigate(it) }, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(target: Destination, selected: Boolean, enabled: Boolean, click: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colors.primary.copy(alpha = .12f) else MaterialTheme.colors.surface.copy(alpha = 0f)) {
        Box(Modifier.heightIn(min = 56.dp).selectable(selected, enabled = enabled, role = Role.Tab, onClick = click)
            .semantics { contentDescription = "Navigate to ${target.label()}" }.padding(horizontal = 4.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center) {
            Text(target.label(), style = MaterialTheme.typography.button,
                color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = if (enabled) .75f else .38f))
        }
    }
}
