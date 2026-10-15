// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import org.infinilect.app.ui.*
import org.infinilect.core.*
import kotlin.test.*

class ProgressPresentationIndexTest {
    @Test fun indexPreservesLatestFormatRevisionAndFirstTieWithoutWritingProgress() {
        val id = PublicationId(SourceId("original"), "same-title")
        val other = id.copy(sourceId = SourceId("other-source"))
        fun record(book: PublicationId, revision: String, time: Long) = ReadingProgress(
            ReadingProgressId(book, revision, PublicationFormat.TEXT), ReadingLocator.Text(6, 10), .6, time)
        val old = record(id, "v1", 1); val newest = record(id, "v2", 3); val tie = record(id, "v3", 3)
        val foreign = record(other, "v4", 10)
        val values = listOf(old, newest, tie, foreign)
        val indexed = indexPublicationProgress(values)
        assertSame(newest, indexed[id]); assertSame(foreign, indexed[other])
        for (book in listOf(id, other, id.copy(localId = "missing"))) assertSame(publicationProgress(values, book), indexed[book])
        assertEquals(listOf(old, newest, tie, foreign), values)
        assertTrue(indexPublicationProgress(emptyList()).isEmpty())
    }
}
