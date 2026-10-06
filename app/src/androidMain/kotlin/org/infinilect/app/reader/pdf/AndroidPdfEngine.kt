// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.nio.file.Path
import org.infinilect.core.*

/** Only the API-21 renderer surface, valid on minSdk 26. Called under the owned IO gate.
 * SecurityException cannot distinguish every password/encryption condition on API 26. */
internal actual fun platformPdfEngine(): PdfEngine = PdfEngine { path -> AndroidPdfEngineDocument(path) }

private class AndroidPdfEngineDocument(path: Path) : PdfEngineDocument {
    private val renderer: PdfRenderer
    override val pages: List<PdfPageGeometry>
    init {
        val descriptor = ParcelFileDescriptor.open(path.toFile(),ParcelFileDescriptor.MODE_READ_ONLY)
        val opened = try { PdfRenderer(descriptor) }
        catch (error: Throwable) {
            descriptor.close()
            throw PdfException(if (error is SecurityException) PdfFailure.ENCRYPTED else PdfFailure.MALFORMED)
        }
        // Successful construction transfers descriptor ownership to PdfRenderer.
        try {
            PdfLimits.pageCount(opened.pageCount)
            pages = List(opened.pageCount) { index ->
                val page = opened.openPage(index)
                try { PdfPageGeometry(page.width.toDouble(),page.height.toDouble()) }
                finally { page.close() }
            }
            renderer = opened
        } catch (error: Throwable) {
            opened.close()
            if (error is PdfException) throw error
            throw PdfException(PdfFailure.MALFORMED)
        }
    }
    override fun render(index: Int, size: PdfRenderSize): PdfRaster {
        if (index !in pages.indices) throw PdfException(PdfFailure.PAGE_INDEX)
        PdfLimits.output(size.width,size.height)
        val page = renderer.openPage(index)
        try {
            val bitmap = Bitmap.createBitmap(size.width,size.height,Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { setScale(size.width / pages[index].widthPoints.toFloat(),size.height / pages[index].heightPoints.toFloat()) }
                page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val pixels = IntArray(size.width * size.height)
                bitmap.getPixels(pixels,0,size.width,0,0,size.width,size.height)
                return PdfRaster(size,pixels)
            } finally { bitmap.recycle() }
        } catch (error: PdfException) { throw error }
        catch (_: Exception) { throw PdfException(PdfFailure.RENDER) }
        finally { page.close() }
    }
    override fun close() { renderer.close() }
}
