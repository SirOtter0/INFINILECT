// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

internal enum class PageTapAction { PREVIOUS, CONTROLS, NEXT }

/** Fraction of the actual canvas width; invalid/outside coordinates are ignored. */
internal fun pageTapAction(fraction: Float, mode: PageReadingMode): PageTapAction? {
    if (!fraction.isFinite() || fraction !in 0f..1f) return null
    if (fraction in .3f.. .7f) return PageTapAction.CONTROLS
    // Existing continuous modes keep their scrolling interaction; center still opens chrome.
    if (mode == PageReadingMode.VERTICAL || mode == PageReadingMode.WEBTOON) return null
    val next = if (mode == PageReadingMode.PAGED_RTL) fraction < .3f else fraction > .7f
    return if (next) PageTapAction.NEXT else PageTapAction.PREVIOUS
}

/** Incoming-page offset sign. Only page indices are retained, never outgoing images. */
internal fun pageTransitionSign(previous: Int?, current: Int, mode: PageReadingMode): Int {
    if (previous == null || previous == current) return 0
    val logical = if (current > previous) 1 else -1
    return if (mode == PageReadingMode.PAGED_RTL) -logical else logical
}
