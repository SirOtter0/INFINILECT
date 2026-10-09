// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

/** Boundary requests use existing bounded jobs/tickets, not a second navigator. */
@OptIn(ExperimentalCoroutinesApi::class)
class EpubViewportNavigationTest {
    private val id = PublicationId(SourceId("original"), "viewport-boundaries")
    private val first = EpubEntryPath("OPS/first.xhtml")
    private val second = EpubEntryPath("OPS/second.xhtml")
    private class Store : ReadingProgressStore {
        var saved: ReadingProgress? = null
        override suspend fun get(id: ReadingProgressId) = saved?.takeIf { it.id == id }
        override suspend fun save(progress: ReadingProgress): Boolean { saved = progress; return true }
        override suspend fun remove(id: ReadingProgressId) = false
    }
    private inner class Doc : EpubDocument {
        override val publicationId = id
        override val packagePath = EpubEntryPath("OPS/book.opf")
        override val metadata = EpubMetadata("original", "Original boundaries", listOf("en"), null)
        override val manifest = listOf(EpubManifestItem("a",first,"application/xhtml+xml"),EpubManifestItem("b",second,"application/xhtml+xml"))
        override val spine = listOf(EpubSpineItem("a"),EpubSpineItem("b"))
        override val navigationItemId = "nav"
        var closed = 0
        override suspend fun openResource(path: EpubEntryPath): ResourceContent = error("Synthetic only")
        override fun close() { closed++ }
    }
    private inner class Parser : EpubParser {
        var gate: CompletableDeferred<Unit>? = null
        var gatedIndex = 256
        var fail = false
        var calls = 0
        var active = 0
        var maximum = 0
        override suspend fun toc(document: EpubDocument) = emptyList<EpubTocEntry>()
        override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter = error("Use windows")
        override suspend fun window(document: EpubDocument, path: EpubEntryPath, request: EpubWindowRequest): EpubChapter {
            calls++; active++; maximum=maxOf(maximum,active)
            try {
                val count=if(path==first)3001 else 4
                val index=when(request) {
                    is EpubWindowRequest.Block -> request.index
                    EpubWindowRequest.End -> count-1
                    is EpubWindowRequest.Locator -> request.value.elementPath.last()
                    is EpubWindowRequest.Anchor -> request.value.toInt()
                }
                if(index==gatedIndex) { gate?.await(); if(fail) error("Controlled failure") }
                val start=index/128*128
                return EpubChapter(path,(start until minOf(start+128,count)).map {
                    EpubBlock(listOf(0,it),0,EpubBlockKind.PARAGRAPH,listOf(EpubRun("Original $it")),it*20)
                },emptyMap(),start,count,count*20)
            } finally { active-- }
        }
    }
    private fun ready(reader: EpubReaderController)=assertIs<EpubReaderState.Ready>(reader.state.value)
    @Test fun allLongWindowsCanBeReachedInBothDirectionsWithoutSkippingBlocks()=runTest {
        val parser=Parser(); val doc=Doc(); val reader=EpubReaderController(doc,ReadingProgressId(id,"book",PublicationFormat.EPUB),backgroundScope,parser)
        try {
            reader.initialize(null)
            // Do not run automatic lookahead first; the boundary owns its exact target.
            val seen=mutableSetOf<Int>()
            while(ready(reader).chapter.path==first) {
                val current=ready(reader); seen.addAll(current.chapter.blocks.map { it.elementPath.last() })
                reader.scrollBoundary(current.ticket,true); runCurrent()
                assertTrue(reader.retainedChapters<=2); assertTrue(reader.retainedBlocks<=256)
            }
            assertEquals((0..3000).toSet(),seen)
            reader.scrollBoundary(ready(reader).ticket,false); runCurrent()
            assertEquals(first,ready(reader).chapter.path)
            assertEquals(3000,ready(reader).chapter.blocks[ready(reader).initialPosition.first].elementPath.last())
            val reverse=mutableSetOf<Int>()
            while(true) {
                val current=ready(reader); reverse.addAll(current.chapter.blocks.map { it.elementPath.last() })
                if(current.chapter.startBlock==0) break
                reader.scrollBoundary(current.ticket,false); runCurrent()
            }
            assertEquals(seen,reverse); assertEquals(1,parser.maximum)
        } finally { reader.close() }; assertEquals(1,doc.closed)
    }
    @Test fun rapidBoundaryRequestsDeduplicateAndOnlyPresentedTargetsSave()=runTest {
        val store=Store(); val writer=ProgressPersistence(store,StandardTestDispatcher(testScheduler)){testScheduler.currentTime+1}
        val parser=Parser(); val reader=EpubReaderController(Doc(),ReadingProgressId(id,"book",PublicationFormat.EPUB),backgroundScope,parser,writer)
        try {
            reader.initialize(null); runCurrent()
            val current=ready(reader); parser.gate=CompletableDeferred(); reader.report(current.ticket,200,0); reader.flush(); runCurrent()
            val before=store.saved
            runCurrent()
            repeat(20) { reader.scrollBoundary(ready(reader).ticket,true) }; runCurrent()
            val calls=parser.calls
            repeat(20) { reader.scrollBoundary(ready(reader).ticket,true) }; runCurrent()
            assertEquals(calls,parser.calls); assertEquals(before,store.saved); assertEquals(1,parser.maximum)
            parser.gate!!.complete(Unit); runCurrent()
            assertEquals(before,store.saved)
            val incoming=ready(reader); assertEquals(256,incoming.chapter.startBlock)
            reader.report(current.ticket,220,0); reader.flush(); runCurrent(); assertEquals(before,store.saved)
            reader.presented(incoming.ticket); reader.flush(); runCurrent()
            assertEquals(256,assertIs<ReadingLocator.Epub>(assertNotNull(store.saved).locator).elementPath.last())
        } finally { reader.close(); writer.close() }
    }
    @Test fun failedBoundaryKeepsPresentedLocatorAndRetryDoesNotSkipTarget()=runTest {
        val parser=Parser(); val reader=EpubReaderController(Doc(),ReadingProgressId(id,"book",PublicationFormat.EPUB),backgroundScope,parser)
        try {
            reader.initialize(null); runCurrent(); val old=ready(reader)
            parser.fail=true; reader.scrollBoundary(old.ticket,true); runCurrent()
            val failed=ready(reader); assertEquals(old.chapter,failed.chapter); assertNotNull(failed.error)
            assertTrue(reader.canRetry); parser.fail=false; reader.retry(); runCurrent()
            assertEquals(256,ready(reader).chapter.startBlock); assertFalse(reader.canRetry)
        } finally { reader.close() }
    }
    @Test fun staleBoundaryResizeCloseAndFinalBoundaryCannotOverwritePosition()=runTest {
        val parser=Parser(); val doc=Doc(); val reader=EpubReaderController(doc,ReadingProgressId(id,"book",PublicationFormat.EPUB),backgroundScope,parser)
        reader.initialize(null); runCurrent(); val old=ready(reader)
        reader.presentationChanged(); val fresh=ready(reader)
        reader.scrollBoundary(old.ticket,true); assertEquals(fresh,ready(reader))
        reader.chapter(1); runCurrent(); val end=ready(reader)
        reader.scrollBoundary(end.ticket,true); assertEquals(end,ready(reader))
        reader.chapter(0); runCurrent(); parser.gate=CompletableDeferred(); parser.gatedIndex=ready(reader).chapter.endBlock
        reader.scrollBoundary(ready(reader).ticket,true); runCurrent()
        assertEquals(1,parser.active); reader.close(); runCurrent()
        assertEquals(0,parser.active); assertEquals(0,reader.retainedChapters)
        parser.gate!!.complete(Unit); runCurrent(); reader.scrollBoundary(fresh.ticket,true)
        assertIs<EpubReaderState.Loading>(reader.state.value); assertEquals(1,doc.closed)
    }
}
