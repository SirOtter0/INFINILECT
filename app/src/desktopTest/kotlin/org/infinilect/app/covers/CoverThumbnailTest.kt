// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.covers

import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.media.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CoverThumbnailTest {
    private fun image(w:Int,h:Int,type:String="png"):ByteArray {
        val image=BufferedImage(w,h,BufferedImage.TYPE_INT_RGB)
        try {
            for(y in 0 until h) for(x in 0 until w) image.setRGB(x,y, 0xff000000.toInt() or ((x*17+y*3) and 255 shl 16) or ((y*13+x*7) and 255 shl 8) or ((x*3+y*5) and 255))
            return ByteArrayOutputStream().use {ImageIO.write(image,type,it);it.toByteArray()}
        } finally {image.flush()}
    }
    @Test fun portraitPngIsSubsampledBeforePixelMaterialization()=runBlocking<Unit> {
        val raster=decodeCoverThumbnail(image(760,1080),"image/png")
        assertEquals(190 to 270, raster.width to raster.height);assertEquals(51_300,raster.argb.size)
    }
    @Test fun landscapeJpegFitsBothThumbnailBoundsWithoutStretching()=runBlocking<Unit> {
        val raster=decodeCoverThumbnail(image(1024,512,"jpeg"),"image/jpeg")
        assertEquals(128 to 64,raster.width to raster.height)
    }
    @Test fun smallImageIsNotUpscaledAndMalformedImageIsRejected()=runBlocking<Unit> {
        val raster=decodeCoverThumbnail(image(48,72),"image/png");assertEquals(48 to 72,raster.width to raster.height)
        assertFails {decodeCoverThumbnail(byteArrayOf(1,2,3),"image/png")}
    }
    @Test fun oversizedSourceIsRejectedBeforeNativeDecode()=runBlocking<Unit> {
        assertFails {decodeCoverThumbnail(image(1200,1200),"image/png")}
        assertFails {decodeCoverThumbnail(ByteArray(RasterPolicy.ENCODED_BYTES+1),"image/png")}
    }
    @Test fun sharedBitmapIsConvertedOnceAndReleasedOnInvalidation()=runTest {
        var conversions=0
        val cache=PublicationCovers({CoverArtwork(PublicationFormat.CBZ,Raster(48,72,IntArray(48*72){0xff294d55.toInt()}))},
            StandardTestDispatcher(testScheduler),convert={conversions++;androidx.compose.ui.graphics.ImageBitmap(it.width,it.height)})
        val id=PublicationId(SourceId("original"),"image")
        val a=cache.acquire(id);val b=cache.acquire(id);runCurrent()
        assertEquals(1,conversions);assertSame(a.state.value.image,b.state.value.image);assertEquals(48,a.state.value.image?.width)
        cache.invalidate(id);assertNull(a.state.value.image);assertNull(b.state.value.image)
        cache.release(a);cache.release(b);cache.close();cache.awaitClosed()
    }
    @Test fun positiveLruEvictionClearsOldArtworkAndCannotRetainTwentyFifthBitmap()=runTest {
        val cache=PublicationCovers({CoverArtwork(PublicationFormat.EPUB,Raster(192,288,IntArray(192*288)))},
            StandardTestDispatcher(testScheduler),convert={androidx.compose.ui.graphics.ImageBitmap(it.width,it.height)})
        val oldLeases=(0..24).map {
            val lease=cache.acquire(PublicationId(SourceId("original"),"$it"));runCurrent();cache.release(lease);lease
        }
        assertNull(oldLeases.first().state.value.image)
        assertEquals(24,oldLeases.count {it.state.value.image!=null})
        assertEquals(5_308_416,oldLeases.sumOf {it.state.value.image?.let {image->image.width*image.height*4}?:0})
        assertEquals(24,cache.retainedEntries())
        cache.close();cache.awaitClosed();assertTrue(oldLeases.all {it.state.value.image==null})
    }

}
