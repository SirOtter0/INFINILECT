// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui
internal actual fun lastOpenedLabel(epochMillis: Long): String = "Last opened · " +
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(epochMillis))

internal actual fun historyDay(epochMillis: Long, nowEpochMillis: Long): HistoryDay {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epochMillis))
    }
    return HistoryDay(day.toString(), label)
}
