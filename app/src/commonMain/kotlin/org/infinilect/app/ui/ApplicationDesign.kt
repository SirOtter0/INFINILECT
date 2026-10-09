// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.infinilect.app.ReaderAppearance

internal object AppSpace {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val contentWidth = 1120.dp
}

internal fun applicationColors(dark: Boolean): Colors = if (dark) darkColors(
    primary = Color(0xFF9AD1CC), primaryVariant = Color(0xFF2D6662), secondary = Color(0xFF9AD1CC),
    background = Color(0xFF141B1B), surface = Color(0xFF1D2626),
    onPrimary = Color(0xFF103B38), onSecondary = Color(0xFF103B38),
    onBackground = Color(0xFFE1EBE9), onSurface = Color(0xFFE1EBE9),
    error = Color(0xFFFFB4AB), onError = Color(0xFF5F1413),
) else lightColors(
    primary = Color(0xFF255F5B), primaryVariant = Color(0xFF164A46), secondary = Color(0xFF255F5B),
    background = Color(0xFFF5F7F5), surface = Color(0xFFFFFFFF),
    onPrimary = Color.White, onSecondary = Color.White,
    onBackground = Color(0xFF192B29), onSurface = Color(0xFF192B29),
    error = Color(0xFFAB302B), onError = Color.White,
)

/** Theme applies to application destinations, not content readers. */
@Composable
internal fun ApplicationTheme(mode: ApplicationThemeMode, appearance: (ReaderAppearance) -> Unit = {}, content: @Composable () -> Unit) {
    val dark = mode.isDark(isSystemInDarkTheme())
    val colors = applicationColors(dark)
    val latest by rememberUpdatedState(appearance)
    SideEffect { latest(ReaderAppearance(dark, colors.background)) }
    MaterialTheme(colors = colors, typography = Typography(
        h4 = MaterialTheme.typography.h4.copy(fontSize = 28.sp),
        h6 = MaterialTheme.typography.h6.copy(fontSize = 19.sp),
        body1 = MaterialTheme.typography.body1.copy(fontSize = 16.sp, lineHeight = 24.sp),
        body2 = MaterialTheme.typography.body2.copy(fontSize = 14.sp, lineHeight = 20.sp),
        button = MaterialTheme.typography.button.copy(fontSize = 14.sp),
    ), shapes = Shapes(RoundedCornerShape(10.dp), RoundedCornerShape(16.dp), RoundedCornerShape(24.dp))) {
        Surface(Modifier.fillMaxSize(), color = colors.background, content = content)
    }
}

@Composable
internal fun ScreenHeading(title: String, description: String, trailing: (@Composable () -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.small)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.h4, modifier = Modifier.weight(1f).semantics { heading() })
            trailing?.invoke()
        }
        Text(description, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onBackground.copy(alpha = .75f))
    }
}

@Composable
internal fun FeedbackCard(title: String, message: String, busy: Boolean = false, error: Boolean = false,
    action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (error) MaterialTheme.colors.error.copy(alpha = .09f) else MaterialTheme.colors.surface,
        elevation = 0.dp) {
        Column(Modifier.padding(AppSpace.medium), verticalArrangement = Arrangement.spacedBy(AppSpace.small)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(title, style = MaterialTheme.typography.subtitle1, modifier = Modifier.semantics { heading() },
                color = if (error) MaterialTheme.colors.error else MaterialTheme.colors.onSurface)
            Text(message, style = MaterialTheme.typography.body2)
            if (action != null) TextButton(onAction, Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}
