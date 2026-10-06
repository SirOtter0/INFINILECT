// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Limits on inspected metadata and owned output, not a bound on parser working memory. */
object PdfLimits {
    const val SOURCE_BYTES = 32L * 1024 * 1024
    const val PAGES = 2048
    const val POINTS = 14_400.0
    const val ASPECT = 8.0
    const val SIDE = 2048
    const val PIXELS = 1_048_576L
    const val RASTER_BYTES = 4L * 1024 * 1024

    fun pageCount(count: Int) { if (count !in 1..PAGES) throw PdfException(PdfFailure.LIMIT) }
    fun geometry(width: Double, height: Double) {
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 ||
            width > POINTS || height > POINTS || width / height > ASPECT || height / width > ASPECT)
            throw PdfException(PdfFailure.GEOMETRY)
    }
    fun output(width: Int, height: Int) {
        if (width !in 1..SIDE || height !in 1..SIDE || width.toLong() * height > PIXELS ||
            width.toLong() * height * 4 > RASTER_BYTES) throw PdfException(PdfFailure.LIMIT)
    }
}

/** Display geometry in PDF points, after the adapter normalizes page rotation. */
data class PdfPageGeometry(val widthPoints: Double, val heightPoints: Double) {
    init { PdfLimits.geometry(widthPoints, heightPoints) }
    fun fit(maxWidth: Int = PdfLimits.SIDE, maxHeight: Int = PdfLimits.SIDE): PdfRenderSize {
        if (maxWidth <= 0 || maxHeight <= 0) throw PdfException(PdfFailure.LIMIT)
        val ratio = widthPoints / heightPoints
        // Ratio is bounded; avoid width*height underflow for tiny positive geometry.
        val width = min(min(maxWidth.coerceAtMost(PdfLimits.SIDE).toDouble(),
            maxHeight.coerceAtMost(PdfLimits.SIDE) * ratio),sqrt(PdfLimits.PIXELS * ratio))
        return PdfRenderSize(maxOf(1,floor(width).toInt()),maxOf(1,floor(width / ratio).toInt()))
    }
}

data class PdfRenderSize(val width: Int, val height: Int) {
    init { PdfLimits.output(width, height) }
}

/** Exclusively owned sRGB ARGB pixels. Closing releases this owner's array reference.
 * Consumers must finish copying/converting before closing; no engine objects or caches. */
class PdfRaster(val size: PdfRenderSize, pixels: IntArray) {
    private var owned: IntArray? = pixels
    init { require(pixels.size.toLong() == size.width.toLong() * size.height) }
    val argb: IntArray get() = owned ?: throw PdfException(PdfFailure.CLOSED)
    fun close() { owned = null }
}

enum class PdfFailure(val userMessage: String) {
    MALFORMED("This PDF is malformed or unsupported."),
    ENCRYPTED("This PDF requires a password or uses unsupported encryption."),
    LIMIT("This PDF exceeds the supported document or rendering limits."),
    GEOMETRY("This PDF has unsupported page dimensions."),
    PAGE_INDEX("This PDF page is unavailable."),
    CODEC("This PDF page requires an unavailable image codec."),
    STORAGE("The private PDF preparation storage is unavailable."),
    TRANSFER("The PDF resource could not be verified completely."),
    RENDER("This PDF page could not be rendered."),
    CLOSED("This PDF document is closed."),
}
class PdfException(val failure: PdfFailure) : Exception(failure.userMessage)

/** Owned document. Adapters serialize operations and own all descriptors/pages/engine state.
 * renderPage produces one caller-owned raster fitting inside the requested bounded size.
 * close is idempotent and retires the document immediately; native cleanup may wait for
 * an already running operation. There is no claim of interruptible parsing/rendering. */
interface PdfDocument {
    val progressId: ReadingProgressId
    val pages: List<PdfPageGeometry>
    val pageCount: Int get() = pages.size
    suspend fun renderPage(pageIndex: Int, size: PdfRenderSize): PdfRaster
    fun close()
}
