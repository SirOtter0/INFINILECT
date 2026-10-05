// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.test.*

class EpubProgressTest {
    private val path = EpubEntryPath("OPS/chapter.xhtml")
    private val id = ReadingProgressId(PublicationId(SourceId("fixture"), "1"), "epub", PublicationFormat.EPUB)
    @Test fun epubLocatorIsTypedAndLayoutIndependent() {
        val locator = ReadingLocator.Epub(path, listOf(0, 3), 7, 0.4)
        assertEquals(locator, ReadingProgress(id, locator, 0.4, 1).locator)
    }
    @Test fun epubCannotMasqueradeAsTextProgress() {
        assertFailsWith<IllegalArgumentException> { ReadingProgress(id.copy(format = PublicationFormat.TEXT), ReadingLocator.Epub(path, listOf(0), 0, 0.0), 0.0, 0) }
    }
    @Test fun elementPathIsBoundedAndNonnegative() {
        for (parts in listOf(emptyList(), listOf(-1), listOf(20_000), List(33) { 0 }))
            assertFailsWith<IllegalArgumentException> { ReadingLocator.Epub(path, parts, 0, 0.0) }
    }
    @Test fun offsetsAndFallbacksAreValidated() {
        assertFailsWith<IllegalArgumentException> { ReadingLocator.Epub(path, listOf(0), -1, 0.0) }
        for (progression in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1))
            assertFailsWith<IllegalArgumentException> { ReadingLocator.Epub(path, listOf(0), 0, progression) }
    }
    @Test fun spineIdentityIsCanonicalNotAnExternalUrl() {
        assertFailsWith<IllegalArgumentException> { EpubEntryPath("file:///book.xhtml") }
        assertFailsWith<IllegalArgumentException> { EpubEntryPath("https://example.org/book.xhtml") }
    }
    @Test fun historicalTextSemanticsAreUnchanged() { assertEquals(ReadingLocator.Text(4, 10), ReadingProgress(id.copy(format = PublicationFormat.TEXT), ReadingLocator.Text(4, 10), 0.4, 1).locator) }
}
