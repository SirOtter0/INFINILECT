// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import java.util.concurrent.atomic.AtomicLong

// Ordering across recreated owners in one process, even if the wall clock moves back.
private val lastProgressTime = AtomicLong(0)
internal fun progressTime(): Long = lastProgressTime.updateAndGet { previous ->
    maxOf(System.currentTimeMillis(), if (previous == Long.MAX_VALUE) previous else previous + 1)
}
