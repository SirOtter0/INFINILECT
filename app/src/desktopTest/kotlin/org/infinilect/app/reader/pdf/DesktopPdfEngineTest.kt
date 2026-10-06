// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import java.nio.file.Files
import javax.imageio.ImageIO
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.infinilect.core.*
import kotlin.test.*

class DesktopPdfEngineTest {
    private fun <T> withPdf(bytes:ByteArray,action:(PdfEngineDocument)->T):T {
        val directory=Files.createTempDirectory("pdf-engine-test")
        val file=Files.write(directory.resolve("owned.pdf"),bytes)
        try {
            val document=platformPdfEngine().open(file)
            try { return action(document) } finally { document.close() }
        } finally { Files.deleteIfExists(file);Files.deleteIfExists(directory) }
    }
    @Test fun realSingleAndMultiPageRenderWithoutNativeDependencies() {
        for (count in listOf(1,3)) repeat(3) {
            withPdf(smallPdf(count)) { document ->
                assertEquals(count,document.pages.size)
                val raster=document.render(count-1,document.pages.last().fit(256,256))
                try {
                    PdfLimits.output(raster.size.width,raster.size.height)
                    assertTrue(raster.argb.any { it != -1 }) // Real page content, not a blank placeholder.
                } finally { raster.close() }
            }
        }
    }
    @Test fun fakeMalformedAndTruncatedInputIsControlled() {
        for (bytes in listOf("%PDF definitely not a document".encodeToByteArray(),smallPdf().copyOf(80),byteArrayOf(0,1,2)))
            assertEquals(PdfFailure.MALFORMED,assertFailsWith<PdfException>{withPdf(bytes){}}.failure)
    }
    @Test fun rejectsPageCountAndGeometryBeforeRendering() {
        assertEquals(PdfFailure.LIMIT,assertFailsWith<PdfException>{withPdf(smallPdf(2049)){}}.failure)
        for ((w,h) in listOf(0 to 792,14401 to 792,900 to 100))
            assertEquals(PdfFailure.GEOMETRY,assertFailsWith<PdfException>{withPdf(smallPdf(width=w,height=h)){}}.failure)
        assertFailsWith<PdfException>{withPdf(smallPdf(0)){}}
    }
    @Test fun invalidIndexAndExtremeOutputAreControlled() {
        withPdf(smallPdf()) { document ->
            assertEquals(PdfFailure.PAGE_INDEX,assertFailsWith<PdfException>{document.render(-1,PdfRenderSize(1,1))}.failure)
            assertEquals(PdfFailure.PAGE_INDEX,assertFailsWith<PdfException>{document.render(1,PdfRenderSize(1,1))}.failure)
            val raster=document.render(0,document.pages[0].fit())
            try { assertTrue(raster.argb.size <= PdfLimits.PIXELS) } finally { raster.close() }
        }
    }
    @Test fun passwordsAndEmptyPasswordEncryptionAreRejected() {
        for(password in listOf("secret","")) {
            val file=Files.createTempFile("pdf-password-test",".pdf")
            try {
                PDDocument().use { pdf ->
                    pdf.addPage(PDPage())
                    pdf.protect(StandardProtectionPolicy("owner",password,AccessPermission()))
                    pdf.save(file.toFile())
                }
                assertEquals(PdfFailure.ENCRYPTED,assertFailsWith<PdfException>{platformPdfEngine().open(file)}.failure)
            } finally { Files.deleteIfExists(file) }
        }
    }
    @Test fun missingOptionalCodecsFailInsteadOfDroppingDrawnImages() {
        for((filter,format) in listOf("JBIG2Decode" to "JBIG2","JPXDecode" to "JPEG2000")) {
            if(ImageIO.getImageReadersByFormatName(format).hasNext()) continue
            withPdf(smallPdf(imageFilter=filter)) { document ->
                assertEquals(PdfFailure.CODEC,assertFailsWith<PdfException>{document.render(0,PdfRenderSize(128,128))}.failure)
            }
        }
    }
}
