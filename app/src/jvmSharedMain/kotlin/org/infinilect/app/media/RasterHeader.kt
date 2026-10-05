// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.media

import java.util.zip.CRC32
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

internal actual suspend fun inspectRaster(bytes: ByteArray, mediaType: String): Pair<Int, Int> =
    rasterDimensions(bytes, mediaType, currentCoroutineContext()[Job])

/** Cheap bounded preflight before any native/JDK pixel allocation. Not a replacement for
 * decoder validation; provider dimensions must match these values before full decoding.
 */
internal fun rasterDimensions(bytes: ByteArray, type: String, job: Job? = null): Pair<Int, Int> {
    job?.ensureActive()
    require(bytes.size.toLong() in 1..RasterPolicy.ENCODED_BYTES)
    fun u(i: Int) = bytes[i].toInt() and 255
    fun word(i: Int) = (u(i) shl 8) or u(i + 1)
    fun integer(i: Int): Int = (u(i) shl 24) or (u(i + 1) shl 16) or (u(i + 2) shl 8) or u(i + 3)
    val dimensions = when (type) {
        "image/png" -> {
            require(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)))
            require(integer(8) == 13 && bytes.copyOfRange(12, 16).decodeToString() == "IHDR")
            require(bytes[24].toInt() == 8 && u(25) in setOf(0, 2, 3, 4, 6) && u(26) == 0 && u(27) == 0 && u(28) in 0..1)
            // Standard static PNG only. Chunk bounds prevent malformed header aliases and APNG.
            var p = 8; var end = false
            while (p <= bytes.size - 12) {
                job?.ensureActive()
                val size = integer(p); require(size >= 0 && size <= bytes.size - p - 12)
                val name = bytes.copyOfRange(p + 4, p + 8).decodeToString()
                // No compressed metadata/profiles, animation, EXIF or unknown decoder extensions.
                require(name in setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND") && (name != "IHDR" || p == 8))
                require(CRC32().apply { update(bytes, p + 4, size + 4) }.value == (integer(p + size + 8).toLong() and 0xffffffffL))
                p += size + 12
                if (name == "IEND") { require(size == 0 && p == bytes.size); end = true; break }
            }
            require(end)
            integer(16) to integer(20)
        }
        "image/jpeg" -> {
            require(bytes.size >= 4 && word(0) == 0xffd8 && word(bytes.size - 2) == 0xffd9)
            var p = 2; var result: Pair<Int, Int>? = null
            var inScan = false; var ended = false; var scans = 0
            while (p < bytes.size) {
                job?.ensureActive()
                if (inScan) {
                    while (p < bytes.size && u(p) != 255) { p++; if (p % 4096 == 0) job?.ensureActive() }
                }
                require(p < bytes.size && u(p++) == 255)
                while (p < bytes.size && u(p) == 255) p++
                require(p < bytes.size); val marker = u(p++)
                if (marker == 0 || marker in 0xd0..0xd7) { require(inScan); continue }
                inScan = false
                if (marker == 0xd9) { require(p == bytes.size); ended = true; break }
                require(marker in setOf(0xc0, 0xc2, 0xc4, 0xda, 0xdb, 0xdd, 0xe0, 0xfe) && p <= bytes.size - 2)
                val length = word(p); require(length >= 2 && length <= bytes.size - p)
                // No publisher profiles/EXIF, even between progressive entropy scans.
                if (marker == 0xe0) require(length >= 16 && bytes.copyOfRange(p + 2, p + 7).contentEquals(byteArrayOf(74,70,73,70,0)))
                if (marker == 0xc0 || marker == 0xc2) {
                    require(length >= 8 && result == null && u(p + 2) == 8 && u(p + 7) in setOf(1, 3))
                    require(length == 8 + 3 * u(p + 7))
                    result = word(p + 5) to word(p + 3)
                }
                if (marker == 0xda) {
                    require(result != null && length >= 6 && ++scans <= 64)
                    inScan = true
                }
                p += length
            }
            require(ended && scans > 0)
            requireNotNull(result)
        }
        else -> error("Unsupported raster")
    }
    require(RasterPolicy.dimensions(dimensions.first, dimensions.second))
    return dimensions
}
