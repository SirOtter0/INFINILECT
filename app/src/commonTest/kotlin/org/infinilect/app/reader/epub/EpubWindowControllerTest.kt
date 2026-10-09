// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.reader.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EpubWindowControllerTest {
    private val publication = PublicationId(SourceId("original"), "long-epub")
    private val id = ReadingProgressId(publication, "book", PublicationFormat.EPUB)
    private val paths = listOf(EpubEntryPath("OPS/one.xhtml"), EpubEntryPath("OPS/two.xhtml"))
    private inner class Doc(override val publicationId: PublicationId = publication) : EpubDocument {
        override val packagePath = EpubEntryPath("OPS/package.opf")
        override val metadata = EpubMetadata("original", "Original", listOf("en"), null)
        override val manifest = paths.mapIndexed { i, p -> EpubManifestItem("c$i", p, "application/xhtml+xml") }
        override val spine = manifest.map { EpubSpineItem(it.id) }
        override val navigationItemId = "nav"
        var closes = 0
        override suspend fun openResource(path: EpubEntryPath): ResourceContent = error("No acquisition")
        override fun close() { closes++ }
    }
    private fun text(i: Int) = "Original 📖 ${i.toString().padStart(4, '0')}"
    private inner class Parser(val count: Int = 3001, val sharedElement: Boolean = false) : EpubParser {
        var active = 0; var peak = 0; var requests = 0
        var action: suspend (EpubEntryPath, EpubWindowRequest) -> Unit = { _, _ -> }
        override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter = error("Must use windows")
        override suspend fun window(document: EpubDocument, path: EpubEntryPath, request: EpubWindowRequest): EpubChapter {
            active++; peak = maxOf(peak, active); requests++
            try {
                action(path, request)
                val index = when (request) {
                    is EpubWindowRequest.Block -> request.index.coerceIn(0, count - 1)
                    is EpubWindowRequest.Locator -> request.value.elementPath.last().takeIf { it in 0 until count } ?: (request.value.chapterProgression * (count - 1)).toInt()
                    is EpubWindowRequest.Anchor -> request.value.removePrefix("a").toIntOrNull() ?: throw EpubException(EpubFailure.INVALID)
                    EpubWindowRequest.End -> count - 1
                }
                require(index in 0 until count)
                val start = index / EpubWindowPolicy.BLOCKS * EpubWindowPolicy.BLOCKS
                val points = text(0).epubCodePoints()
                return EpubChapter(path, (start until minOf(start + EpubWindowPolicy.BLOCKS, count)).map {
                    EpubBlock(listOf(0, if(sharedElement)0 else it), if(sharedElement)it*points else 0, EpubBlockKind.PARAGRAPH, listOf(EpubRun(text(it))), it * points)
                }, (0 until minOf(count, 4096)).associate { "a$it" to EpubPosition(listOf(0, if(sharedElement)0 else it), if(sharedElement)it*points else 0) }, start, count, count * points)
            } finally { active-- }
        }
        override suspend fun toc(document: EpubDocument) = paths.map { EpubTocEntry("Later", EpubTarget(it, "a2800"), 0) }
    }
    private class Store : ReadingProgressStore {
        val values = mutableMapOf<ReadingProgressId, ReadingProgress>()
        override suspend fun get(id: ReadingProgressId) = values[id]
        override suspend fun save(progress: ReadingProgress): Boolean { values[progress.id] = progress; return true }
        override suspend fun remove(id: ReadingProgressId) = values.remove(id) != null
    }
    private suspend fun TestScope.use(count: Int = 3001, before: (Parser) -> Unit = {}, action: suspend (EpubReaderController, Parser, Store, Doc) -> Unit) {
        val parser = Parser(count); before(parser)
        val store = Store(); val writer = ProgressPersistence(store, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime + 1 }
        val doc = Doc(); val reader = EpubReaderController(doc, id, backgroundScope, parser, writer)
        try { reader.initialize(null); runCurrent(); action(reader, parser, store, doc) }
        finally { reader.close(); writer.close(); writer.awaitClosed() }
    }
    private fun frame(reader: EpubReaderController) = assertIs<EpubReaderState.Ready>(reader.state.value)
    private fun bounds(reader: EpubReaderController) {
        assertTrue(reader.retainedChapters <= EpubWindowPolicy.RETAINED)
        assertTrue(reader.retainedBlocks <= EpubWindowPolicy.RETAINED * EpubWindowPolicy.BLOCKS)
        assertTrue(reader.retainedTextUnits <= EpubWindowPolicy.RETAINED * EpubWindowPolicy.TEXT_UNITS)
        assertTrue(frame(reader).chapter.blocks.size <= 256)
    }
    @Test fun lookaheadNeverSavesDecodedOrBufferedProgress() = runTest { use { reader, _, store, _ ->
        assertEquals(256, frame(reader).chapter.blocks.size); reader.flush(); runCurrent()
        assertTrue(store.values.isEmpty()); bounds(reader)
    } }
    @Test fun forwardBufferReplacementKeepsLiveLocatorAndPresentationIdentity() = runTest { use { reader, _, _, _ ->
        val original = frame(reader); reader.report(original.ticket, 150, 5); runCurrent()
        val shifted = frame(reader)
        assertEquals(128, shifted.chapter.startBlock); assertEquals(original.presentation, shifted.presentation)
        assertEquals(listOf(0,150), shifted.chapter.locator(shifted.initialPosition.first, shifted.initialPosition.second).elementPath)
        assertEquals(5, shifted.initialPosition.second); assertNotEquals(original.ticket, shifted.ticket); bounds(reader)
    } }
    @Test fun immediatelyCompletedLookaheadUsesTheJustReportedLocator() = runTest {
        val owner = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val reader = EpubReaderController(Doc(), id, owner, Parser())
        try {
            reader.initialize(null)
            reader.report(frame(reader).ticket, 150, 5)
            val shifted = frame(reader)
            assertEquals(128, shifted.chapter.startBlock)
            assertEquals(listOf(0, 150), shifted.chapter.locator(shifted.initialPosition.first, shifted.initialPosition.second).elementPath)
            assertEquals(5, shifted.initialPosition.second)
            bounds(reader)
        } finally { reader.close(); owner.cancel() }
    }
    @Test fun backwardBufferReplacementKeepsTheSameParagraph() = runTest { use { reader, _, _, _ ->
        reader.report(frame(reader).ticket,150,5);runCurrent()
        reader.report(frame(reader).ticket,10,3);runCurrent()
        val restored=frame(reader);assertEquals(0,restored.chapter.startBlock)
        assertEquals(listOf(0,138),restored.chapter.locator(restored.initialPosition.first,restored.initialPosition.second).elementPath);bounds(reader)
    } }
    @Test fun nextAndPreviousCrossWindowsWithoutOverlappingReadingIdentity() = runTest { use { reader, _, _, _ ->
        reader.next();runCurrent();val next=frame(reader)
        assertEquals(128,next.chapter.startBlock);assertEquals(0 to 0,next.initialPosition)
        reader.presented(next.ticket);runCurrent();reader.previous();runCurrent()
        val previous=frame(reader);assertEquals(0,previous.chapter.startBlock)
        assertEquals(127,previous.initialPosition.first);assertEquals(text(127).epubCodePoints(),previous.initialPosition.second)
    } }
    @Test fun requestedWindowDoesNotAdvanceProgressUntilPresented() = runTest { use { reader, _, store, _ ->
        val original=frame(reader);reader.report(original.ticket,12,3);reader.flush();runCurrent();val saved=store.values[id]
        reader.next();runCurrent();reader.flush();runCurrent();assertEquals(saved,store.values[id])
        val target=frame(reader);reader.presented(target.ticket);reader.flush();runCurrent()
        assertEquals(listOf(0,128),assertIs<ReadingLocator.Epub>(store.values[id]!!.locator).elementPath)
    } }
    @Test fun staleWindowCallbackCannotSaveAfterRollingReplacement() = runTest { use { reader, _, store, _ ->
        val old=frame(reader);reader.report(old.ticket,150,4);runCurrent();reader.flush();runCurrent();val saved=store.values[id]
        reader.report(old.ticket,0,0);reader.presented(old.ticket);reader.flush();runCurrent();assertEquals(saved,store.values[id])
    } }
    @Test fun failedLookaheadAndFailedNextKeepCurrentChapterAndProgress() = runTest { use(before={ parser ->
        parser.action={_, query -> if(query is EpubWindowRequest.Block && query.index>=128)throw EpubException(EpubFailure.LIMIT)}
    }) { reader, _, store, _ ->
        assertEquals(EpubFailure.LIMIT.userMessage,frame(reader).error)
        reader.report(frame(reader).ticket,12,4);reader.flush();runCurrent();val saved=store.values[id]
        reader.next();runCurrent();reader.flush();runCurrent()
        assertEquals(0,frame(reader).chapter.startBlock);assertEquals(saved,store.values[id]);bounds(reader)
    } }
    @Test fun failedNextWindowKeepsTheCurrentImagePresentation() = runTest { use(before = { parser ->
        parser.action = { _, query ->
            if (query is EpubWindowRequest.Block && query.index >= 128) throw EpubException(EpubFailure.LIMIT)
        }
    }) { reader, _, _, _ ->
        // A controlled unsupported-image fallback is still a successfully displayed
        // media state. Starting/failing a window request must not erase it.
        val image = EpubImage(EpubEntryPath("OPS/unsupported.svg"), "image/svg+xml", "Original artwork")
        reader.visibleMedia(frame(reader).ticket, listOf(image))
        reader.media.state.first { it[image] == EpubMediaState.Unavailable }
        val previous = reader.media.state.value
        reader.next(); runCurrent()
        assertEquals(previous, reader.media.state.value)
        assertEquals(0, frame(reader).chapter.startBlock)
    } }
    @Test fun deepTocAndInternalLinksCanMoveForwardBackwardAndBetweenSpines() = runTest { use { reader, _, _, _ ->
        reader.navigate(reader.toc[0].target);runCurrent();assertEquals(2688,frame(reader).chapter.startBlock)
        val deep=frame(reader);assertEquals(112,deep.initialPosition.first);reader.presented(deep.ticket);runCurrent()
        reader.navigate(EpubTarget(paths[1],"a2900"));runCurrent();assertEquals(1,frame(reader).spineIndex)
        reader.navigate(EpubTarget(paths[0],"a10"));runCurrent();assertEquals(0,frame(reader).spineIndex);assertEquals(10,frame(reader).initialPosition.first)
        val old=reader.state.value;reader.navigate(EpubTarget(EpubEntryPath("foreign.xhtml")));assertEquals(old,reader.state.value)
    } }
    @Test fun finalWindowStopsAtRealChapterEndAndNextEntersNextSpine() = runTest { use { reader, _, _, _ ->
        reader.navigate(EpubTarget(paths[0],"a3000"));runCurrent();reader.presented(frame(reader).ticket);runCurrent()
        assertEquals(3001,frame(reader).chapter.endBlock);reader.next();runCurrent()
        assertEquals(1,frame(reader).spineIndex);assertEquals(0,frame(reader).chapter.startBlock)
    } }
    @Test fun firstAndLastPublicationBoundariesDoNothingSafely() = runTest { use { reader, _, _, _ ->
        assertFalse(reader.canPrevious);val first=reader.state.value;reader.previous();assertEquals(first,reader.state.value)
        reader.navigate(EpubTarget(paths[1],"a3000"));runCurrent();reader.presented(frame(reader).ticket);runCurrent()
        assertFalse(reader.canNext);val last=reader.state.value;reader.next();assertEquals(last,reader.state.value)
    } }
    @Test fun deepPositionSurvivesMarginsFontResizeAndPresentationRecreation() = runTest { use { reader, _, store, _ ->
        reader.navigate(EpubTarget(paths[0],"a2800"));runCurrent();val ready=frame(reader)
        reader.report(ready.ticket,ready.initialPosition.first,7)
        val expected=ready.chapter.locator(ready.initialPosition.first,7)
        for(settings in listOf(EpubReaderSettings(fontSize=28),EpubReaderSettings(margin=36),EpubReaderSettings())){
            reader.presentationChanged(settings);val current=frame(reader)
            assertEquals(expected,current.chapter.locator(current.initialPosition.first,current.initialPosition.second))
        }
        reader.presentationChanged();reader.flush();runCurrent();assertEquals(expected,store.values[id]!!.locator)
    } }
    @Test fun savedDeepLocatorReopensWithoutPublicationStateLeakage() = runTest { use { reader, _, store, _ ->
        reader.navigate(EpubTarget(paths[0],"a2800"));runCurrent();val deep=frame(reader);reader.report(deep.ticket,deep.initialPosition.first,7);reader.close();runCurrent()
        val saved=assertNotNull(store.values[id]);val writer=ProgressPersistence(store,StandardTestDispatcher(testScheduler)){100}
        val fresh=EpubReaderController(Doc(),id,backgroundScope,Parser(),writer)
        val foreignId=id.copy(publicationId=publication.copy(localId="another"))
        val other=EpubReaderController(Doc(foreignId.publicationId),foreignId,backgroundScope,Parser(),writer)
        try {fresh.initialize(saved);assertEquals(saved.locator,frame(fresh).chapter.locator(frame(fresh).initialPosition.first,frame(fresh).initialPosition.second))
            other.initialize(saved);assertEquals(0,frame(other).chapter.startBlock);assertEquals(0 to 0,frame(other).initialPosition)
        }finally{fresh.close();other.close();writer.close();writer.awaitClosed()}
    } }
    @Test fun outdatedDeepElementFallsBackNearTheSavedEnd() = runTest {
        val saved=ReadingProgress(id,ReadingLocator.Epub(paths[1],listOf(0,19_999),0,0.999),0.999,1)
        val reader=EpubReaderController(Doc(),id,backgroundScope,Parser())
        try{reader.initialize(saved);assertEquals(2944,frame(reader).chapter.startBlock);assertTrue(frame(reader).initialPosition.first>40)}finally{reader.close()}
    }
    @Test fun presentationChangeDuringPendingWindowRetiresTargetWithoutSavingIt() = runTest { use { reader, parser, store, _ ->
        reader.report(frame(reader).ticket,12,5);reader.flush();runCurrent();val saved=store.values[id]
        val gate=CompletableDeferred<Unit>();parser.action={_, query -> if(query is EpubWindowRequest.Anchor)withContext(NonCancellable){gate.await()}}
        reader.navigate(EpubTarget(paths[0],"a2800"));runCurrent()
        // Explicit chapter loading keeps its intended semantic target and new settings.
        reader.presentationChanged(EpubReaderSettings(fontSize=26));gate.complete(Unit);runCurrent()
        assertEquals(26,reader.settings.value.fontSize);reader.flush();runCurrent();assertEquals(saved,store.values[id])
        reader.presented(frame(reader).ticket);reader.flush();runCurrent();assertNotEquals(saved,store.values[id])
    } }
    @Test fun resizeDuringLookaheadCancelsObsoleteBufferButKeepsLivePosition() = runTest { use { reader, parser, _, _ ->
        val gate=CompletableDeferred<Unit>();parser.action={_, _ -> withContext(NonCancellable){gate.await()}}
        reader.report(frame(reader).ticket,150,6);runCurrent();reader.presentationChanged();gate.complete(Unit);runCurrent()
        val current=frame(reader);assertEquals(listOf(0,150),current.chapter.locator(current.initialPosition.first,current.initialPosition.second).elementPath);bounds(reader)
    } }
    @Test fun rapidSupersessionSerializesParseAndRejectsLateResults() = runTest { use { reader, parser, store, _ ->
        val gate=CompletableDeferred<Unit>();var first=true
        parser.action={_, _ -> if(first){first=false;withContext(NonCancellable){gate.await()}}}
        reader.navigate(EpubTarget(paths[0],"a1000"));runCurrent()
        repeat(30){reader.navigate(EpubTarget(paths[0],"a${2000+it}"));runCurrent()}
        assertEquals(1,parser.active);assertEquals(1,parser.peak);gate.complete(Unit);runCurrent()
        val target=frame(reader);assertEquals(listOf(0,2029),target.chapter.locator(target.initialPosition.first,0).elementPath)
        assertTrue(store.values.isEmpty());bounds(reader)
    } }
    @Test fun closeDuringPendingWindowReleasesCacheAndRejectsLatePresentation() = runTest { use { reader, parser, store, doc ->
        val gate=CompletableDeferred<Unit>();parser.action={_, _ -> withContext(NonCancellable){gate.await()}}
        val stale=frame(reader).ticket;reader.navigate(EpubTarget(paths[0],"a2800"));runCurrent()
        reader.close();gate.complete(Unit);runCurrent();reader.presented(stale)
        assertEquals(0,reader.retainedChapters);assertEquals(0,reader.retainedBlocks);assertEquals(0,parser.active)
        assertEquals(1,doc.closes);assertTrue(store.values.isEmpty());assertIs<EpubReaderState.Loading>(reader.state.value)
    } }
    @Test fun repeatedNavigationAndPublicationLengthCannotGrowTheCache() = runTest {
        for(count in listOf(2049,3001,6000))use(count){reader, parser, _, _ ->
            repeat(60){reader.next();runCurrent();reader.presented(frame(reader).ticket);runCurrent();bounds(reader)}
            repeat(60){reader.previous();runCurrent();reader.presented(frame(reader).ticket);runCurrent();bounds(reader)}
            assertEquals(1,parser.peak)
        }
    }
    @Test fun exactSharedElementBoundaryPrefersTheFollowingWindowRegardlessOfCacheOrder()=runTest {
        val reader=EpubReaderController(Doc(),id,backgroundScope,Parser(257,sharedElement=true))
        try{
            reader.initialize(null);runCurrent();reader.report(frame(reader).ticket,150,5);runCurrent()
            reader.report(frame(reader).ticket,10,3);runCurrent()
            assertEquals(0,frame(reader).chapter.startBlock)
            reader.navigate(EpubTarget(paths[0],"a128"));runCurrent()
            assertEquals(128,frame(reader).chapter.startBlock)
            assertEquals(0 to 0,frame(reader).initialPosition)
        }finally{reader.close()}
    }
    @Test fun unpresentedTargetSurvivesRemountWithoutSavingItsRequestedLocator()=runTest { use {reader,_,store,_->
        reader.navigate(EpubTarget(paths[0],"a2800"));runCurrent();val target=frame(reader)
        val expected=target.chapter.locator(target.initialPosition.first,target.initialPosition.second)
        reader.presentationChanged();val remounted=frame(reader)
        assertEquals(expected,remounted.chapter.locator(remounted.initialPosition.first,remounted.initialPosition.second))
        reader.flush();runCurrent();assertTrue(store.values.isEmpty())
        reader.presented(remounted.ticket);reader.flush();runCurrent();assertEquals(expected,store.values[id]!!.locator)
    } }
    @Test fun controllerRejectsOversizedWindowModelsBeforePublication()=runTest {
        for(blocks in listOf(
            (0..128).map{EpubBlock(listOf(0,it),0,EpubBlockKind.PARAGRAPH,listOf(EpubRun("x")),it)},
            listOf(EpubBlock(listOf(0,0),0,EpubBlockKind.PARAGRAPH,listOf(EpubRun("x".repeat(8193))),0)),
            listOf(EpubBlock(listOf(0,0),0,EpubBlockKind.PARAGRAPH,List(8192){EpubRun("x")},0),EpubBlock(listOf(0,1),0,EpubBlockKind.PARAGRAPH,listOf(EpubRun("y")),8192))
        )){
            val parser=object:EpubParser {
                override suspend fun chapter(document:EpubDocument,path:EpubEntryPath)=EpubChapter(path,blocks,emptyMap())
                override suspend fun toc(document:EpubDocument)=emptyList<EpubTocEntry>()
            }
            val doc=Doc();val reader=EpubReaderController(doc,id,backgroundScope,parser)
            try{assertEquals(EpubFailure.LIMIT,assertFailsWith<EpubException>{reader.initialize(null)}.failure);assertEquals(0,reader.retainedChapters)}finally{reader.close()}
            assertEquals(1,doc.closes)
        }
    }
}
