// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.*
import kotlinx.coroutines.*

internal actual suspend fun epubImageBitmap(raster: EpubRaster): ImageBitmap = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val pixels = ByteArray(raster.argb.size * 4)
    for (i in raster.argb.indices) {
        if (i % 4096 == 0) currentCoroutineContext().ensureActive()
        val color = raster.argb[i]
        pixels[i * 4] = (color shr 16).toByte(); pixels[i * 4 + 1] = (color shr 8).toByte()
        pixels[i * 4 + 2] = color.toByte(); pixels[i * 4 + 3] = (color ushr 24).toByte()
    }
    val bitmap = Bitmap()
    try {
        check(bitmap.installPixels(ImageInfo(raster.width, raster.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), pixels, raster.width * 4))
        bitmap.asComposeImageBitmap()
    } catch (error: Throwable) { bitmap.close(); throw error }
}
