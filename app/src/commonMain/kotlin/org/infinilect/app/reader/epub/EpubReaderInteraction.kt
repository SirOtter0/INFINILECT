// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

internal enum class EpubReaderPanel { CONTENTS, SETTINGS }
internal enum class EpubTapAction { PREVIOUS, CONTROLS, NEXT }

internal fun epubReaderIsDark(theme: EpubReadingTheme, systemDark: Boolean): Boolean = when (theme) {
    EpubReadingTheme.SYSTEM -> systemDark
    EpubReadingTheme.LIGHT -> false
    EpubReadingTheme.DARK -> true
}

internal fun epubTapAction(fraction: Float): EpubTapAction? = when {
    !fraction.isFinite() || fraction !in 0f..1f -> null
    fraction < .25f -> EpubTapAction.PREVIOUS
    fraction <= .75f -> EpubTapAction.CONTROLS
    else -> EpubTapAction.NEXT
}

/** Presentation-only state: opening chrome never retickets, loads or saves a passage. */
internal class EpubReaderControls {
    var visible by mutableStateOf(false); private set
    var panel by mutableStateOf<EpubReaderPanel?>(null); private set
    fun toggle() { if (panel == null) visible = !visible }
    fun open(panel: EpubReaderPanel) { visible = true; this.panel = panel }
    fun closePanel() { panel = null }
    fun dismiss(): Boolean = when {
        panel != null -> { closePanel(); true }
        visible -> { visible = false; true }
        else -> false
    }
}

/** Only an unconsumed, short, stationary, single-pointer tap may act.
 * Observes the final pass; links, selection and scroll always get first refusal. */
internal class EpubReaderTap(
    private val x: Float, private val y: Float, width: Float, height: Float,
    private val start: Long, private val slop: Float, private val longPressMillis: Long,
) {
    val action = if (width > 0 && height > 0 && y in 0f..height) epubTapAction(x / width) else null
    private var eligible = action != null
    fun observe(x: Float, y: Float, time: Long, consumed: Boolean, pointers: Int): Boolean {
        if (consumed || pointers != 1 || time < start || time - start >= longPressMillis ||
            (x - this.x) * (x - this.x) + (y - this.y) * (y - this.y) > slop * slop) eligible = false
        return eligible
    }
}

internal fun Modifier.epubReaderTap(onTap: (EpubTapAction) -> Unit): Modifier = composed {
    val latestTap by rememberUpdatedState(onTap)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            val tap = EpubReaderTap(down.position.x, down.position.y, size.width.toFloat(), size.height.toFloat(),
                down.uptimeMillis, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
            tap.observe(down.position.x, down.position.y, down.uptimeMillis, down.isConsumed, 1)
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val eligible = tap.observe(change.position.x, change.position.y, change.uptimeMillis,
                    event.changes.any { it.isConsumed }, event.changes.size)
                if (!change.pressed && eligible) tap.action?.let(latestTap)
            } while (event.changes.any { it.pressed })
        }
    }
}

/** Opaque chrome consumes its blank/disabled areas too, after children get input. */
internal fun Modifier.epubTapBarrier(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}
