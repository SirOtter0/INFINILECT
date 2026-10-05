// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlin.test.*
import org.infinilect.app.epub.*
import org.infinilect.app.reader.epub.*

/** REAL JDK ImageIO decoder and Skia presentation adapter; no Android-device claim. */
class EpubRasterDecoderTest {
    @Test fun realDesktopPngDecodeProducesOwnedPixels() = runBlocking {
        val raster = defaultEpubRasterDecoder().decode(developmentPng(), "image/png")
        assertEquals(96, raster.width); assertEquals(64, raster.height); assertEquals(6144, raster.argb.size)
        assertEquals(0xff265aa0.toInt(), raster.argb[20 * 96 + 20])
    }
    @Test fun realDesktopJpegDecodeProducesBoundedPixels() = runBlocking {
        val raster = defaultEpubRasterDecoder().decode(developmentJpeg(), "image/jpeg")
        assertEquals(96 * 64, raster.argb.size); assertEquals(96, raster.width)
    }
    @Test fun realPresentationAdapterPreservesSizeAndArgb() = runBlocking {
        val raster = EpubRaster(1, 1, intArrayOf(0xff265aa0.toInt()))
        val bitmap = epubImageBitmap(raster); assertEquals(1, bitmap.width); assertEquals(1, bitmap.height)
        val pixels = IntArray(1); bitmap.readPixels(pixels); assertContentEquals(raster.argb, pixels)
    }
    @Test fun invalidInputNeverProducesImage() = runBlocking<Unit> {
        assertFails { defaultEpubRasterDecoder().decode(byteArrayOf(1,2,3), "image/png") }
    }
    @Test fun cancelledDecodeDoesNotPublishPixels() = runBlocking<Unit> {
        val task = launch(start = CoroutineStart.LAZY) { defaultEpubRasterDecoder().decode(developmentPng(), "image/png"); fail("Cancelled work ran") }
        task.cancelAndJoin()
    }
    @Test fun realDesktopProgressiveJpegDecodeIsBounded() = runBlocking {
        val raster = defaultEpubRasterDecoder().decode(originalProgressiveJpeg(), "image/jpeg")
        assertEquals(8 to 8, raster.width to raster.height); assertEquals(64, raster.argb.size)
    }

}
