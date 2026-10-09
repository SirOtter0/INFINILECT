// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Files
import java.nio.file.Path
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import kotlin.test.*
import org.infinilect.app.reader.epub.*
import org.infinilect.app.reader.*
import org.infinilect.core.*

/** Original fixtures; operation counts, quotas and forced interleavings, never timing assertions. */
class EpubSemanticCacheTest {
    private val paths = listOf("one", "two", "three").map { EpubEntryPath("OPS/$it.xhtml") }
    private fun paragraphs(count: Int) = (0 until count).joinToString("") { "<p id=\"p$it\">Original $it 📖</p>" }
    private inner class Doc(root: Path, quota: Long, body: String) : EpubDocument, EpubSemanticCacheOwner {
        val cache = EpubSemanticCache(root, quota)
        val bodies = paths.associateWith { body }.toMutableMap()
        val opens = mutableMapOf<EpubEntryPath, Int>()
        val handles = mutableListOf<EpubBytes>()
        var beforeRead: suspend () -> Unit = {}
        override val publicationId = epubId
        override val packagePath = EpubEntryPath("OPS/package.opf")
        override val metadata = EpubMetadata("original", "Original", listOf("en"), null)
        override val manifest = paths.mapIndexed { i, p -> EpubManifestItem("c$i", p, "application/xhtml+xml") } + EpubManifestItem("image", EpubEntryPath("OPS/image.png"), "image/png")
        override val spine = paths.indices.map { EpubSpineItem("c$it") }
        override val navigationItemId = "nav"
        override fun semanticCache() = cache
        override suspend fun openResource(path: EpubEntryPath): ResourceContent {
            opens[path] = (opens[path] ?: 0) + 1
            return EpubBytes(("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Original</title></head><body>${bodies.getValue(path)}</body></html>").encodeToByteArray(), beforeRead = { beforeRead() }).also(handles::add)
        }
        override fun close() = cache.invalidate()
    }
    private suspend fun use(body: String = paragraphs(3269), quota: Long = EpubSemanticCachePolicy.CHAPTER_BYTES, action: suspend (Doc, Path) -> Unit) {
        val root = Files.createTempDirectory("epub-semantic-test")
        val doc = Doc(root, quota, body)
        try { action(doc, root) } finally {
            doc.close(); doc.cache.closeAndJoin()
            assertTrue(Files.list(root).use { it.toList().isEmpty() })
            assertTrue(doc.handles.all { it.closed })
            root.toFile().deleteRecursively()
        }
    }
    @Test fun consecutiveForwardAndReverseWindowsParseTheSourceOnlyOnce() = runBlocking<Unit> { use { doc, _ ->
        val parser = BoundedEpubParser()
        for (index in (0..3268 step 128).toList() + (3268 downTo 0 step 128).toList()) {
            val window = parser.window(doc, paths[0], EpubWindowRequest.Block(index))
            assertEquals((window.startBlock until window.endBlock).map { "Original $it 📖" }, window.blocks.map { it.text })
            assertTrue(window.blocks.size <= 128); assertTrue(window.blocks.sumOf { it.text.length } <= 65_536)
        }
        assertEquals(1, doc.opens[paths[0]]); assertEquals(1, doc.cache.builds); assertTrue(doc.cache.hits >= 50)
    } }
    @Test fun warmDeepAnchorsLocatorsAndEndNeverReparseXhtml() = runBlocking<Unit> { use { doc, _ ->
        val parser = BoundedEpubParser(); parser.window(doc, paths[0])
        for (index in listOf(2800, 4, 3200)) {
            val window = parser.window(doc, paths[0], EpubWindowRequest.Anchor("p$index"))
            val saved = window.locator(index - window.startBlock, 7)
            val restored = parser.window(doc, paths[0], EpubWindowRequest.Locator(saved))
            assertEquals(saved, restored.locator(restored.locate(saved).first, restored.locate(saved).second))
        }
        assertEquals(3269, parser.window(doc, paths[0], EpubWindowRequest.End).endBlock)
        val stale = ReadingLocator.Epub(paths[0], listOf(0,19_999), 0, .9)
        assertTrue(parser.window(doc, paths[0], EpubWindowRequest.Locator(stale)).startBlock > 2800)
        assertEquals(1, doc.opens[paths[0]])
    } }
    @Test fun missingAnchorDoesNotEvictOrLeakAValidIndex() = runBlocking<Unit> { use { doc, _ ->
        val parser = BoundedEpubParser(); parser.window(doc, paths[0])
        repeat(10) { assertEquals(EpubFailure.INVALID, assertFailsWith<EpubException> { parser.window(doc, paths[0], EpubWindowRequest.Anchor("absent")) }.failure) }
        parser.window(doc, paths[0], EpubWindowRequest.End)
        assertEquals(1, doc.cache.retainedChapters); assertEquals(1, doc.opens[paths[0]])
    } }
    @Test fun serializationPreservesStyleImageListUnicodeAndElementOffsets() = runBlocking<Unit> { use("<h2 id=\"123\">Original 📖</h2><ol start=\"4\"><li>A <em>bold <strong>résumé</strong></em> <a href=\"two.xhtml#p0\">link</a></li></ol><blockquote>Quote</blockquote><p>Before<img src=\"image.png\" alt=\"Original\"/>after</p><pre> a\n b </pre><hr/>") { doc, _ ->
        val parser = BoundedEpubParser(); val expected = parser.chapter(doc, paths[0])
        assertEquals(expected, parser.window(doc, paths[0])); assertEquals(expected, parser.window(doc, paths[0]))
    } }
    @Test fun twoChapterLruEvictsBeforeConstructionAndNeverGrowsWithNavigation() = runBlocking<Unit> { use { doc, root ->
        val parser = BoundedEpubParser()
        for (path in listOf(paths[0],paths[1],paths[0],paths[2])) parser.window(doc,path)
        assertEquals(1, doc.opens[paths[0]]); assertEquals(1,doc.opens[paths[1]])
        parser.window(doc,paths[0]); assertEquals(1,doc.opens[paths[0]])
        parser.window(doc,paths[1]); assertEquals(2,doc.opens[paths[1]])
        repeat(20) {
            parser.window(doc, paths[it % paths.size], EpubWindowRequest.End)
            assertEquals(2,doc.cache.retainedChapters)
            assertTrue(doc.cache.retainedWindowDescriptors <= 2 * EpubWindowPolicy.INDEX_ENTRIES)
            assertTrue(doc.cache.retainedAnchorRecords <= 2 * 2 * 4096)
            val files = Files.list(root).use { it.toList() }
            assertEquals(2,files.size); assertTrue(files.sumOf(Files::size) <= EpubSemanticCachePolicy.DOCUMENT_BYTES)
            assertTrue(files.all { Files.size(it) <= EpubSemanticCachePolicy.CHAPTER_BYTES })
        }
    } }
    @Test fun fullQuotaFallsBackWithoutTruncatingOrRepeatedFailedBuilds() = runBlocking<Unit> { use(quota = 128) { doc, root ->
        val parser = BoundedEpubParser()
        assertEquals(128, parser.window(doc,paths[0]).blocks.size)
        assertEquals(3269,parser.window(doc,paths[0],EpubWindowRequest.End).endBlock)
        assertEquals(3,doc.opens[paths[0]]) // attempted index+fallback, then only fallback
        assertEquals(0,doc.cache.retainedChapters); assertTrue(Files.list(root).use { it.toList().isEmpty() })
    } }
    @Test fun corruptChecksumRebuildsFromValidatedSource() = runBlocking<Unit> { use { doc, root ->
        val parser=BoundedEpubParser(); parser.window(doc,paths[0])
        val file=Files.list(root).use { it.toList().single() }
        RandomAccessFile(file.toFile(),"rw").use { it.seek(it.length()-1); it.writeByte(42) }
        val last=parser.window(doc,paths[0],EpubWindowRequest.End)
        assertEquals("Original 3268 📖",last.blocks.last().text); assertEquals(2,doc.opens[paths[0]])
        assertEquals(1,Files.list(root).use { it.count() }.toInt())
    } }
    @Test fun missingCacheFileRebuildsSafely() = runBlocking<Unit> { use { doc, root ->
        val parser=BoundedEpubParser(); parser.window(doc,paths[0]); Files.delete(Files.list(root).use { it.toList().single() })
        assertEquals(3269,parser.window(doc,paths[0],EpubWindowRequest.End).totalBlocks)
        assertEquals(2,doc.opens[paths[0]])
    } }
    @Test fun corruptedCacheCannotBypassSourceSecurityValidation() = runBlocking<Unit> { use { doc, root ->
        val parser=BoundedEpubParser(); parser.window(doc,paths[0]); Files.write(Files.list(root).use { it.toList().single() }, byteArrayOf(1))
        doc.bodies[paths[0]]="<script>alert(1)</script><p>unsafe</p>"
        assertFailsWith<EpubException> { parser.window(doc,paths[0]) }
        assertEquals(0,doc.cache.retainedChapters); assertTrue(Files.list(root).use { it.toList().isEmpty() })
    } }
    @Test fun cancellationDuringConstructionClosesSourceAndRemovesPartialFile() = runBlocking<Unit> { use { doc, root ->
        val entered=CompletableDeferred<Unit>(); doc.beforeRead={entered.complete(Unit);awaitCancellation()}
        val task=launch { BoundedEpubParser().window(doc,paths[0]) }
        entered.await(); assertEquals(1,Files.list(root).use { it.count() }.toInt())
        task.cancelAndJoin(); assertTrue(doc.handles.single().closed)
        assertEquals(0,doc.cache.retainedChapters); assertTrue(Files.list(root).use { it.toList().isEmpty() })
    } }
    @Test fun closeDuringConstructionCancelsWorkDrainsAndPreventsNewReads() = runBlocking<Unit> { use { doc, root ->
        val entered=CompletableDeferred<Unit>(); doc.beforeRead={entered.complete(Unit);awaitCancellation()}
        val task=launch { BoundedEpubParser().window(doc,paths[0]) }
        entered.await(); doc.close(); doc.cache.closeAndJoin(); task.join()
        assertTrue(task.isCancelled); assertTrue(doc.handles.single().closed)
        assertFailsWith<CancellationException> { BoundedEpubParser().window(doc,paths[0]) }
        assertTrue(Files.list(root).use { it.toList().isEmpty() })
    } }
    @Test fun simultaneousEquivalentRequestsBuildOnlyOneIndex() = runBlocking<Unit> { use { doc, _ ->
        val parser=BoundedEpubParser()
        val values=List(12){ async(Dispatchers.Default){parser.window(doc,paths[0],EpubWindowRequest.Block(2800))} }.awaitAll()
        assertTrue(values.all { it == values.first() }); assertEquals(1,doc.opens[paths[0]]);assertEquals(1,doc.cache.builds)
    } }
    @Test fun oversizedIndividualBlockLeavesNoPartialIndex() = runBlocking<Unit> { use("<p>${"<span>${"x".repeat(256)}</span>".repeat(33)}</p>") { doc, root ->
        assertEquals(EpubFailure.LIMIT, assertFailsWith<EpubException>{BoundedEpubParser().window(doc,paths[0])}.failure)
        assertEquals(0,doc.cache.retainedChapters);assertTrue(Files.list(root).use { it.toList().isEmpty() })
    } }
    @Test fun invalidLateContentNeverPublishesAnEarlyWindow() = runBlocking<Unit> { use(paragraphs(3001)+"<p><a href=\"https://example.invalid/\">remote</a></p>") { doc, root ->
        assertFailsWith<EpubException>{BoundedEpubParser().window(doc,paths[0])}
        assertEquals(0,doc.cache.retainedChapters);assertTrue(Files.list(root).use { it.toList().isEmpty() })
    } }
    @Test fun preparedDocumentCachesOnlyInsideItsPrivateSessionAndFinalCloseDeletesIt() = runBlocking<Unit> {
        val root=Files.createTempDirectory("epub-semantic-owner");val owner=FileEpubPreparer(root)
        val fixture=epub2Fixture();fixture.change("OPS/chapter.xhtml"){it.replace(Regex("<body>.*</body>",RegexOption.DOT_MATCHES_ALL),"<body>${paragraphs(3001)}</body>")}
        try {
            val doc=owner.prepare(epubPublication,epubResource,epubLoader(EpubBytes(fixture.zip())))
            BoundedEpubParser().window(doc,EpubEntryPath("OPS/chapter.xhtml"))
            val files=Files.walk(root).use{it.filter{p->Files.isRegularFile(p) && p.fileName.toString().startsWith("epub-semantic-")}.toList()}
            assertEquals(1,files.size);assertTrue(files.single().parent.fileName.toString().startsWith("session-"))
            doc.close();doc.close();owner.close();owner.awaitClosed()
            assertTrue(Files.list(root).use{it.toList().isEmpty()})
        } finally {owner.close();owner.awaitClosed();root.toFile().deleteRecursively()}
    }
    @Test fun staleUnlockedSemanticFilesAreRemovedAndNeverReused() = runBlocking<Unit> {
        val root=Files.createTempDirectory("epub-semantic-stale");val owner=FileEpubPreparer(root)
        val stale=Files.createDirectory(root.resolve("session-00000000-0000-0000-0000-000000000000"))
        Files.createFile(stale.resolve(".owner.lock"));Files.write(stale.resolve("epub-semantic-00000000-0000-0000-0000-000000000000.bin"),byteArrayOf(1,2,3))
        try {
            val doc=owner.prepare(epubPublication,epubResource,epubLoader(EpubBytes(epub2Fixture().zip())))
            assertFalse(Files.exists(stale));assertEquals("Hello, 世界 📖",BoundedEpubParser().window(doc,EpubEntryPath("OPS/chapter.xhtml")).blocks.first().text)
            doc.close()
        } finally {owner.close();owner.awaitClosed();assertTrue(Files.list(root).use{it.toList().isEmpty()});root.toFile().deleteRecursively()}
    }
    @Test fun constructionReservesQuotaBeforeWritingAndCancellationReleasesItsSlot() = runBlocking<Unit> { use { doc, root ->
        val parser=BoundedEpubParser();parser.window(doc,paths[0]);parser.window(doc,paths[1])
        val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        val task=launch {
            doc.cache.window(doc,paths[2],EpubWindowRequest.Block(0)){writer->
                writer.accept(EpubBlock(listOf(0,0),0,EpubBlockKind.PARAGRAPH,listOf(EpubRun("Original")),0),0,1)
                entered.complete(Unit);gate.await()
                EpubSemanticMetadata(emptyMap(),emptyMap(),1,8)
            }
        }
        entered.await()
        val files=Files.list(root).use{it.toList()}
        assertEquals(2,files.size);assertEquals(1,doc.cache.retainedChapters)
        assertTrue(files.sumOf(Files::size) <= EpubSemanticCachePolicy.DOCUMENT_BYTES)
        assertTrue(files.all{Files.size(it)<=EpubSemanticCachePolicy.CHAPTER_BYTES})
        task.cancelAndJoin();assertEquals(1,Files.list(root).use{it.count()}.toInt())
        assertEquals(3269,parser.window(doc,paths[2],EpubWindowRequest.End).totalBlocks)
        assertEquals(2,doc.cache.retainedChapters)
    } }
    @Test fun failedDeletionKeepsSessionQuotaAcrossRetiredDocuments() {
        val root=Files.createTempDirectory("epub-semantic-reservations");val files=EpubSemanticFiles(root)
        val reserved=mutableListOf<Path>()
        try {
            repeat(EpubSemanticCachePolicy.SESSION_FILES) {
                val file=files.create();reserved.add(file)
                // Force an OS deletion failure without permissions, timing or sleeps.
                Files.delete(file);Files.createDirectory(file);Files.write(file.resolve("held"),byteArrayOf(1))
                assertFailsWith<java.nio.file.DirectoryNotEmptyException>{files.delete(file)}
            }
            assertEquals(4,files.retainedFiles)
            repeat(10){assertFailsWith<EpubSemanticCacheUnavailable>{files.create()}}
            assertEquals(4,Files.list(root).use{it.count()}.toInt())
            for(file in reserved){Files.delete(file.resolve("held"));files.delete(file)}
            assertEquals(0,files.retainedFiles)
            files.delete(files.create());assertEquals(0,files.retainedFiles)
        } finally {root.toFile().deleteRecursively()}
    }
    @Test fun concurrentDocumentReservationsCannotExceedSessionQuota()=runBlocking<Unit> {
        val root=Files.createTempDirectory("epub-semantic-concurrent");val files=EpubSemanticFiles(root)
        val gate=CompletableDeferred<Unit>()
        try {
            val jobs=List(12){async(Dispatchers.Default){gate.await();try{files.create()}catch(_:EpubSemanticCacheUnavailable){null}}}
            gate.complete(Unit);val allocated=jobs.awaitAll().filterNotNull()
            assertEquals(4,allocated.size);assertEquals(4,files.retainedFiles)
            assertEquals(4,Files.list(root).use{it.count()}.toInt())
            allocated.forEach(files::delete);assertEquals(0,files.retainedFiles)
        } finally {root.toFile().deleteRecursively()}
    }
}
