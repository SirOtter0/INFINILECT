// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import java.time.*
import java.text.DateFormat
import java.util.Date
import kotlin.test.*

class HistoryDayTest {
    private val zone = ZoneId.systemDefault()
    private fun epoch(day: LocalDate, hour: Int = 12) = day.atTime(hour,0).atZone(zone).toInstant().toEpochMilli()
    @Test fun todayAndYesterdayUseActualLocalCalendarDates() {
        val day = LocalDate.of(2026,10,9); val now = epoch(day,1)
        assertEquals(HistoryDay("2026-10-09","Today"), historyDay(epoch(day,0),now))
        assertEquals(HistoryDay("2026-10-08","Yesterday"), historyDay(epoch(day.minusDays(1),23),now))
    }
    @Test fun olderDatesUseLocalizedFormatterAndStableCalendarKey() {
        val day = LocalDate.of(2026,9,5); val timestamp = epoch(day)
        assertEquals(HistoryDay("2026-09-05",DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))),historyDay(timestamp,epoch(day.plusDays(30))))
    }
    @Test fun futureTimestampIsNotFabricatedAsToday() {
        val day = LocalDate.of(2026,10,9)
        assertEquals("2026-10-10",historyDay(epoch(day.plusDays(1)),epoch(day)).key)
        assertNotEquals("Today",historyDay(epoch(day.plusDays(1)),epoch(day)).label)
    }
}
