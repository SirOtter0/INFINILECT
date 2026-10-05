// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*

internal actual suspend fun epubImageBitmap(raster: EpubRaster): ImageBitmap = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    Bitmap.createBitmap(raster.argb, raster.width, raster.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
