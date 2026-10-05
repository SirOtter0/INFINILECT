// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.infinilect.app.page.DevelopmentComicSource
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.reader.page.*
import org.infinilect.app.media.*
import org.infinilect.core.readBytes
import kotlin.test.*

/** Real ImageIO + Skia, actual original PNG/JPEG pages. Host, not physical device coverage. */
class PageRasterDecoderTest {
    @Test fun originalPngPageDecodesAndPresentationPreservesDimensions()=runBlocking {
        val source=DevelopmentComicSource();val page=source.search("demo",null).publications.single().resources[0]
        val bytes=source.loadResource(page).readBytes(RasterPolicy.ENCODED_BYTES)
        val raster=defaultRasterDecoder().decode(bytes,page.mediaType)
        assertEquals(512 to 768,raster.width to raster.height)
        val bitmap=rasterImageBitmap(raster);assertEquals(512,bitmap.width);assertEquals(768,bitmap.height)
    }
    @Test fun originalJpegPageDecodesWithinSharedRasterBounds()=runBlocking {
        val source=DevelopmentComicSource();val page=source.search("demo",null).publications.single().resources[3]
        val raster=defaultRasterDecoder().decode(source.loadResource(page).readBytes(RasterPolicy.ENCODED_BYTES),page.mediaType)
        assertEquals(640 to 640,raster.width to raster.height);assertEquals(409600,raster.argb.size)
    }
    @Test fun realPageDocumentAndControllerReadDistantRegionsWithoutDecodingWholeComic()=runBlocking {
        val source=DevelopmentComicSource();val pub=source.search("demo",null).publications.single()
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        val controller=PageReaderController(defaultPagePreparer().prepare(pub,DirectResourceLoader(source)),scope)
        try {controller.initialize(null)
            for(index in listOf(0,20,3)) {controller.navigate(index);withTimeout(10_000){controller.state.first{it.frames[index] is PageFrame.Ready}}
                assertTrue(controller.retainedPages<=3);assertEquals(index,controller.state.value.position.index)}}
        finally{controller.close();scope.cancel()}
        assertEquals(0,controller.retainedPages)
    }
}
