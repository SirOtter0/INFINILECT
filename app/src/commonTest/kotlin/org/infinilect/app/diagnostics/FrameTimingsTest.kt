// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

import kotlin.test.*

class FrameTimingsTest {
    @Test fun boundedHistogramReportsDeadlineAndOverflowWithoutKeepingFrames() {
        val timings = FrameTimings()
        assertEquals(0, timings.percentileMs(95))
        repeat(94) { timings.add(8_500_000, 16_666_667, 0) }
        repeat(5) { timings.add(22_000_000, 16_666_667, 0) }
        timings.add(2_000_000_000, 16_666_667, 3)
        assertEquals(100L, timings.frames)
        assertEquals(6L, timings.missedDeadline)
        assertEquals(9, timings.percentileMs(50))
        assertEquals(23, timings.percentileMs(95))
        assertEquals(23, timings.percentileMs(99))
        assertEquals(1001, timings.percentileMs(100))
        assertEquals(1L, timings.over700ms); assertEquals(1L, timings.overflow)
        assertEquals(3L, timings.callbackDrops)
    }
    @Test fun invalidPlatformSamplesDoNotPolluteMetrics() {
        val timings = FrameTimings()
        timings.add(-1, 16_666_667, 9); timings.add(10, -1, 9)
        assertEquals(0L, timings.frames)
        timings.add(8_000_000, 8_000_000, -1)
        assertEquals(0L, timings.missedDeadline); assertEquals(0L, timings.callbackDrops)
        assertFailsWith<IllegalArgumentException> { timings.percentileMs(0) }
    }
}
