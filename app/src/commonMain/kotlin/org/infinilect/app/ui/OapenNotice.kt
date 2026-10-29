// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.infinilect.app.discovery.*
import org.infinilect.core.PublicationId

@Composable
internal fun OapenNotice(id: PublicationId? = null) {
    val strings = LocalAppStrings.current
    val browser = LocalUriHandler.current
    var failed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings[if (id == null) UiText.OAPEN_LOOKUP_HELP else UiText.OAPEN_LIMITED], style = MaterialTheme.typography.caption)
        TextButton({
            try { browser.openUri(id?.let(::oapenPage) ?: OAPEN_CATALOG_PAGE); failed = false }
            catch (_: Exception) { failed = true }
        }, Modifier.heightIn(min = 48.dp)) {
            Text(strings[if (id == null) UiText.OAPEN_OPEN_CATALOG else UiText.OAPEN_OPEN_RECORD])
        }
        if (failed) Text(strings[UiText.OAPEN_OPEN_FAILED], color = MaterialTheme.colors.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
