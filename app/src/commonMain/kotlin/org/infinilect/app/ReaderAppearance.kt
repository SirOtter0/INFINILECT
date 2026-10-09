// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

/** Platform presentation only; never a reader/session or preference owner. */
data class ReaderAppearance(val dark: Boolean, val background: Color)

@Composable
internal fun ReaderAppearanceEffect(appearance: ReaderAppearance, change: (ReaderAppearance?) -> Unit) {
    val latestChange by rememberUpdatedState(change)
    DisposableEffect(appearance) {
        latestChange(appearance)
        onDispose { latestChange(null) }
    }
}
