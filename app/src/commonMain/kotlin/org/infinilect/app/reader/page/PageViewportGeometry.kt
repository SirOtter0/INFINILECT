// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import org.infinilect.core.PageDimensions

internal const val PAGE_SPREAD_GUTTER_DP = 2f
private const val PAN_EDGE_EPSILON_PIXELS = .5f

/** Pixel geometry only; no raster, bitmap, resource or durable reading identity. */
internal data class PageSpreadFit(val pages: List<Size>, val gutter: Float) {
    val size = Size(pages.sumOf { it.width.toDouble() }.toFloat() + gutter, pages.maxOf { it.height })
}

internal fun fitPageSpread(pages: List<PageDimensions>, viewport: Size, gutterPixels: Float): PageSpreadFit {
    require(pages.size in 1..2 && viewport.width >= 0 && viewport.height >= 0)
    if (viewport.width == 0f || viewport.height == 0f) return PageSpreadFit(pages.map { Size.Zero }, 0f)
    val gutter = if (pages.size == 2) gutterPixels.coerceIn(0f, viewport.width / 10) else 0f
    val scale = minOf((viewport.width - gutter) / pages.sumOf { it.width }, viewport.height / pages.maxOf { it.height })
    return PageSpreadFit(pages.map { Size(it.width * scale, it.height * scale) }, gutter)
}

internal fun pagePanBounds(content: Size, viewport: Size, zoom: Float) = Offset(
    max(0f, (content.width * zoom - viewport.width) / 2),
    max(0f, (content.height * zoom - viewport.height) / 2),
)

/** Consume pan first. Reversal consumes existing pager displacement before panning back.
 * Near-edge snapping avoids float jitter without inventing a pannable viewport margin. */
internal fun consumePagePan(pan: Float, delta: Float, bound: Float, overscroll: Float): Pair<Float, Float> {
    if (bound == 0f) return 0f to (overscroll + delta)
    var remaining = delta
    if (overscroll != 0f) {
        val next = overscroll + delta
        if (next * overscroll >= 0f) return pan to next
        remaining = next
    }
    val edge = if (remaining > 0) bound else -bound
    val start = if (abs(pan - edge) <= PAN_EDGE_EPSILON_PIXELS) edge else pan.coerceIn(-bound, bound)
    val target = (start + remaining).coerceIn(-bound, bound)
    return target to (remaining - (target - start))
}

/** One finger's zoomed gesture. Vertical intent stays pan-only for this gesture.
 * Only excess beyond real fitted-content bounds can enter the authoritative pager. */
internal class PageEdgePanGesture(private val slop: Float, private val viewportWidth: Float) {
    private var horizontal = 0f
    private var vertical = 0f
    private var horizontalIntent: Boolean? = null
    var overscroll = 0f
    val horizontalMotion get() = horizontalIntent == true && abs(horizontal) > abs(vertical) * 1.2f
    val canHandoff get() = horizontalMotion && abs(overscroll) > slop
    fun move(pan: Offset, delta: Offset, bounds: Offset): Offset {
        horizontal += delta.x; vertical += delta.y
        if (horizontalIntent == null && max(abs(horizontal), abs(vertical)) > slop)
            horizontalIntent = abs(horizontal) > abs(vertical) * 1.2f
        val step = consumePagePan(pan.x, delta.x, bounds.x, overscroll)
        // An inherited presentation offset survives slop arbitration. Vertical intent
        // cannot move that offset; fresh vertical/slop gestures still have zero excess.
        overscroll = when {
            horizontalIntent == true || horizontalIntent == null && overscroll != 0f -> step.second.coerceIn(-viewportWidth, viewportWidth)
            else -> overscroll
        }
        return Offset(step.first, (pan.y + delta.y).coerceIn(-bounds.y, bounds.y))
    }
}
