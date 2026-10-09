// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.covers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import kotlinx.coroutines.*
import org.infinilect.app.media.rasterDimensions

import org.infinilect.app.media.Raster

internal actual suspend fun decodeCoverThumbnail(bytes: ByteArray, mediaType: String): Raster = withContext(Dispatchers.IO) {
        val (width, height) = rasterDimensions(bytes, mediaType, currentCoroutineContext().job)
        val sample = org.infinilect.app.covers.CoverPolicy.sample(width, height)
        val outputWidth = (width + sample - 1) / sample
        val outputHeight = (height + sample - 1) / sample
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == width && bounds.outHeight == height && bounds.outMimeType == mediaType)
        currentCoroutineContext().ensureActive()
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false; inSampleSize = sample; inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        try {
            currentCoroutineContext().ensureActive()
            require(bitmap.width in maxOf(1, width / sample)..outputWidth && bitmap.height in maxOf(1, height / sample)..outputHeight)
            val decodedWidth = bitmap.width
            val decodedHeight = bitmap.height
            val pixels = IntArray(decodedWidth * decodedHeight)
            bitmap.getPixels(pixels, 0, decodedWidth, 0, 0, decodedWidth, decodedHeight)
            Raster(decodedWidth, decodedHeight, pixels)
        } finally { bitmap.recycle() }
}
