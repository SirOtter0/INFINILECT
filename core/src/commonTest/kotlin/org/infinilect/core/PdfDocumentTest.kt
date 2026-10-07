// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.test.*

class PdfDocumentTest {
    @Test fun pageCountIsPositiveAndBounded() {
        for (n in listOf(Int.MIN_VALUE, 0, 2049, Int.MAX_VALUE))
            assertEquals(PdfFailure.LIMIT, assertFailsWith<PdfException> { PdfLimits.pageCount(n) }.failure)
        PdfLimits.pageCount(1); PdfLimits.pageCount(2048)
    }
    @Test fun rejectsHostileGeometry() {
        for ((w,h) in listOf(0.0 to 10.0, -1.0 to 10.0, Double.NaN to 10.0,
            Double.POSITIVE_INFINITY to 10.0, 14401.0 to 14400.0, 9.0 to 1.0, 1.0 to 9.0))
            assertFailsWith<PdfException> { PdfPageGeometry(w,h) }
        PdfPageGeometry(14400.0, 14400.0); PdfPageGeometry(8.0,1.0)
    }
    @Test fun outputChecksBeforeAllocationUseLongArithmetic() {
        for ((w,h) in listOf(0 to 1, 1 to -1, 2049 to 1, 2048 to 2048,
            Int.MAX_VALUE to Int.MAX_VALUE, 1024 to 1025)) assertFailsWith<PdfException> { PdfRenderSize(w,h) }
        PdfRenderSize(1024,1024); PdfRenderSize(2048,512)
    }
    @Test fun fitHonorsAllBoundsAndOwnershipClosesDeterministically() {
        for (page in listOf(PdfPageGeometry(612.0,792.0), PdfPageGeometry(14400.0,14400.0), PdfPageGeometry(8.0,1.0))) {
            val size=page.fit(Int.MAX_VALUE, Int.MAX_VALUE)
            PdfLimits.output(size.width,size.height)
            assertTrue(size.width.toLong()*size.height <= PdfLimits.PIXELS)
        }
        val raster=PdfRaster(PdfRenderSize(1,1),intArrayOf(-1))
        assertEquals(-1,raster.argb.single()); raster.close(); raster.close()
        assertEquals(PdfFailure.CLOSED,assertFailsWith<PdfException> { raster.argb }.failure)
    }
    @Test fun pdfUsesExistingSemanticPageProgress() {
        val id=ReadingProgressId(PublicationId(SourceId("owned"),"digest"),"content",PublicationFormat.PDF)
        val progress=ReadingProgress(id,ReadingLocator.Page("pdf-page-1",1,0.0),0.5,1)
        assertEquals(1,(progress.locator as ReadingLocator.Page).pageIndex)
    }
}
