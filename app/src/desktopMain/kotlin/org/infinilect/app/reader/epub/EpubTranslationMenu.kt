// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.ui.Modifier

@OptIn(ExperimentalFoundationApi::class)
internal actual fun Modifier.epubTranslationMenu(enabled: Boolean, translate: () -> Unit): Modifier =
    if (!enabled) this else appendTextContextMenuComponents {
        separator()
        item("infinilect-translate", "Translate") { translate(); close() }
    }
