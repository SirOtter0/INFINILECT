// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import java.nio.file.Path
import javax.imageio.ImageIO
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.pdfparser.PDFParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.rendering.*
import org.infinilect.core.*

/** File-backed buffered random access, strict parsing, no mapping or full-source byte array.
 * All PDFBox/Java2D state lives here and is used under FilePdfPreparer's IO gate. */
internal actual fun platformPdfEngine(): PdfEngine = PdfEngine { path -> DesktopPdfEngineDocument(path) }

private class DesktopPdfEngineDocument(path: Path) : PdfEngineDocument {
    private val document: PDDocument
    private val renderer: PDFRenderer
    override val pages: List<PdfPageGeometry>
    init {
        val input = RandomAccessReadBufferedFile(path.toFile())
        val opened = try { PDFParser(input).parse(false) }
        catch (error: Throwable) {
            input.close()
            throw PdfException(if (error is InvalidPasswordException) PdfFailure.ENCRYPTED else PdfFailure.MALFORMED)
        }
        try {
            // No password UI, including documents decryptable with an empty password.
            if (opened.isEncrypted) throw PdfException(PdfFailure.ENCRYPTED)
            PdfLimits.pageCount(opened.numberOfPages)
            pages = List(opened.numberOfPages) { index ->
                val page = opened.getPage(index)
                val box = page.cropBox
                val width = box.width.toDouble(); val height = box.height.toDouble()
                PdfLimits.geometry(width,height)
                val unit = page.userUnit.toDouble()
                if (!unit.isFinite() || unit <= 0) throw PdfException(PdfFailure.GEOMETRY)
                PdfLimits.geometry(width * unit,height * unit)
                if (!box.lowerLeftX.isFinite() || !box.lowerLeftY.isFinite() ||
                    !box.upperRightX.isFinite() || !box.upperRightY.isFinite()) throw PdfException(PdfFailure.GEOMETRY)
                val rotated = page.rotation == 90 || page.rotation == 270
                PdfPageGeometry((if (rotated) height else width) * unit,(if (rotated) width else height) * unit)
            }
            renderer = object : PDFRenderer(opened) {
                override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer =
                    object : PageDrawer(parameters) {
                        init { setAnnotationFilter { false } }
                        override fun drawImage(image: PDImage) {
                            // PDFBox otherwise logs absent optional codecs and can omit an image.
                            checkImageCodecs(image.cosObject)
                            super.drawImage(image)
                        }
                    }
            }.apply { isSubsamplingAllowed = true; setAnnotationsFilter { false } }
            document = opened
        } catch (error: Throwable) {
            opened.close()
            if (error is PdfException) throw error
            throw PdfException(PdfFailure.MALFORMED)
        }
    }
    override fun render(index: Int, size: PdfRenderSize): PdfRaster {
        if (index !in pages.indices) throw PdfException(PdfFailure.PAGE_INDEX)
        PdfLimits.output(size.width,size.height)
        val geometry = pages[index]
        // Round scale down; PDFBox floors width*scale and height*scale before allocation.
        val page = document.getPage(index)
        val requestedScale = (minOf(size.width / geometry.widthPoints,size.height / geometry.heightPoints) * page.userUnit).toFloat()
        if (!requestedScale.isFinite() || requestedScale <= 0) throw PdfException(PdfFailure.GEOMETRY)
        val scale = Math.nextDown(requestedScale)
        val width = maxOf(1,kotlin.math.floor((page.cropBox.width * scale).toDouble()).toInt())
        val height = maxOf(1,kotlin.math.floor((page.cropBox.height * scale).toDouble()).toInt())
        PdfLimits.output(width,height) // Match PDFBox's allocation arithmetic before calling it.
        val image = try { renderer.renderImage(index,scale,ImageType.RGB,RenderDestination.VIEW) }
        catch (error: PdfException) { throw error }
        catch (_: Exception) { throw PdfException(PdfFailure.RENDER) }
        try {
            PdfLimits.output(image.width,image.height)
            if (image.width > size.width || image.height > size.height) throw PdfException(PdfFailure.RENDER)
            val actual = PdfRenderSize(image.width,image.height)
            return PdfRaster(actual,image.getRGB(0,0,image.width,image.height,null,0,image.width))
        } finally { image.flush() }
    }
    override fun close() { document.close() }
}

/** Include image masks: PDFBox can decode them inside getImage without drawImage callbacks. */
private fun checkImageCodecs(image: COSDictionary) {
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<COSDictionary,Boolean>())
    fun inspect(dictionary:COSDictionary,depth:Int) {
        if (depth > 16 || visited.size >= 32) throw PdfException(PdfFailure.LIMIT)
        if (!visited.add(dictionary)) throw PdfException(PdfFailure.MALFORMED)
        val filter=dictionary.getDictionaryObject(COSName.FILTER)
        val names=when(filter) {
            is COSName -> listOf(filter)
            is COSArray -> {
                if (filter.size() > 16) throw PdfException(PdfFailure.LIMIT)
                (0 until filter.size()).map { filter.getObject(it) }
            }
            else -> emptyList()
        }
        if (COSName.JBIG2_DECODE in names && !ImageIO.getImageReadersByFormatName("JBIG2").hasNext() ||
            COSName.JPX_DECODE in names && !ImageIO.getImageReadersByFormatName("JPEG2000").hasNext())
            throw PdfException(PdfFailure.CODEC)
        for (key in listOf(COSName.MASK,COSName.SMASK))
            (dictionary.getDictionaryObject(key) as? COSDictionary)?.let { inspect(it,depth+1) }
    }
    inspect(image,0)
}
