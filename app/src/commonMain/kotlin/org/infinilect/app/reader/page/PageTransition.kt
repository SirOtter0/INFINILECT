// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlin.math.abs

internal enum class PageTransitionPhase { DRAGGING, WAITING, SETTLING, RETURNING }

/** Only logical identity and viewport-relative position. Never owns pixels or a job. */
internal data class PageTransition(
    val ticket: Long,
    val from: Int,
    val target: Int,
    val offset: Float = 0f,
    val phase: PageTransitionPhase,
    val targetStamp: Long? = null,
)

internal fun pagedMode(mode: PageReadingMode) = mode == PageReadingMode.PAGED_LTR || mode == PageReadingMode.PAGED_RTL

/** Edge containing the incoming page: next is right in LTR and left in RTL. */
internal fun pageIncomingSide(from: Int, target: Int, mode: PageReadingMode): Int {
    if (from == target) return 0
    val logical = if (target > from) 1 else -1
    return if (mode == PageReadingMode.PAGED_RTL) -logical else logical
}

internal fun pageDragTarget(from: Int, offset: Float, mode: PageReadingMode, count: Int): Int {
    if (!offset.isFinite() || offset == 0f || !pagedMode(mode)) return from
    val logical = if ((offset < 0) == (mode == PageReadingMode.PAGED_LTR)) 1 else -1
    return (from + logical).takeIf { it in 0 until count } ?: from
}

/** Distance or a deliberate short fling, with velocity agreeing with displacement.
 * All units are viewports, so thresholds do not depend on screen pixels/density. */
internal fun pageDragCompletes(offset: Float, velocity: Float): Boolean {
    if (!offset.isFinite() || !velocity.isFinite()) return false
    return abs(offset) >= .25f || (abs(offset) >= .05f && abs(velocity) >= .9f && offset * velocity > 0)
}
