// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Central double taps leave the lateral navigation zones immediate and unambiguous. */
internal class ComicDoubleTap {
    private var point: Offset? = null
    private var time = 0L
    fun tap(at: Offset, downTime: Long, upTime: Long, minimum: Long, maximum: Long, slop: Float): Boolean {
        val previous = point
        val double = previous != null && downTime - time in minimum..maximum && (at - previous).getDistance() <= slop
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
