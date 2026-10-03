// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import org.infinilect.core.ReadingLocator
import org.infinilect.core.ReadingProgress
import kotlin.math.roundToInt

/** Sparse Unicode index. UTF-16 indexes exist only at the layout boundary, never on disk.
 * A surrogate pair is one code point. Layout positions inside a pair round down.
 * Sparse checkpoints bound each conversion to 256 code points, avoiding full rescans.
 */
internal class TextLocations(private val text: String) {
    private val checkpoints: IntArray
    val codePoints: Int
    init {
        val offsets = mutableListOf(0)
        var index = 0
        var count = 0
        while (index < text.length) {
            index += width(index)
            count++
            if (count % 256 == 0) offsets += index
        }
        checkpoints = offsets.toIntArray()
        codePoints = count
    }
    private fun width(index: Int): Int = if (text[index].isHighSurrogate() &&
        index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1

    fun utf16Offset(codePointOffset: Long): Int {
        val count = codePointOffset.coerceIn(0, codePoints.toLong()).toInt()
        var codePoint = count / 256 * 256
        var index = checkpoints[count / 256]
        while (codePoint++ < count) index += width(index)
        return index
    }
    fun locator(utf16Offset: Int): ReadingLocator.Text {
        val offset = utf16Offset.coerceIn(0, text.length)
        var low = 0
        var high = checkpoints.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (checkpoints[middle] <= offset) low = middle else high = middle - 1
        }
        var index = checkpoints[low]
        var count = low * 256
        while (index < offset) {
            val next = index + width(index)
            if (next > offset) break
            index = next; count++
        }
        return ReadingLocator.Text(count.toLong(), codePoints.toLong())
    }
    fun progression(locator: ReadingLocator.Text): Double = if (codePoints == 0) 0.0
        else (locator.codePointOffset.toDouble() / codePoints).coerceIn(0.0, 1.0)

    fun restore(progress: ReadingProgress?): Int {
        val location = progress?.locator as? ReadingLocator.Text ?: return 0
        val offset = if (location.documentCodePoints == codePoints.toLong())
            location.codePointOffset.coerceIn(0, codePoints.toLong())
        else (progress.progression * codePoints).roundToInt().coerceIn(0, codePoints).toLong()
        return utf16Offset(offset)
    }
}

/** Transient current-layout mapping only; the persistent value remains a text offset. */
internal fun restoreScrollTop(
    utf16Offset: Int, maxScroll: Int,
    lineForOffset: (Int) -> Int, lineTop: (Int) -> Float,
): Int = lineTop(lineForOffset(utf16Offset)).toInt().coerceIn(0, maxScroll.coerceAtLeast(0))

internal fun visibleTextOffset(
    scrollY: Int, maxScroll: Int, textLength: Int,
    lineForY: (Float) -> Int, lineStart: (Int) -> Int,
): Int = if (maxScroll > 0 && scrollY >= maxScroll) textLength
    else lineStart(lineForY(scrollY.coerceAtLeast(0).toFloat())).coerceIn(0, textLength)
