// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.infinilect.app.gutenberg.*
import org.infinilect.app.covers.*
import org.infinilect.core.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class DiscoveryCoverTest {
    @Test fun advertisedJpegUsesExistingBoundedThumbnailDecoderWithoutFormatAuthority()=runTest {
        val image=BufferedImage(200,288,BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) for(x in 0 until image.width) image.setRGB(x,y,if(x<100) 0x336655 else 0x663355)
        val bytes=ByteArrayOutputStream().also{ImageIO.write(image,"JPEG",it)}.toByteArray();image.flush()
        val link="https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg"
        val book=previewPublication().dropLast(1)+",\"images\":[{\"href\":\"$link\",\"type\":\"image/jpeg\"}]}"
        val requests=mutableListOf<String>()
        val source=GutenbergSource(MockEngine {request->
            requests+=request.url.toString()
            if(request.url.host=="www.gutenberg.org") respond(bytes,headers=headersOf(HttpHeaders.ContentType,"image/jpeg"))
            else respond(if(request.url.encodedPath.endsWith("/search")) previewPage("books",publications=book) else previewRoot,headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val page=source.discover(DiscoveryRequest("books"));assertTrue(page.entries.single().publication.resources.isEmpty())
            val cover=assertNotNull(source.coverThumbnail(PublicationId(source.id,"84")));assertNull(cover.format)
            val raster=assertNotNull(cover.raster);assertTrue(raster.width<=CoverPolicy.WIDTH&&raster.height<=CoverPolicy.HEIGHT)
            assertEquals(3,requests.size);assertTrue(requests.none{it.endsWith(".epub")})
        }finally{source.close()}
    }
}
