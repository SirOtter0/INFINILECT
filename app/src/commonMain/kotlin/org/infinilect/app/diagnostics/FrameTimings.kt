// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

/** Diagnostic-only accumulator. One reporting thread, fixed memory, no per-frame collections. */
internal class FrameTimings {
    private val milliseconds = LongArray(1001)
    var frames = 0L; private set
    var missedDeadline = 0L; private set
    var over700ms = 0L; private set
    var callbackDrops = 0L; private set
    var overflow = 0L; private set
    fun add(durationNs: Long, deadlineNs: Long, droppedCallbacks: Int) {
        if (durationNs < 0 || deadlineNs <= 0) return
        frames++
        if (durationNs > deadlineNs) missedDeadline++
        if (durationNs > 700_000_000) over700ms++
        callbackDrops += droppedCallbacks.coerceAtLeast(0)
        val ms = durationNs / 1_000_000
        if (ms >= milliseconds.size - 1) overflow++
        milliseconds[ms.coerceAtMost(1000).toInt()]++
    }
    /** Upper bucket edge (1ms resolution); 1001 denotes the >=1000ms overflow bucket. */
    fun percentileMs(percent: Int): Int {
        require(percent in 1..100)
        if (frames == 0L) return 0
        val rank = (frames * percent + 99) / 100
        var count = 0L
        for (i in milliseconds.indices) { count += milliseconds[i]; if (count >= rank) return i + 1 }
        error("Frame histogram is inconsistent")
    }
}
