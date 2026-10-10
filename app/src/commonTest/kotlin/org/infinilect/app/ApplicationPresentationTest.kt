// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.infinilect.app.ui.*
import org.infinilect.core.*
import kotlin.test.*

class ApplicationPresentationTest {
    @Test fun onlyKnownFormatsAreDisplayedAndTypeDoesNotInventAFormat() {
        val metadata = PublicationSnapshot(PublicationId(SourceId("source"), "book"), "Book", PublicationType.BOOK).displayPublication()
        assertTrue(metadata.resources.isEmpty())
        assertEquals("Format checked when opening", publicationFormat(emptyList()))
        assertEquals("EPUB · TEXT", publicationFormat(listOf(PublicationFormat.EPUB, PublicationFormat.TEXT, PublicationFormat.EPUB)))
    }
    @Test fun progressSummaryIsTheNewestKnownRecordForTheExactPublication() {
        val id = PublicationId(SourceId("source"), "book")
        val record = ReadingProgress(ReadingProgressId(id, "text", PublicationFormat.TEXT), ReadingLocator.Text(5,10), .5, 10)
        val other = record.copy(id = record.id.copy(publicationId = id.copy(sourceId = SourceId("other"))), progression = .9, updatedAtEpochMillis = 20)
        assertEquals(record, publicationProgress(listOf(record,other), id))
        assertEquals(record.copy(progression = .7, updatedAtEpochMillis = 30), publicationProgress(listOf(record, record.copy(progression = .7, updatedAtEpochMillis = 30)), id))
        assertNull(publicationProgress(emptyList(),id))
    }
    @Test fun readerOverrideAndReaderExitRestoreCurrentApplicationAppearance() {
        val light = ReaderAppearance(false, Color.White); val dark = ReaderAppearance(true, Color.Black)
        assertEquals(dark, resolveSystemAppearance(dark, null))
        assertEquals(light, resolveSystemAppearance(dark, light))
        assertEquals(dark, resolveSystemAppearance(light, dark))
        assertEquals(light, resolveSystemAppearance(light, null))
    }
    @Test fun navigationUsesTheSameFourDestinationsInCompactAndWideWindows() {
        assertEquals(listOf(Destination.HOME, Destination.LIBRARY, Destination.HISTORY, Destination.SEARCH, Destination.SETTINGS), applicationDestinations)
        assertFalse(applicationNavigationWide(360f)); assertFalse(applicationNavigationWide(839f)); assertTrue(applicationNavigationWide(840f))
    }
    @Test fun lightAndDarkPalettesProvideReadableTextAndButtonContrast() {
        fun contrast(a: Color,b: Color): Float { val x=a.luminance();val y=b.luminance();return (maxOf(x,y)+.05f)/(minOf(x,y)+.05f) }
        for (dark in listOf(false,true)) {
            val colors=applicationColors(dark)
            assertTrue(contrast(colors.onSurface,colors.surface)>=4.5f)
            assertTrue(contrast(colors.onBackground,colors.background)>=4.5f)
            assertTrue(contrast(colors.onPrimary,colors.primary)>=4.5f)
            assertTrue(contrast(colors.error,colors.surface)>=4.5f)
        }
    }
}
