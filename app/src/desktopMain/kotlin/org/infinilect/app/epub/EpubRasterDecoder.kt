// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlinx.coroutines.*
import org.infinilect.app.epub.epubRasterDimensions

internal actual fun defaultEpubRasterDecoder(): EpubRasterDecoder = object : EpubRasterDecoder {
    override suspend fun decode(bytes: ByteArray, mediaType: String): EpubRaster = withContext(Dispatchers.IO) {
        val (width, height) = epubRasterDimensions(bytes, mediaType, currentCoroutineContext().job)
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val candidates = ImageIO.getImageReadersByFormatName(if (mediaType == "image/png") "PNG" else "JPEG")
            check(candidates.hasNext())
            val reader = candidates.next()
            try {
                reader.setInput(input, true, true) // no metadata tree or filesystem-backed ImageIO cache
                require(reader.getWidth(0) == width && reader.getHeight(0) == height)
                currentCoroutineContext().ensureActive()
                val image = reader.read(0)
                try {
                    currentCoroutineContext().ensureActive()
                    require(image.width == width && image.height == height)
                    EpubRaster(width, height, image.getRGB(0, 0, width, height, null, 0, width))
                } finally { image.flush() }
            } finally { reader.dispose() }
        }
    }
}
