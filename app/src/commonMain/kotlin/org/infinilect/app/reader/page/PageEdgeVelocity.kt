// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

/** Recent measured pager movement, not total finger/pan velocity. Supports sparse
 * down/up streams without extrapolating displacement or depending on a platform fit.
 * At most eight scalar samples; no artwork, jobs or publication ownership. */
internal class PageEdgeVelocity(timeMillis: Long, offset: Float = 0f) {
    private data class Sample(val time: Long, val offset: Float)
    private val samples = ArrayDeque<Sample>().apply { addLast(Sample(timeMillis, offset)) }
    private var direction = 0
    private var lastMotion = timeMillis
    internal val sampleCount get() = samples.size

    fun add(timeMillis: Long, offset: Float) {
        if (!offset.isFinite()) return
        val previous = samples.last()
        if (timeMillis < previous.time) return
        val delta = offset - previous.offset
        val nextDirection = when { delta > 0 -> 1; delta < 0 -> -1; else -> 0 }
        if (offset == 0f) {
            // Pan-only time must not dilute a later edge fling.
            samples.clear(); samples.addLast(Sample(timeMillis, 0f))
            direction = 0; lastMotion = timeMillis
            return
        }
        if (offset * previous.offset < 0f) {
            samples.clear()
            samples.addLast(Sample(previous.time, 0f))
            direction = 0
        } else if (nextDirection != 0 && direction != 0 && nextDirection != direction) {
            // An inward reversal must not inherit an earlier outward fling.
            samples.clear(); samples.addLast(previous)
        }
        if (nextDirection != 0) { direction = nextDirection; lastMotion = timeMillis }
        if (samples.last().time == timeMillis) samples.removeLast()
        samples.addLast(Sample(timeMillis, offset))
        while (samples.size > 8 || (samples.size > 1 && timeMillis - samples.first().time > 100)) samples.removeFirst()
    }

    fun pixelsPerSecond(): Float {
        val first = samples.first(); val last = samples.last()
        val elapsed = last.time - first.time
        if (samples.size < 2 || elapsed <= 0 || last.offset == 0f || last.time - lastMotion >= 40) return 0f
        return ((last.offset - first.offset) * 1000 / elapsed).takeIf { it.isFinite() } ?: 0f
    }
}
