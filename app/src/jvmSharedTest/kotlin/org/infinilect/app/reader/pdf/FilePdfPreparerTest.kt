// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.infinilect.core.*
import kotlin.test.*

class FilePdfPreparerTest {
    private val id=PublicationId(SourceId("owned-test"),"document")
    private val resource=PublicationResource(id,"content",PublicationFormat.PDF,"application/pdf")
    private val publication=Publication(id,"PDF",PublicationType.DOCUMENT,resources=listOf(resource))
    private fun loader(bytes:ByteArray=smallPdf(), failEof:Boolean=false): ResourceLoader = object:ResourceLoader {
        override suspend fun load(resource:PublicationResource):ResourceContent = object:ResourceContent {
            var position=0;var closed=false
            override val sizeBytes=bytes.size.toLong()
            override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                check(!closed)
                if(position==bytes.size) { if(failEof)throw java.io.IOException();return -1 }
                val n=minOf(length,bytes.size-position);bytes.copyInto(buffer,offset,position,position+n);position+=n;return n
            }
            override fun close(){closed=true}
        }
    }
    private class Engine:PdfEngine {
        val opens=AtomicInteger();val closes=AtomicInteger();val renders=AtomicInteger()
        var produced:PdfRaster?=null
        var onRender:()->Unit={}
        override fun open(path:Path):PdfEngineDocument {
            opens.incrementAndGet();assertTrue(Files.size(path)>0)
            return object:PdfEngineDocument {
                override val pages=listOf(PdfPageGeometry(612.0,792.0))
                override fun render(index:Int,size:PdfRenderSize):PdfRaster {
                    renders.incrementAndGet();onRender()
                    return PdfRaster(size,IntArray(size.width*size.height)).also { produced=it }
                }
                override fun close(){closes.incrementAndGet()}
            }
        }
    }
    @Test fun repeatedOwnedOpenCloseRemovesSpoolsAndClosesOnce()=runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-owner-test");val engine=Engine();val owner=FilePdfPreparer(base,engine)
        try {
            repeat(5) {
                val document=owner.prepare(publication,resource,loader())
                val raster=document.renderPage(0,PdfRenderSize(64,64));raster.close()
                document.close();document.close();owner.awaitClosed()
                assertFailsWith<PdfException>{document.renderPage(0,PdfRenderSize(1,1))}
            }
            assertEquals(5,engine.opens.get());assertEquals(5,engine.closes.get())
            Files.list(base).use { paths -> assertEquals(0L,paths.filter { it.fileName.toString().endsWith(".part") }.count()) }
        } finally { owner.close();owner.awaitClosed();base.toFile().deleteRecursively() }
    }
    @Test fun EOFVerificationMustCompleteBeforeEngineOpen()=runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-verify-test");val engine=Engine();val owner=FilePdfPreparer(base,engine)
        try {
            assertFailsWith<PdfException>{owner.prepare(publication,resource,loader(failEof=true))}
            assertEquals(0,engine.opens.get())
            Files.list(base).use { paths -> assertEquals(0L,paths.filter { it.fileName.toString().endsWith(".part") }.count()) }
        } finally { owner.close();owner.awaitClosed();base.toFile().deleteRecursively() }
    }
    @Test fun cancellationAndCloseDuringBlockingRenderReleaseDiscardedRaster()=runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-cancel-test");val engine=Engine();val owner=FilePdfPreparer(base,engine)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val document=owner.prepare(publication,resource,loader())
            engine.onRender={entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
            val request=launch { document.renderPage(0,PdfRenderSize(64,64));error("obsolete result escaped") }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5,TimeUnit.SECONDS)) }
            request.cancel();document.close();release.countDown();request.join();owner.awaitClosed()
            assertEquals(1,engine.closes.get())
            assertFailsWith<PdfException>{assertNotNull(engine.produced).argb}
        } finally { release.countDown();owner.close();owner.awaitClosed();base.toFile().deleteRecursively() }
    }
}
