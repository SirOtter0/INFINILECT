// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

internal class TestPdfDocument(count:Int=3,
    override val progressId:ReadingProgressId=ReadingProgressId(PublicationId(SourceId("local-imports"),"digest"),"content",PublicationFormat.PDF),
) : PdfDocument {
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

internal class TestPdfProgressStore : ReadingProgressStore {
    val values=mutableMapOf<ReadingProgressId,ReadingProgress>()
    val saves=mutableListOf<ReadingProgress>()
    var beforeSave:suspend (ReadingProgress)->Unit={}
    override suspend fun get(id:ReadingProgressId)=values[id]
    override suspend fun save(progress:ReadingProgress):Boolean {
        beforeSave(progress);values[progress.id]=progress;saves+=progress;return true
    }
    override suspend fun remove(id:ReadingProgressId):Boolean {values.remove(id);return true}
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
        val document=TestPdfDocument();val store=TestPdfProgressStore()
        val writer=ProgressPersistence(store,StandardTestDispatcher(testScheduler)){10L}
        val reader=PdfReaderController(document,this,writer)
        reader.initialize(null)
        val gate=CompletableDeferred<Unit>()
        document.action={index->if(index==1)withContext(NonCancellable){gate.await()}}
        reader.next();runCurrent();reader.next();runCurrent()
        assertEquals(2,reader.state.value.index)
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(2,reader.state.value.index)
        assertIs<PdfFrame.Ready>(reader.state.value.frame)
        assertEquals(2,reader.state.value.presentedIndex)
        assertEquals(listOf(2),store.saves.map { (it.locator as ReadingLocator.Page).pageIndex })
        assertFailsWith<PdfException>{document.rasters.last().argb} // Late page-one result.
        reader.close();writer.close();writer.awaitClosed()
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
        reader.initialize(null);runCurrent();assertTrue(values.isEmpty())
        val gate=CompletableDeferred<Unit>();document.action={gate.await()}
        reader.next();runCurrent()
        assertEquals(1,reader.state.value.index);assertIs<PdfFrame.Loading>(reader.state.value.frame)
        assertEquals(0,reader.state.value.presentedIndex);assertTrue(values.isEmpty())
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(1,reader.state.value.presentedIndex);assertIs<PdfFrame.Ready>(reader.state.value.frame)
        assertEquals(1,(assertNotNull(values[document.progressId]).locator as ReadingLocator.Page).pageIndex)
        reader.close()
        val reopened=PdfReaderController(TestPdfDocument(),this,writer)
        reopened.initialize(writer.get(document.progressId));advanceUntilIdle()
        assertEquals(1,reopened.state.value.index)
        assertEquals(1,(assertNotNull(values[document.progressId]).locator as ReadingLocator.Page).pageIndex)
        reopened.close();writer.close();writer.awaitClosed()
    }
    @Test fun failedLaterPageIsControlledAndKeepsLastSuccessfulProgressOnReopen()=runTest {
        for (failure in listOf(PdfFailure.CODEC,PdfFailure.RENDER)) {
            val document=TestPdfDocument();val store=TestPdfProgressStore()
            val writer=ProgressPersistence(store,StandardTestDispatcher(testScheduler)){10L}
            val reader=PdfReaderController(document,this,writer)
            reader.initialize(null);reader.next();advanceUntilIdle()
            document.action={throw PdfException(failure)}
            reader.next();advanceUntilIdle()
            assertEquals(2,reader.state.value.index)
            assertEquals(failure,assertIs<PdfFrame.Failed>(reader.state.value.frame).failure)
            assertEquals(1,reader.state.value.presentedIndex)
            assertEquals(listOf(1),store.saves.map { (it.locator as ReadingLocator.Page).pageIndex })
            reader.flush();reader.close();advanceUntilIdle()
            val reopened=PdfReaderController(TestPdfDocument(),this,writer)
            reopened.initialize(store.get(document.progressId));advanceUntilIdle()
            assertEquals(1,reopened.state.value.index);assertIs<PdfFrame.Ready>(reopened.state.value.frame)
            assertEquals(1,store.saves.size) // Opening does not reset/rewrite progress.
            reopened.close();writer.close();writer.awaitClosed()
        }
    }
    @Test fun closingDuringRenderDoesNotSaveTheUnrenderedTarget()=runTest {
        val document=TestPdfDocument();val store=TestPdfProgressStore()
        val writer=ProgressPersistence(store,StandardTestDispatcher(testScheduler)){10L}
        val reader=PdfReaderController(document,this,writer)
        reader.initialize(null);reader.next();advanceUntilIdle()
        val gate=CompletableDeferred<Unit>()
        document.action={withContext(NonCancellable){gate.await()}}
        reader.next();runCurrent();reader.flush();reader.close()
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(listOf(1),store.saves.map { (it.locator as ReadingLocator.Page).pageIndex })
        assertEquals(1,reader.state.value.presentedIndex)
        document.rasters.forEach {assertFailsWith<PdfException>{it.argb}}
        writer.close();writer.awaitClosed()
    }
    @Test fun recreationIndexWinsOverAnOlderAsynchronousProgressCommit()=runTest {
        val document=TestPdfDocument();val store=TestPdfProgressStore()
        val writer=ProgressPersistence(store,StandardTestDispatcher(testScheduler)){10L}
        val reader=PdfReaderController(document,this,writer)
        val older=ReadingProgress(document.progressId,ReadingLocator.Page("pdf-page-0",0,0.0),0.0,10)
        val gate=CompletableDeferred<Unit>()
        store.beforeSave={if(it==older)gate.await()}
        writer.submit(older);runCurrent() // Older write is still in flight during recreation.
        reader.initialize(writer.get(document.progressId),2)
        assertEquals(2,reader.state.value.index);assertEquals(listOf(2),document.rendered)
        assertEquals(2,reader.state.value.presentedIndex)
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(2,(assertNotNull(store.get(document.progressId)).locator as ReadingLocator.Page).pageIndex)
        assertTrue(assertNotNull(store.get(document.progressId)).updatedAtEpochMillis>older.updatedAtEpochMillis)
        reader.close();writer.close();writer.awaitClosed()
    }
    @Test fun failedInitialRecreationDoesNotCommitItsTarget()=runTest {
        val document=TestPdfDocument();val store=TestPdfProgressStore()
        val older=ReadingProgress(document.progressId,ReadingLocator.Page("pdf-page-0",0,0.0),0.0,10)
        store.values[document.progressId]=older
        val writer=ProgressPersistence(store,StandardTestDispatcher(testScheduler)){10L}
        val reader=PdfReaderController(document,this,writer)
        document.action={throw PdfException(PdfFailure.RENDER)}
        assertFailsWith<PdfException>{reader.initialize(older,2)};advanceUntilIdle()
        assertEquals(older,store.get(document.progressId));assertTrue(store.saves.isEmpty())
        assertNull(reader.state.value.presentedIndex)
        reader.close();writer.close();writer.awaitClosed()
    }
}
