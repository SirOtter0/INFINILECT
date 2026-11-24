// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
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
    @Test fun declaresBoundedImageGeometryBeforeDecodingResources() {
        fun pdfWithImageResource(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { output ->
            PDDocument().use { pdf ->
                val page = PDPage()
                val image = LosslessFactory.createFromImage(pdf, BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))
                image.cosObject.setInt(COSName.WIDTH, width)
                image.cosObject.setInt(COSName.HEIGHT, height)
                // No page operation draws this resource. The fixture only exercises metadata preflight.
                page.resources = PDResources().also { it.add(image) }
                pdf.addPage(page)
                pdf.save(output)
            }
            output.toByteArray()
        }
        withPdf(pdfWithImageResource(1, 1)) { document ->
            document.render(0, PdfRenderSize(128, 128)).close()
        }
        for ((width, height) in listOf(5_000 to 5_001, 16_385 to 1, 0 to 1)) {
            withPdf(pdfWithImageResource(width, height)) { document ->
                assertEquals(PdfFailure.LIMIT, assertFailsWith<PdfException> {
                    document.render(0, PdfRenderSize(128, 128)).close()
                }.failure)
            }
        }
    }

    @Test fun missingOptionalCodecsFailInsteadOfDroppingDrawnImages() {
        for((filter,format) in listOf("JBIG2Decode" to "JBIG2","JPXDecode" to "JPEG2000")) {
            if(ImageIO.getImageReadersByFormatName(format).hasNext()) continue
            for(explicitColor in listOf(true,false)) withPdf(smallPdf(imageFilter=filter,explicitImageColor=explicitColor)) { document ->
                assertEquals(PdfFailure.CODEC,assertFailsWith<PdfException>{document.render(0,PdfRenderSize(128,128))}.failure)
            }
        }
    }
}
