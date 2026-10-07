// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.media

/** Passive raster bounds shared by EPUB illustrations and comic pages. Long multiplication. */
internal object RasterPolicy {
    const val ENCODED_BYTES = 2 * 1024 * 1024
    const val DIMENSION = 2048
    const val PIXELS = 1_048_576
    fun dimensions(width: Int, height: Int) = width in 1..DIMENSION && height in 1..DIMENSION && width.toLong() * height <= PIXELS
}
/** Owned sRGB ARGB presentation payload. No platform/Compose/parser/filesystem types. */
internal class Raster(val width: Int, val height: Int, val argb: IntArray) {
    init { require(RasterPolicy.dimensions(width, height) && argb.size == width * height) }
}
internal interface RasterDecoder {
    suspend fun decode(bytes: ByteArray, mediaType: String): Raster
    /** Page-reader opt-in only. Existing illustration/single-page callers are unchanged. */
    suspend fun decodePage(bytes: ByteArray, mediaType: String, paired: Boolean): Raster = decode(bytes, mediaType)
}
internal expect fun defaultRasterDecoder(): RasterDecoder
/** Header/CRC preflight, before decoder allocation. No native/framework result. */
internal expect suspend fun inspectRaster(bytes: ByteArray, mediaType: String): Pair<Int, Int>
