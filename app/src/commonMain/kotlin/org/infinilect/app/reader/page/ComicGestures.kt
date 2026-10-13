// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

internal const val COMIC_ZOOM_INTENT_HOLD_MILLIS = 120L

/** Brief lateral taps navigate immediately. A held first tap explicitly arms lateral
 * zoom at fit; once zoomed, side taps no longer navigate and ordinary doubles reset. */
internal fun comicZoomTapIntent(action: PageTapAction?, zoom: Float, holdMillis: Long) =
    action == PageTapAction.CONTROLS || zoom > 1f || holdMillis >= COMIC_ZOOM_INTENT_HOLD_MILLIS

/** Hit the displayed, transformed artwork rectangle, not its letterboxed backdrop. */
internal fun comicArtworkContains(at: Offset, content: Size, viewport: Size, zoom: Float, pan: Offset): Boolean {
    val half = Offset(content.width * zoom / 2, content.height * zoom / 2)
    val center = Offset(viewport.width / 2, viewport.height / 2) + pan
    return at.x in (center.x - half.x)..(center.x + half.x) && at.y in (center.y - half.y)..(center.y + half.y)
}

/** Candidate is owned by one fitted spread; moved/consumed/multiple-pointer gestures retire it. */
internal class ComicDoubleTap {
    private var point: Offset? = null
    private var time = 0L
    fun matches(at: Offset, downTime: Long, minimum: Long, maximum: Long, slop: Float): Boolean =
        point?.let { downTime - time in minimum..maximum && (at - it).getDistance() <= slop } == true
    fun tap(at: Offset, downTime: Long, upTime: Long, minimum: Long, maximum: Long, slop: Float): Boolean {
        val double = matches(at, downTime, minimum, maximum, slop)
        point = if (double) null else at
        time = upTime
        return double
    }
    fun clear() { point = null }
}

internal data class ComicZoom(val zoom: Float, val pan: Offset)

/** Fitted artwork only: no source pixels, new bitmap or progress identity. */
internal fun comicDoubleTapZoom(zoom: Float, pan: Offset, at: Offset, content: Size, viewport: Size): ComicZoom {
    if (zoom > 1f) return ComicZoom(1f, Offset.Zero)
    val target = 2.5f
    val factor = target / zoom
    val centered = Offset(viewport.width / 2, viewport.height / 2) - at
    val candidate = pan * factor + centered * (factor - 1)
    val bounds = pagePanBounds(content, viewport, target)
    return ComicZoom(target, Offset(candidate.x.coerceIn(-bounds.x, bounds.x), candidate.y.coerceIn(-bounds.y, bounds.y)))
}
