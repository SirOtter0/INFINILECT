// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

internal class TestPdfDocument(count:Int=3) : PdfDocument {
    override val progressId=ReadingProgressId(PublicationId(SourceId("local-imports"),"digest"),"content",PublicationFormat.PDF)
    override val pages=List(count){PdfPageGeometry(612.0,792.0)}
    val rasters=mutableListOf<PdfRaster>();val rendered=mutableListOf<Int>()
    var closes=0
    var action:suspend (Int)->Unit={}
    override suspend fun renderPage(pageIndex:Int,size:PdfRenderSize):PdfRaster {
        rendered+=pageIndex;action(pageIndex)
        return PdfRaster(PdfRenderSize(2,2),IntArray(4)).also { rasters+=it }
    }
    override fun close(){closes++}
}

@OptIn(ExperimentalCoroutinesApi::class)
class PdfReaderControllerTest {
    @Test fun nextPreviousKeepOneRasterAndCloseExactlyOnce()=runTest {
        val document=TestPdfDocument();val reader=PdfReaderController(document,this)
        reader.initialize(null);assertEquals(0,reader.state.value.index)
        reader.previous();assertEquals(listOf(0),document.rendered)
        reader.next();advanceUntilIdle();assertEquals(1,reader.state.value.index)
        assertFailsWith<PdfException>{document.rasters.first().argb}
        reader.next();advanceUntilIdle();reader.next();assertEquals(2,reader.state.value.index)
        reader.previous();advanceUntilIdle();assertEquals(1,reader.state.value.index)
        reader.close();reader.close();assertEquals(1,document.closes)
        document.rasters.forEach { assertFailsWith<PdfException>{it.argb} }
    }
    @Test fun obsoleteNonCooperativeResultNeverUpdatesPageAndIsClosed()=runTest {
        val document=TestPdfDocument();val reader=PdfReaderController(document,this)
        reader.initialize(null)
        val gate=CompletableDeferred<Unit>()
        document.action={index->if(index==1)withContext(NonCancellable){gate.await()}}
        reader.next();runCurrent();reader.next();runCurrent()
        assertEquals(2,reader.state.value.index)
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(2,reader.state.value.index)
        assertIs<PdfFrame.Ready>(reader.state.value.frame)
        assertFailsWith<PdfException>{document.rasters.last().argb} // Late page-one result.
        reader.close()
    }
    @Test fun logicalProgressRestoresWithoutWritingAnInitialReset()=runTest {
        val document=TestPdfDocument();val values=mutableMapOf<ReadingProgressId,ReadingProgress>()
        val store=object:ReadingProgressStore {
            override suspend fun get(id:ReadingProgressId)=values[id]
            override suspend fun save(progress:ReadingProgress):Boolean {values[progress.id]=progress;return true}
            override suspend fun remove(id:ReadingProgressId):Boolean {values.remove(id);return true}
        }
        val writer=ProgressPersistence(store,StandardTestDispatcher(testScheduler)){10L}
        val reader=PdfReaderController(document,this,writer)
        reader.initialize(null);reader.next();advanceUntilIdle()
        assertEquals(1,(assertNotNull(values[document.progressId]).locator as ReadingLocator.Page).pageIndex)
        reader.close()
        val reopened=PdfReaderController(TestPdfDocument(),this,writer)
        reopened.initialize(writer.get(document.progressId));advanceUntilIdle()
        assertEquals(1,reopened.state.value.index)
        assertEquals(1,(assertNotNull(values[document.progressId]).locator as ReadingLocator.Page).pageIndex)
        reopened.close();writer.close();writer.awaitClosed()
    }
    @Test fun failedLaterPageIsControlledAndKeepsSemanticPosition()=runTest {
        val document=TestPdfDocument();val reader=PdfReaderController(document,this)
        reader.initialize(null);document.action={throw PdfException(PdfFailure.CODEC)}
        reader.next();advanceUntilIdle()
        assertEquals(1,reader.state.value.index);assertEquals(PdfFailure.CODEC,assertIs<PdfFrame.Failed>(reader.state.value.frame).failure)
        reader.close()
    }
}
