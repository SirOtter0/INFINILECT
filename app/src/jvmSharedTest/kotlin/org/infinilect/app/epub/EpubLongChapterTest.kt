// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import org.infinilect.app.reader.EpubException
import org.infinilect.app.reader.EpubFailure

class EpubLongChapterTest {
    private suspend fun prepared(body: String, epub3: Boolean = false, navAnchor: String = "start", action: suspend (EpubDocument) -> Unit) {
        val root = Files.createTempDirectory("epub-long-chapter")
        val owner = FileEpubPreparer(root)
        val fixture = if(epub3) EpubFixture() else epub2Fixture()
        // Replace only body content; fixture metadata/navigation remain original synthetic material.
        fixture.change("OPS/chapter.xhtml") { it.replace(Regex("<body>.*</body>", RegexOption.DOT_MATCHES_ALL), "<body>$body</body>") }
        fixture.change(if(epub3) "OPS/nav.xhtml" else "OPS/Nav/toc.ncx") { it.replace("#start", "#$navAnchor") }
        val input = EpubBytes(fixture.zip())
        try {
            val document = owner.prepare(epubPublication, epubResource, epubLoader(input))
            try { action(document) } finally { document.close() }
        } finally {
            owner.close(); owner.awaitClosed(); assertTrue(input.closed)
            assertTrue(payloads(root).isEmpty()); root.toFile().deleteRecursively()
        }
    }
    @Test fun realReaderCanEnterAChapterBeyondTheOldBlockCeiling() = runBlocking<Unit> {
        prepared((0..3000).joinToString("") { "<p id=\"${if (it == 0) "start" else "p$it"}\">Original passage $it 📖</p>" }) { document ->
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val reader = EpubReaderController(document, ReadingProgressId(document.publicationId, "book", PublicationFormat.EPUB), scope)
            try {
                reader.initialize(null)
                val ready = assertIs<EpubReaderState.Ready>(reader.state.value)
                assertTrue(ready.chapter.blocks.size <= 256)
                assertEquals("Original passage 0 📖", ready.chapter.blocks.first().text)
            } finally { reader.close(); scope.cancel() }
        }
    }
    private val path = EpubEntryPath("OPS/chapter.xhtml")
    private fun paragraphs(count: Int) = (0 until count).joinToString("") { "<p id=\"${if (it == 0) "start" else "p$it"}\">Original passage $it 📖</p>" }
    private fun bounded(window: EpubChapter) {
        assertTrue(window.blocks.size <= EpubWindowPolicy.BLOCKS)
        assertTrue(window.blocks.sumOf { it.text.length } <= EpubWindowPolicy.TEXT_UNITS)
        assertTrue(window.endBlock <= window.totalBlocks)
    }
    private suspend fun all(document: EpubDocument): List<String> {
        val parser = BoundedEpubParser(); val text = mutableListOf<String>()
        var next = 0; var total: Int
        do {
            val window = parser.window(document, path, EpubWindowRequest.Block(next)); bounded(window)
            assertEquals(next, window.startBlock); total = window.totalBlocks
            text += window.blocks.map { it.text }; next = window.endBlock
        } while (next < total)
        assertEquals(total, text.size); return text
    }
    @Test fun blockCountsAroundTheOldCeilingHaveCompleteCoverage() = runBlocking<Unit> {
        for (count in listOf(2047, 2048, 2049)) prepared(paragraphs(count)) { doc ->
            assertEquals((0 until count).map { "Original passage $it 📖" }, all(doc))
        }
    }
    @Test fun threeThousandPlusBlocksHaveNeitherMissingNorDuplicatedText() = runBlocking<Unit> {
        prepared(paragraphs(3269)) { doc -> assertEquals((0 until 3269).map { "Original passage $it 📖" }, all(doc)) }
    }
    @Test fun firstMiddleAndFinalWindowUseGlobalIndicesAndRealChapterEnd() = runBlocking<Unit> {
        prepared(paragraphs(3001)) { doc ->
            val parser = BoundedEpubParser()
            for (index in listOf(0, 127, 128, 1500, 2999, 3000)) {
                val window = parser.window(doc, path, EpubWindowRequest.Block(index)); bounded(window)
                assertTrue(index in window.startBlock until window.endBlock)
                assertEquals("Original passage $index 📖", window.blocks[index - window.startBlock].text)
            }
            val last = parser.window(doc, path, EpubWindowRequest.End)
            assertEquals(3001, last.endBlock); assertEquals("Original passage 3000 📖", last.blocks.last().text)
        }
    }
    @Test fun forwardThenBackwardWindowsReconstructTheSameSemanticText() = runBlocking<Unit> {
        prepared(paragraphs(3001)) { doc ->
            val parser = BoundedEpubParser(); var cursor = 3000
            while (true) {
                val window = parser.window(doc, path, EpubWindowRequest.Block(cursor)); bounded(window)
                assertEquals((window.startBlock until window.endBlock).map { "Original passage $it 📖" }, window.blocks.map { it.text })
                if (window.startBlock == 0) break
                cursor = window.startBlock - 1
            }
        }
    }
    @Test fun deepSemanticLocatorAndOldLocatorRestoreWithoutAWindowIdentifier() = runBlocking<Unit> {
        prepared(paragraphs(3001)) { doc ->
            val parser = BoundedEpubParser()
            val original = parser.window(doc, path, EpubWindowRequest.Block(2800))
            val saved = original.locator(2800 - original.startBlock, 12)
            val fresh = parser.window(doc, path, EpubWindowRequest.Locator(saved))
            assertEquals(saved, fresh.locator(fresh.locate(saved).first, fresh.locate(saved).second))
            assertEquals(listOf(0,2800), saved.elementPath); assertEquals(12L, saved.codePointOffset)
            val old = ReadingLocator.Epub(path, listOf(0,2800), 12, saved.chapterProgression)
            assertEquals(saved, parser.window(doc, path, EpubWindowRequest.Locator(old)).let { it.locator(it.locate(old).first, it.locate(old).second) })
        }
    }
    @Test fun laterAndEarlierNumericAnchorsResolveTheirContainingWindows() = runBlocking<Unit> {
        prepared(paragraphs(3001).replace("id=\"p2800\"", "id=\"2800\"")) { doc ->
            val parser = BoundedEpubParser()
            for (anchor in listOf("2800","start")) {
                val window = parser.window(doc, path, EpubWindowRequest.Anchor(anchor)); bounded(window)
                val position = assertNotNull(window.anchors[anchor])
                val at = window.locate(ReadingLocator.Epub(path,position.elementPath,position.codePointOffset.toLong(),0.0))
                assertEquals(if (anchor=="start") "Original passage 0 📖" else "Original passage 2800 📖",window.blocks[at.first].text)
            }
            assertEquals(EpubFailure.INVALID,assertFailsWith<EpubException> { parser.window(doc,path,EpubWindowRequest.Anchor("missing")) }.failure)
        }
    }
    @Test fun missingElementPathUsesWholeChapterProgressionRatherThanWindowProgression() = runBlocking<Unit> {
        prepared(paragraphs(3001)) { doc ->
            val parser = BoundedEpubParser()
            val total = parser.window(doc,path).totalCodePoints
            val stale = ReadingLocator.Epub(path,listOf(0,19_999),0,0.9)
            val restored = parser.window(doc,path,EpubWindowRequest.Locator(stale)); bounded(restored)
            val position = restored.locate(stale)
            val actual = restored.locator(position.first,position.second)
            assertTrue(restored.startBlock>2000); assertTrue(kotlin.math.abs(actual.chapterProgression-0.9) <= 1.0/total)
        }
    }
    @Test fun textBudgetCreatesWindowsEvenBelowTheBlockBudget() = runBlocking<Unit> {
        prepared((0 until 100).joinToString("") { "<p>${"x".repeat(7000)}</p>" }) { doc ->
            val parser=BoundedEpubParser();var cursor=0;var points=0
            while(cursor<100){val window=parser.window(doc,path,EpubWindowRequest.Block(cursor));bounded(window);assertTrue(window.blocks.size<=9);points+=window.blocks.sumOf{it.codePoints};cursor=window.endBlock}
            assertEquals(700_000,points)
        }
    }
    @Test fun appendEventBudgetBoundsRunObjectsIndependentlyOfText() = runBlocking<Unit> {
        prepared("<p>${"<span>x</span>".repeat(4096)}</p>".repeat(3)) { doc ->
            val parser=BoundedEpubParser();val first=parser.window(doc,path);bounded(first)
            assertEquals(2,first.blocks.size);assertEquals(3,first.totalBlocks)
            assertEquals(1,parser.window(doc,path,EpubWindowRequest.Block(2)).blocks.size)
        }
    }
    @Test fun oversizedSingleElementStillFailsWithoutTruncation() = runBlocking<Unit> {
        // Each XML span is legal; the combined semantic paragraph exceeds 8,192 units.
        prepared("<p>${"<span>${"x".repeat(256)}</span>".repeat(33)}</p>") { doc ->
            assertEquals(EpubFailure.LIMIT,assertFailsWith<EpubException>{BoundedEpubParser().window(doc,path)}.failure)
        }
    }
    @Test fun emptyChapterHasControlledFailureAndMinimalChapterIsComplete() = runBlocking<Unit> {
        prepared("<p> </p>") { doc -> assertEquals(EpubFailure.INVALID,assertFailsWith<EpubException>{BoundedEpubParser().window(doc,path)}.failure) }
        prepared("<p id=\"start\">📖</p>") { doc -> val window=BoundedEpubParser().window(doc,path);bounded(window);assertEquals(1,window.totalBlocks);assertEquals(1,window.totalCodePoints) }
    }
    @Test fun smallChapterWindowsMatchTheHistoricalModelAndLocatorsExactly() = runBlocking<Unit> {
        prepared(paragraphs(40)) { doc -> assertEquals(BoundedEpubParser().chapter(doc,path),BoundedEpubParser().window(doc,path)) }
    }
    @Test fun cancellationDuringWindowReadClosesTheOwnedResource() = runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();var closed=0
        val doc=object:EpubDocument {
            override val publicationId=epubId
            override val packagePath=EpubEntryPath("OPS/package.opf")
            override val metadata=EpubMetadata("original","Original",listOf("en"),null)
            override val manifest=listOf(EpubManifestItem("chapter",path,"application/xhtml+xml"))
            override val spine=listOf(EpubSpineItem("chapter"))
            override val navigationItemId="nav"
            override suspend fun openResource(path:EpubEntryPath)=object:ResourceContent {
                override val sizeBytes:Long?=null
                override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {entered.complete(Unit);awaitCancellation()}
                override fun close(){closed++}
            }
            override fun close(){}
        }
        val work=launch{BoundedEpubParser().window(doc,path)}
        entered.await();work.cancelAndJoin();assertEquals(1,closed)
    }
    @Test fun publicationLengthCannotIncreaseReturnedWindowContent() = runBlocking<Unit> {
        prepared((0..6000).joinToString(""){"<p>Original $it</p>"}) { doc ->
            val first=BoundedEpubParser().window(doc,path);val last=BoundedEpubParser().window(doc,path,EpubWindowRequest.End)
            bounded(first);bounded(last);assertEquals(6001,last.totalBlocks);assertEquals("Original 6000",last.blocks.last().text)
        }
    }
    @Test fun epub3NavigationCanTargetAnAnchorBeyondTheOldLimit() = runBlocking<Unit> {
        prepared(paragraphs(3001),epub3=true,navAnchor="p2800") { doc ->
            val parser=BoundedEpubParser();val target=parser.toc(doc).single().target
            assertEquals("p2800",target.anchor)
            val window=parser.window(doc,target.path,EpubWindowRequest.Anchor(target.anchor!!))
            assertTrue(2800 in window.startBlock until window.endBlock);bounded(window)
        }
    }
}
