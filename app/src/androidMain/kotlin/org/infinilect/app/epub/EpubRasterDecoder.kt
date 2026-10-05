// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import kotlinx.coroutines.*
import org.infinilect.app.epub.epubRasterDimensions

internal actual fun defaultEpubRasterDecoder(): EpubRasterDecoder = object : EpubRasterDecoder {
    override suspend fun decode(bytes: ByteArray, mediaType: String): EpubRaster = withContext(Dispatchers.IO) {
        val (width, height) = epubRasterDimensions(bytes, mediaType, currentCoroutineContext().job)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == width && bounds.outHeight == height && bounds.outMimeType == mediaType)
        currentCoroutineContext().ensureActive()
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false; inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        try {
            currentCoroutineContext().ensureActive()
            require(bitmap.width == width && bitmap.height == height)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            EpubRaster(width, height, pixels)
        } finally { bitmap.recycle() }
    }
}
