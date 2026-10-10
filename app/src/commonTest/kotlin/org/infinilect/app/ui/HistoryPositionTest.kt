// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import kotlin.test.*
import org.infinilect.core.*

class HistoryPositionTest {
    private fun record(format: PublicationFormat, locator: ReadingLocator) = ReadingProgress(
        ReadingProgressId(PublicationId(SourceId("original"), "book"), "resource", format), locator, .42, 1)
    @Test fun actualLogicalPageIsOneBasedWithoutInventedTotal() {
        assertEquals("Page 6 · 42% read", historyPosition(record(PublicationFormat.PAGES, ReadingLocator.Page("six", 5, .2))))
        assertEquals("Page 6 · 42% read", historyPosition(record(PublicationFormat.PDF, ReadingLocator.Page("six", 5, .2))))
    }
    @Test fun epubDoesNotTurnElementOrWindowIntoChapterOrPageNumber() {
        assertEquals("42% read", historyPosition(record(PublicationFormat.EPUB, ReadingLocator.Epub(EpubEntryPath("book/chapter.xhtml"), listOf(900, 2), 4, .7))))
    }
    @Test fun textDoesNotInventPagesAndUnknownProgressStaysAbsent() {
        assertEquals("42% read", historyPosition(record(PublicationFormat.TEXT, ReadingLocator.Text(42,100))))
        assertNull(historyPosition(null))
    }
}
