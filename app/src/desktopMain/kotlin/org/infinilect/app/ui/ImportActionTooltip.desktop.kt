// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package org.infinilect.app.ui
import androidx.compose.foundation.TooltipArea
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal actual fun ImportActionTooltip(content: @Composable () -> Unit) {
    TooltipArea(tooltip = {
        Surface(shape = MaterialTheme.shapes.small) { Text("Import local file", Modifier.padding(8.dp)) }
    }, content = content)
}
