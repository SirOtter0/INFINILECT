// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import org.infinilect.core.PublicationId

/** Source-scoped UI key: Pair<String, String> is Bundle-saveable on Android/JVM. */
internal fun PublicationId.resultKey(): Pair<String, String> = sourceId.value to localId
