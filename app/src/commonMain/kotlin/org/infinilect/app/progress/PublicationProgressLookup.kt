// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import org.infinilect.core.*

/** Bounded read-only summary lookup; never opens content or writes a locator. */
internal interface PublicationProgressLookup {
    suspend fun recentPublications(ids: List<PublicationId>): List<ReadingProgress>
}
