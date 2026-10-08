// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class EpubNavigationPerformanceTest {
    private val paths=listOf(EpubEntryPath("OPS/one.xhtml"),EpubEntryPath("OPS/two.xhtml"))
    private val id=ReadingProgressId(PublicationId(SourceId("original"),"performance"),"book",PublicationFormat.EPUB)
    private inner class Doc:EpubDocument {
        override val publicationId=id.publicationId
        override val packagePath=EpubEntryPath("OPS/package.opf")
        override val metadata=EpubMetadata("original","Original",listOf("en"),null)
        override val manifest=paths.mapIndexed { i,p->EpubManifestItem("c$i",p,"application/xhtml+xml") }
        override val spine=manifest.map { EpubSpineItem(it.id) }
        override val navigationItemId="nav"
        override suspend fun openResource(path:EpubEntryPath):ResourceContent=error("No acquisition")
        override fun close(){}
    }
    private inner class Parser(val count:Int=3269):EpubParser {
        val calls=mutableListOf<Pair<EpubEntryPath,EpubWindowRequest>>()
        var cancelled=0
        var action:suspend(EpubEntryPath,EpubWindowRequest)->Unit={_,_->}
        override suspend fun chapter(document:EpubDocument,path:EpubEntryPath):EpubChapter=error("Use windows")
        override suspend fun toc(document:EpubDocument)=emptyList<EpubTocEntry>()
        override suspend fun window(document:EpubDocument,path:EpubEntryPath,request:EpubWindowRequest):EpubChapter {
            calls.add(path to request)
            try { action(path,request) } catch(error:CancellationException){cancelled++;throw error}
            val i=when(request){is EpubWindowRequest.Block->request.index;is EpubWindowRequest.Anchor->request.value.removePrefix("p").toInt();is EpubWindowRequest.Locator->request.value.elementPath.last();EpubWindowRequest.End->count-1}.coerceIn(0,count-1)
            val start=i/128*128
            return EpubChapter(path,(start until minOf(start+128,count)).map { EpubBlock(listOf(0,it),0,EpubBlockKind.PARAGRAPH,listOf(EpubRun("Original $it")),it*20) },(0 until count).associate{"p$it" to EpubPosition(listOf(0,it),0)},start,count,count*20)
        }
    }
    private fun ready(reader:EpubReaderController)=assertIs<EpubReaderState.Ready>(reader.state.value)
    @Test fun nextPromotesMatchingLookaheadInsteadOfCancellingAndParsingAgain()=runTest {
        val parser=Parser();val gate=CompletableDeferred<Unit>()
        parser.action={_,q->if(q==EpubWindowRequest.Block(128))gate.await()}
        val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        try {
            reader.initialize(null);runCurrent();reader.next();runCurrent()
            assertEquals(0,parser.cancelled)
            assertEquals(1,parser.calls.count{it.second==EpubWindowRequest.Block(128)})
            gate.complete(Unit);runCurrent()
            assertEquals(128,ready(reader).chapter.startBlock)
        } finally {reader.close()}
    }
    @Test fun typographyAndResizeDoNotDiscardSemanticPrefetch()=runTest {
        val parser=Parser();val gate=CompletableDeferred<Unit>()
        parser.action={_,q->if(q==EpubWindowRequest.Block(128))gate.await()}
        val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        try {
            reader.initialize(null);runCurrent();val before=ready(reader)
            reader.presentationChanged(EpubReaderSettings(fontSize=26));reader.presentationChanged();runCurrent()
            assertEquals(0,parser.cancelled)
            gate.complete(Unit);runCurrent();assertEquals(256,ready(reader).chapter.blocks.size)
            assertNotEquals(before.ticket,ready(reader).ticket)
            assertEquals(0 to 0,ready(reader).initialPosition)
        } finally {reader.close()}
    }
    @Test fun cachedNextIsImmediateAndDoesNotScheduleAReplacementParse()=runTest {
        val parser=Parser();val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        try {
            reader.initialize(null);runCurrent();val count=parser.calls.size
            reader.next()
            assertEquals(128,ready(reader).chapter.startBlock)
            assertEquals(count,parser.calls.size)
        } finally {reader.close()}
    }
    @Test fun chapterBoundaryLookaheadPreparesNextSpineBeforeTheButtonRequest()=runTest {
        val parser=Parser(256);val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        try {
            reader.initialize(null);runCurrent()
            reader.navigate(EpubTarget(paths[0],"p255"));reader.presented(ready(reader).ticket);runCurrent()
            assertTrue(parser.calls.any {it.first==paths[1] && it.second==EpubWindowRequest.Block(0)})
            assertEquals(0,ready(reader).spineIndex)
            val count=parser.calls.size;reader.next()
            assertEquals(1,ready(reader).spineIndex);assertEquals(count,parser.calls.size)
        } finally {reader.close()}
    }
    @Test fun repeatedNextDuringMatchingPrefetchHasOneJobAndOneDestination()=runTest {
        val parser=Parser();val gate=CompletableDeferred<Unit>()
        parser.action={_,q->if(q==EpubWindowRequest.Block(128))gate.await()}
        val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        try {
            reader.initialize(null);runCurrent()
            repeat(30){reader.next();runCurrent()}
            assertEquals(2,parser.calls.size);assertEquals(0,parser.cancelled)
            gate.complete(Unit);runCurrent();assertEquals(128,ready(reader).chapter.startBlock)
            assertTrue(reader.retainedBlocks<=256)
        } finally {reader.close()}
    }
    @Test fun duplicateDistantLinkDoesNotRestartPendingNavigation()=runTest {
        val parser=Parser();val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        val gate=CompletableDeferred<Unit>()
        try {
            reader.initialize(null);runCurrent()
            parser.action={_,q->if(q is EpubWindowRequest.Anchor)gate.await()}
            repeat(30){reader.navigate(EpubTarget(paths[1],"p2800"));runCurrent()}
            assertEquals(1,parser.calls.count {it.second==EpubWindowRequest.Anchor("p2800")})
            assertEquals(0,parser.cancelled)
            gate.complete(Unit);runCurrent();assertEquals(1,ready(reader).spineIndex)
            assertEquals(listOf(0,2800),ready(reader).chapter.blocks[ready(reader).initialPosition.first].elementPath)
        } finally {reader.close()}
    }
    @Test fun loadingAndFailureKeepTheLastReadableModelAndRetryTheSameTarget()=runTest {
        val parser=Parser();val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        val gate=CompletableDeferred<Unit>();var fail=true
        try {
            reader.initialize(null);runCurrent();val shown=reader.displayed.value
            parser.action={_,q->if(q is EpubWindowRequest.Anchor){gate.await();if(fail)throw IllegalStateException("private detail")}}
            reader.navigate(EpubTarget(paths[1],"p2800"));runCurrent()
            assertIs<EpubReaderState.Loading>(reader.state.value);assertEquals(shown,reader.displayed.value);assertTrue(reader.loading.value)
            gate.complete(Unit);runCurrent()
            assertIs<EpubReaderState.Error>(reader.state.value);assertEquals(shown,reader.displayed.value);assertFalse(reader.loading.value)
            assertTrue(reader.canRetry);assertFalse(assertIs<EpubReaderState.Error>(reader.state.value).message.contains("private detail"))
            fail=false;reader.retry();runCurrent()
            assertEquals(1,ready(reader).spineIndex);assertEquals(2800,ready(reader).chapter.startBlock+ready(reader).initialPosition.first)
            assertFalse(reader.canRetry);assertEquals(2,parser.calls.count {it.second==EpubWindowRequest.Anchor("p2800")})
        } finally {reader.close()}
    }
    @Test fun failedLookaheadRetryBuffersWithoutNavigatingOrSavingItsTarget()=runTest {
        val parser=Parser();var fail=true
        parser.action={_,q->if(q==EpubWindowRequest.Block(128)&&fail)throw IllegalStateException()}
        val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        try {
            reader.initialize(null);runCurrent();val old=ready(reader)
            assertNotNull(old.error);assertTrue(reader.canRetry)
            fail=false;reader.retry();runCurrent()
            val current=ready(reader);assertNull(current.error);assertEquals(old.presentation,current.presentation)
            assertEquals(0 to 0,current.initialPosition);assertEquals(256,current.chapter.blocks.size)
            assertEquals(0.0,reader.progression.value)
        } finally {reader.close()}
    }
    @Test fun retryAndPrefetchNeverSavePendingFailedOrDecodedPositions()=runTest {
        val store=object:ReadingProgressStore {
            var value:ReadingProgress?=null
            override suspend fun get(id:ReadingProgressId)=value
            override suspend fun save(progress:ReadingProgress):Boolean{value=progress;return true}
            override suspend fun remove(id:ReadingProgressId):Boolean{value=null;return true}
        }
        val persistence=org.infinilect.app.progress.ProgressPersistence(store,StandardTestDispatcher(testScheduler)){testScheduler.currentTime+1}
        val parser=Parser();val reader=EpubReaderController(Doc(),id,backgroundScope,parser,persistence)
        var fail=true
        try {
            reader.initialize(null);runCurrent();reader.report(ready(reader).ticket,12,3);reader.flush();runCurrent();val saved=store.value
            parser.action={_,q->if(q is EpubWindowRequest.Anchor&&fail)throw IllegalStateException()}
            val stale=ready(reader).ticket;reader.navigate(EpubTarget(paths[1],"p2800"));runCurrent()
            reader.report(stale,0,0);reader.flush();runCurrent();assertEquals(saved,store.value)
            fail=false;reader.retry();runCurrent();reader.flush();runCurrent();assertEquals(saved,store.value)
            val target=ready(reader);reader.presented(target.ticket);reader.flush();runCurrent()
            assertEquals(listOf(0,2800),assertIs<ReadingLocator.Epub>(store.value!!.locator).elementPath)
        } finally {reader.close();persistence.close();persistence.awaitClosed()}
    }
    @Test fun promotedLookaheadSurvivesReflowButRejectsOldPresentationCallbacks()=runTest {
        val parser=Parser();val gate=CompletableDeferred<Unit>()
        parser.action={_,q->if(q==EpubWindowRequest.Block(128))gate.await()}
        val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        try {
            reader.initialize(null);runCurrent();val old=ready(reader)
            reader.next();reader.presentationChanged(EpubReaderSettings(margin=32));runCurrent()
            reader.report(old.ticket,100,0);assertEquals(0.0,reader.progression.value)
            gate.complete(Unit);runCurrent();val target=ready(reader)
            assertEquals(128,target.chapter.startBlock);assertEquals(32,reader.settings.value.margin)
            assertEquals(0.0,reader.progression.value);reader.presented(target.ticket);assertTrue(reader.progression.value>0)
        } finally {reader.close()}
    }
    @Test fun closeClearsTheRenderingSnapshotAndCancelsPendingWork()=runTest {
        val parser=Parser();val gate=CompletableDeferred<Unit>()
        parser.action={_,q->if(q==EpubWindowRequest.Block(128))gate.await()}
        val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        reader.initialize(null);runCurrent();assertNotNull(reader.displayed.value)
        reader.next();reader.close();runCurrent()
        assertNull(reader.displayed.value);assertFalse(reader.loading.value);assertEquals(0,reader.retainedBlocks)
        assertEquals(1,parser.cancelled);assertFalse(reader.canRetry)
    }
    @Test fun reverseBoundaryLookaheadPreparesTheExistingPreviousChapterDestination()=runTest {
        val parser=Parser(256);val reader=EpubReaderController(Doc(),id,backgroundScope,parser)
        try {
            reader.initialize(null);runCurrent();reader.chapter(1);runCurrent()
            reader.presented(ready(reader).ticket);runCurrent()
            reader.report(ready(reader).ticket,150,0);runCurrent()
            reader.report(ready(reader).ticket,10,0);runCurrent()
            assertEquals(paths[1],ready(reader).chapter.path)
            val count=parser.calls.size;reader.previous()
            assertEquals(paths[0],ready(reader).chapter.path);assertEquals(0 to 0,ready(reader).initialPosition)
            assertEquals(count,parser.calls.size)
        } finally {reader.close()}
    }

}
