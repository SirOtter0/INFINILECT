// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.reader.handlesBack

/** Observe navigation in the composition that installs the platform callback. Reading
 * StateFlow.value through ApplicationSession.handlesBack() is not a snapshot read:
 * a reader-only recomposition can otherwise leave Android's callback disabled.
 */
@Composable
internal fun ApplicationBackHandler(
    opening: StateFlow<OpenPublicationState>,
    destination: StateFlow<Destination>,
    onBack: () -> Unit,
    handler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    val currentOpening by opening.collectAsState()
    val currentDestination by destination.collectAsState()
    handler(currentOpening.handlesBack() || currentDestination != Destination.SEARCH, onBack)
}
