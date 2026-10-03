// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import kotlin.test.*
import org.infinilect.core.*

class TextLocationsTest {
    private val id = ReadingProgressId(PublicationId(SourceId("fixture"),"1"),"text",PublicationFormat.TEXT)
    private fun progress(offset: Long, length: Long, fraction: Double) =
        ReadingProgress(id, ReadingLocator.Text(offset,length),fraction,1)
    @Test fun beginningMiddleEofAndOutOfRangeLayoutOffsets() {
        val text = TextLocations("abcde")
        assertEquals(0L,text.locator(-100).codePointOffset)
        assertEquals(2L,text.locator(2).codePointOffset)
        assertEquals(5L,text.locator(100).codePointOffset)
        assertEquals(0,text.utf16Offset(-100)); assertEquals(5,text.utf16Offset(Long.MAX_VALUE))
    }
    @Test fun emptyDocumentHasZeroProgressionAndSafeRestoration() {
        val text = TextLocations("")
        assertEquals(0.0,text.progression(text.locator(0)))
        assertEquals(0,text.restore(progress(1,2,0.5)))
    }
    @Test fun multibyteTextAndEmojiUseCodePointsNotBytesOrSurrogates() {
        val text = TextLocations("é中😀z")
        assertEquals(4,text.codePoints)
        assertEquals(4,text.utf16Offset(3)); assertEquals(5,text.utf16Offset(4))
        assertEquals(2L,text.locator(3).codePointOffset) // Inside emoji rounds down.
        assertEquals(0.75,text.progression(text.locator(4)))
    }
    @Test fun sparseCheckpointBoundariesRoundTripAcrossSurrogatePairs() {
        val s = "😀é中a".repeat(300)
        val text = TextLocations(s)
        for (i in 0..text.codePoints) assertEquals(i.toLong(),text.locator(text.utf16Offset(i.toLong())).codePointOffset)
    }
    @Test fun staleDocumentLengthRestoresByNormalizedRegion() {
        val text = TextLocations("a".repeat(100))
        assertEquals(50,text.restore(progress(10,20,0.5)))
        assertEquals(100,text.restore(progress(20,20,1.0)))
    }
    @Test fun sameLengthRestoresCodePointOffsetRatherThanRoundedPercentage() {
        val text = TextLocations("a".repeat(100))
        assertEquals(43,text.restore(progress(43,100,0.4)))
    }
    @Test fun missingProgressRestoresBeginning() { assertEquals(0,TextLocations("text").restore(null)) }
    @Test fun progressionRemainsClamped() {
        val text = TextLocations("a")
        assertEquals(0.0,text.progression(ReadingLocator.Text(0,1)))
        assertEquals(1.0,text.progression(ReadingLocator.Text(100,100)))
    }
    @Test fun viewportWidthsChangeTransientLinePixelsButNotCanonicalPosition() {
        val text = TextLocations("a".repeat(1000))
        val stored = progress(433,1000,0.433)
        // Synthetic line layouts exercise the production mapping; actual Compose rendering
        // remains a device smoke check. Both restore within one line of the same text region.
        val offset = text.restore(stored)
        for ((width, height) in listOf(20 to 24, 60 to 30)) {
            val y=restoreScrollTop(offset,10000,{ it/width },{ (it*height).toFloat() })
            val logical=visibleTextOffset(y,10000,1000,{ it.toInt()/height },{ it*width })
            assertTrue(offset-logical in 0 until width)
        }
        assertEquals(433,text.restore(stored))
    }
    @Test fun layoutMappingHandlesBeginningEofAndScrollBounds() {
        assertEquals(0,restoreScrollTop(0,100,{0},{0f}))
        assertEquals(100,restoreScrollTop(1000,100,{50},{1000f}))
        assertEquals(1000,visibleTextOffset(100,100,1000,{0},{0}))
        assertEquals(0,visibleTextOffset(0,0,1000,{0},{0})) // Fits viewport: no completion inferred.
        assertEquals(0,visibleTextOffset(-10,100,1000,{0},{-1}))
    }
}
