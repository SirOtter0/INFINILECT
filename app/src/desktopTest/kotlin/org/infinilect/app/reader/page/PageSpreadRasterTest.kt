// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.infinilect.app.media.*
import kotlin.test.*

class PageSpreadRasterTest {
    @Test fun pairedSamplingIsOptInAndSourcePreflightRemainsExact() = runBlocking {
        val decoder = defaultRasterDecoder()
        for (format in listOf("PNG", "JPEG")) for ((w, h) in listOf(2 to 2, 7 to 11, 512 to 768)) {
            val mediaType = if (format == "PNG") "image/png" else "image/jpeg"
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val bytes = ByteArrayOutputStream().use { out -> ImageIO.write(image, format, out); out.toByteArray() }
            image.flush()
            assertEquals(w to h, inspectRaster(bytes, mediaType))
            val single = decoder.decode(bytes, mediaType)
            val pair = decoder.decodePage(bytes, mediaType, true)
            assertEquals(w to h, single.width to single.height)
            assertEquals((w + 1) / 2 to (h + 1) / 2, pair.width to pair.height)
            assertTrue(pair.argb.size <= single.argb.size)
            val again = decoder.decodePage(bytes, mediaType, false)
            assertEquals(w to h, again.width to again.height)
        }
    }
    @Test fun pairSamplingDoesNotAdmitOversizedOrMalformedSources() = runBlocking {
        val decoder = defaultRasterDecoder()
        assertFails { decoder.decodePage(byteArrayOf(1, 2, 3), "image/png", true) }
        val image = BufferedImage(2049, 2, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use { out -> ImageIO.write(image, "PNG", out); out.toByteArray() }
        image.flush()
        assertFails { decoder.decodePage(bytes, "image/png", true) }
        Unit
    }
}
