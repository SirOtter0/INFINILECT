// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui
internal expect fun lastOpenedLabel(epochMillis: Long): String

internal data class HistoryDay(val key: String, val label: String)
internal expect fun historyDay(epochMillis: Long, nowEpochMillis: Long): HistoryDay
