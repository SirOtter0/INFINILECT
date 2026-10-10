// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.covers

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlinx.coroutines.*
import org.infinilect.app.media.rasterDimensions

import org.infinilect.app.media.Raster

internal actual suspend fun decodeCoverThumbnail(bytes: ByteArray, mediaType: String): Raster = withContext(Dispatchers.IO) {
        val (width, height) = rasterDimensions(bytes, mediaType, currentCoroutineContext().job)
        val sample = org.infinilect.app.covers.CoverPolicy.sample(width, height)
        val outputWidth = (width + sample - 1) / sample
        val outputHeight = (height + sample - 1) / sample
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val candidates = ImageIO.getImageReadersByFormatName(if (mediaType == "image/png") "PNG" else "JPEG")
            check(candidates.hasNext())
            val reader = candidates.next()
            try {
                reader.setInput(input, true, true) // no metadata tree or filesystem-backed ImageIO cache
                require(reader.getWidth(0) == width && reader.getHeight(0) == height)
                currentCoroutineContext().ensureActive()
                val params = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                val image = reader.read(0, params)
                try {
                    currentCoroutineContext().ensureActive()
                    require(image.width == outputWidth && image.height == outputHeight)
                    Raster(outputWidth, outputHeight, image.getRGB(0, 0, outputWidth, outputHeight, null, 0, outputWidth))
                } finally { image.flush() }
            } finally { reader.dispose() }
        }
}
