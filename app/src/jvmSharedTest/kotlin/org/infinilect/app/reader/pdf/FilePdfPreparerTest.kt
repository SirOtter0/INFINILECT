// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
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
        private val operations=AtomicInteger()
        val maximumOperations=AtomicInteger()
        private val live=AtomicInteger()
        val maximumDocuments=AtomicInteger()
        var produced:PdfRaster?=null
        var onRender:()->Unit={}
        var onOpen:()->Unit={}
        var onClose:()->Unit={}
        private fun <T> operation(action:()->T):T {
            val count=operations.incrementAndGet();maximumOperations.accumulateAndGet(count,::maxOf)
            try { assertEquals(1,count,"Native open/render/close must share serialization");return action() }
            finally {operations.decrementAndGet()}
        }
        override fun open(path:Path):PdfEngineDocument = operation {
            opens.incrementAndGet();assertTrue(Files.size(path)>0)
            onOpen();maximumDocuments.accumulateAndGet(live.incrementAndGet(),::maxOf)
            object:PdfEngineDocument {
                override val pages=listOf(PdfPageGeometry(612.0,792.0))
                private val retired=AtomicBoolean()
                override fun render(index:Int,size:PdfRenderSize):PdfRaster = operation {
                    assertFalse(retired.get(),"No render on a closed native handle")
                    renders.incrementAndGet();onRender()
                    PdfRaster(size,IntArray(size.width*size.height)).also { produced=it }
                }
                override fun close()=operation {
                    assertTrue(retired.compareAndSet(false,true),"Native handle must close exactly once")
                    closes.incrementAndGet();live.decrementAndGet();onClose()
                }
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
    @Test fun cancellationAtIoReturnClosesUndeliveredDocument()=runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-return-test");val engine=Engine()
        val queued=java.util.ArrayDeque<Runnable>()
        val io=object:CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable){queued.add(block)}
        }
        val owner=FilePdfPreparer(base,engine,io)
        try {
            val request=launch(start=CoroutineStart.UNDISPATCHED) {
                owner.prepare(publication,resource,loader());error("undelivered document escaped")
            }
            queued.removeFirst().run() // Engine opened; caller return dispatch has not run.
            request.cancelAndJoin()
            while(queued.isNotEmpty())queued.removeFirst().run()
            assertEquals(1,engine.opens.get());assertEquals(1,engine.closes.get())
        } finally {
            owner.close();while(queued.isNotEmpty())queued.removeFirst().run()
            owner.awaitClosed();base.toFile().deleteRecursively()
        }
    }
    @Test fun concurrentRenderRequestsRemainSerialized()=runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-serial-test");val engine=Engine();val owner=FilePdfPreparer(base,engine)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val document=owner.prepare(publication,resource,loader())
            engine.onRender={entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
            val first=async {document.renderPage(0,PdfRenderSize(64,64))}
            withContext(Dispatchers.IO){assertTrue(entered.await(5,TimeUnit.SECONDS))}
            val second=async {document.renderPage(0,PdfRenderSize(32,32))}
            yield();assertEquals(1,engine.renders.get())
            release.countDown();first.await().close();second.await().close()
            assertEquals(2,engine.renders.get());document.close()
        } finally {release.countDown();owner.close();owner.awaitClosed();base.toFile().deleteRecursively()}
    }

    @Test fun snapshotAfterFinalOwnerUnregistersDoesNotUseAnObsoleteSingletonSize() = runTest {
        // The exact dangerous interleaving, not a random scheduling stress loop:
        // snapshot reads size=1; cleanup removes the last owner; iterator is now empty.
        val owners = ConcurrentHashMap.newKeySet<String>().also { it.add("native document") }
        val unregister = launch(StandardTestDispatcher(testScheduler)) { owners.remove("native document") }
        val observed = object : AbstractCollection<String>() {
            override val size get() = owners.size
            override fun iterator(): Iterator<String> {
                runCurrent() // Force document/job cleanup before snapshot traversal starts.
                assertTrue(unregister.isCompleted); assertTrue(owners.isEmpty())
                return owners.iterator()
            }
        }
        assertEquals(emptyList(), pdfOwnerSnapshot(observed))
    }

    @Test fun snapshotRemainsStableWhenCancellationUnregistersEveryJob() = runTest {
        val jobs=ConcurrentHashMap.newKeySet<Job>()
        repeat(2) {
            val job=Job();jobs.add(job);job.invokeOnCompletion {jobs.remove(job)}
        }
        val snapshot=pdfOwnerSnapshot(jobs)
        snapshot.forEach {it.cancel()}
        assertTrue(jobs.isEmpty());assertEquals(2,snapshot.size);assertTrue(snapshot.all {it.isCancelled})
    }

    private fun noSpools(base:Path) {
        Files.list(base).use { paths -> assertEquals(0L,paths.filter {it.fileName.toString().endsWith(".part")}.count()) }
    }
    @Test fun finalCloseRetiresBothDocumentsAndReleasesItsLeaseExactlyOnce() = runTest {
        val base=Files.createTempDirectory("pdf-final-close");val engine=Engine()
        val owner=FilePdfPreparer(base,engine,StandardTestDispatcher(testScheduler))
        try {
            val first=owner.prepare(publication,resource,loader());val second=owner.prepare(publication,resource,loader())
            assertEquals(PdfFailure.LIMIT,assertFailsWith<PdfException> {owner.prepare(publication,resource,loader())}.failure)
            first.renderPage(0,PdfRenderSize(2,2)).close()
            first.close();owner.close();second.close();owner.close();owner.awaitClosed()
            assertEquals(2,engine.opens.get());assertEquals(2,engine.closes.get());assertEquals(1,engine.maximumOperations.get())
            assertEquals(2,engine.maximumDocuments.get());noSpools(base)
            assertEquals(PdfFailure.CLOSED,assertFailsWith<PdfException> {first.renderPage(0,PdfRenderSize(2,2))}.failure)
            assertEquals(PdfFailure.CLOSED,assertFailsWith<PdfException> {second.renderPage(0,PdfRenderSize(2,2))}.failure)
            assertEquals(PdfFailure.CLOSED,assertFailsWith<PdfException> {owner.prepare(publication,resource,loader())}.failure)
            assertEquals(1,engine.renders.get());assertEquals(2,engine.opens.get())
            // The final lease is released only after queued native cleanup, not merely retired flags.
            val replacement=FilePdfPreparer(base,Engine(),StandardTestDispatcher(testScheduler))
            try {replacement.prepare(publication,resource,loader()).close()}
            finally {replacement.close();replacement.awaitClosed()}
            noSpools(base)
        } finally {owner.close();owner.awaitClosed();base.toFile().deleteRecursively()}
    }

    @Test fun closeWhileNativeOpenIsPendingClosesItsUndeliveredHandleAndExistingDocument() = runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-open-close");val engine=Engine();val owner=FilePdfPreparer(base,engine)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val first=owner.prepare(publication,resource,loader())
            engine.onOpen={entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
            val request=async {owner.prepare(publication,resource,loader())}
            withContext(Dispatchers.IO) {assertTrue(entered.await(5,TimeUnit.SECONDS))}
            owner.close();owner.close();first.close();release.countDown()
            assertFailsWith<CancellationException> {request.await()};owner.awaitClosed()
            assertEquals(2,engine.opens.get());assertEquals(2,engine.closes.get());noSpools(base)
            assertEquals(1,engine.maximumOperations.get());assertTrue(engine.maximumDocuments.get()<=2)
            assertEquals(PdfFailure.CLOSED,assertFailsWith<PdfException> {first.renderPage(0,PdfRenderSize(2,2))}.failure)
        } finally {release.countDown();owner.close();owner.awaitClosed();base.toFile().deleteRecursively()}
    }

    @Test fun finalCloseDuringNativeRenderDiscardsItsResultAndRejectsQueuedRender() = runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-render-close");val engine=Engine();val owner=FilePdfPreparer(base,engine)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val first=owner.prepare(publication,resource,loader());val second=owner.prepare(publication,resource,loader())
            engine.onRender={entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
            val rendering=async {runCatching {first.renderPage(0,PdfRenderSize(2,2))}}
            withContext(Dispatchers.IO) {assertTrue(entered.await(5,TimeUnit.SECONDS))}
            val queued=async(start=CoroutineStart.UNDISPATCHED) {runCatching {second.renderPage(0,PdfRenderSize(2,2))}}
            owner.close();first.close();second.close();owner.close()
            assertEquals(0,engine.closes.get(),"Native cleanup must wait for the in-flight render")
            release.countDown()
            assertEquals(PdfFailure.CLOSED,assertIs<PdfException>(rendering.await().exceptionOrNull()).failure)
            assertEquals(PdfFailure.CLOSED,assertIs<PdfException>(queued.await().exceptionOrNull()).failure)
            owner.awaitClosed();assertEquals(1,engine.renders.get());assertEquals(2,engine.closes.get());noSpools(base)
            assertFailsWith<PdfException> {assertNotNull(engine.produced).argb}
            assertEquals(1,engine.maximumOperations.get())
        } finally {release.countDown();owner.close();owner.awaitClosed();base.toFile().deleteRecursively()}
    }

    @Test fun cancelledPendingTransferUnregistersAndFinalCloseCleansEveryHandle() = runTest {
        val base=Files.createTempDirectory("pdf-transfer-close");val engine=Engine()
        val owner=FilePdfPreparer(base,engine,StandardTestDispatcher(testScheduler))
        var contentCloses=0;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val pending=object:ResourceLoader {
            override suspend fun load(resource:PublicationResource)=object:ResourceContent {
                override val sizeBytes:Long?=null
                override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {entered.complete(Unit);release.await();return -1}
                override fun close(){contentCloses++}
            }
        }
        try {
            val first=owner.prepare(publication,resource,loader())
            val request=async {owner.prepare(publication,resource,pending)};runCurrent();assertTrue(entered.isCompleted)
            request.cancel();owner.close();first.close();owner.close();runCurrent();request.join();owner.awaitClosed()
            assertEquals(1,contentCloses);assertEquals(1,engine.opens.get());assertEquals(1,engine.closes.get());noSpools(base)
        } finally {release.complete(Unit);owner.close();owner.awaitClosed();base.toFile().deleteRecursively()}
    }

    @Test fun failedRenderDoesNotLeakOrMakeTheNextRenderOrCleanupUnsafe() = runTest {
        val base=Files.createTempDirectory("pdf-failed-render");val engine=Engine()
        val owner=FilePdfPreparer(base,engine,StandardTestDispatcher(testScheduler))
        try {
            val document=owner.prepare(publication,resource,loader())
            engine.onRender={throw java.io.IOException("Synthetic codec failure")}
            assertEquals(PdfFailure.RENDER,assertFailsWith<PdfException> {document.renderPage(0,PdfRenderSize(2,2))}.failure)
            engine.onRender={};document.renderPage(0,PdfRenderSize(2,2)).close()
            document.close();owner.close();owner.awaitClosed();noSpools(base)
            assertEquals(2,engine.renders.get());assertEquals(1,engine.closes.get());assertEquals(1,engine.maximumOperations.get())
        } finally {owner.close();owner.awaitClosed();base.toFile().deleteRecursively()}
    }

    @Test fun concurrentRenderCancellationAndOwnerCloseStressRetainBoundsAndCloseExactlyOnce() = runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-owner-stress")
        try {repeat(100) {
            val engine=Engine();val owner=FilePdfPreparer(base,engine)
            try {
                val docs=List(2) {owner.prepare(publication,resource,loader())}
                coroutineScope {
                    val requests=List(8) {index -> launch(Dispatchers.Default) {
                        try {docs[index%2].renderPage(0,PdfRenderSize(2,2)).close()}
                        catch (error:PdfException) {assertEquals(PdfFailure.CLOSED,error.failure)}
                    }}
                    requests.filterIndexed {index,_->index%3==0}.forEach {it.cancel()}
                    launch(Dispatchers.Default) {docs[0].close();docs[0].close()}
                    launch(Dispatchers.Default) {docs[1].close();docs[1].close()}
                    launch(Dispatchers.Default) {owner.close();owner.close()}
                }
                owner.close();owner.awaitClosed()
                assertEquals(2,engine.opens.get());assertEquals(2,engine.closes.get());noSpools(base)
                assertEquals(1,engine.maximumOperations.get());assertEquals(2,engine.maximumDocuments.get())
            } finally {owner.close();owner.awaitClosed()}
        }} finally {base.toFile().deleteRecursively()}
    }

    @Test fun serializedRenderCloseStressRepeatsOriginalRegression50Times() {
        repeat(50) {concurrentRenderRequestsRemainSerialized()}
    }
}
