// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*
import org.infinilect.app.progress.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileTextDocumentTest {
    private val id = PublicationId(SourceId("fixture"), "../unsafe/😀")
    private val resource = PublicationResource(id, "../text", PublicationFormat.TEXT, "text/plain")
    private val publication = Publication(id, "Title", PublicationType.DOCUMENT, resources = listOf(resource))
    private class Content(val length: Int, val pattern: ByteArray = byteArrayOf(65), var declared: Long? = length.toLong(),
                          val chunk: Int = 8192, val beforeRead: suspend () -> Unit = {}) : ResourceContent {
        override val sizeBytes get() = declared
        var position = 0; var closes = 0; var maxBuffer = 0; var maxRequest = 0
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(closes == 0); beforeRead(); maxBuffer = maxOf(maxBuffer, buffer.size); maxRequest = maxOf(maxRequest, length)
            if (position == this.length) return -1
            val count = minOf(chunk, length, this.length - position)
            repeat(count) { buffer[offset + it] = pattern[(position + it) % pattern.size] }
            position += count; return count
        }
        override fun close() { closes++ }
    }
    private suspend fun TestScope.fixture(action: suspend (Path, FileTextPreparer) -> Unit) {
        val root = Files.createTempDirectory("infinilect-text-test")
        val owner = FileTextPreparer(root.resolve(TEXT_DIRECTORY_NAME), StandardTestDispatcher(testScheduler))
        try { action(root, owner) }
        finally { owner.close(); owner.awaitClosed(); root.toFile().deleteRecursively() }
    }
    private suspend fun TestScope.prepare(owner: TextPreparer, content: ResourceContent): TextDocument =
        loadTextDocument(publication, resource, object : ResourceLoader {
            override suspend fun load(resource: PublicationResource) = content
        }, StandardTestDispatcher(testScheduler), owner)
    private fun bytes(text: String, chunk: Int = 8192) = text.encodeToByteArray().let { Content(it.size, it, chunk = chunk) }
    private fun payloads(root: Path): List<Path> = Files.walk(root).use { it.filter { path -> path.toString().endsWith(".utf8") }.toList() }

    @Test fun smallTextUsesBackedWindowAndClosesSource() = runTest { fixture { root, owner ->
        val content = bytes("hello é😀"); val doc = prepare(owner, content)
        assertEquals("hello é😀", doc.window(0).text); assertEquals(8, doc.codePoints)
        assertIs<FileWindows>(doc.windows); assertEquals(1, content.closes); assertEquals(1, payloads(root).size)
        doc.close(); runCurrent(); assertTrue(payloads(root).isEmpty())
    } }
    @Test fun leadingBomRemovedAndInteriorPreserved() = runTest { fixture { _, owner ->
        val doc = prepare(owner, bytes("\uFEFFhello\uFEFF😀", 1))
        assertEquals("hello\uFEFF😀", doc.window(0).text); assertEquals(7, doc.codePoints)
    } }
    @Test fun bomOnlyIsEmpty() = runTest { fixture { root, owner ->
        val content = bytes("\uFEFF", 1)
        assertEquals(TextFailure.EMPTY, assertFailsWith<TextDocumentException> { prepare(owner, content) }.failure)
        assertTrue(payloads(root).isEmpty()); assertEquals(1, content.closes)
    } }
    @Test fun everyUtf8ByteBoundaryDecodesStrictly() = runTest { fixture { _, owner ->
        val text = "aé中😀z".repeat(1700)
        for (chunk in 1..9) {
            val doc = prepare(owner, bytes(text, chunk)); val combined = StringBuilder()
            for (i in 0 until doc.windowCount) combined.append(doc.window(i).text)
            assertEquals(text, combined.toString()); assertEquals(8500, doc.codePoints); doc.close()
        }
    } }
    @Test fun supplementaryCharactersNeverSplitAtWindowBoundary() = runTest { fixture { _, owner ->
        val doc = prepare(owner, bytes("a".repeat(2047) + "😀" + "z"))
        assertEquals(2048, doc.window(0).locations.codePoints); assertTrue(doc.window(0).text.endsWith("😀"))
        assertEquals("z", doc.window(1).text); assertEquals(2048, doc.windowStart(1))
    } }
    @Test fun malformedUtf8AcrossWindowsDiscardsBackingAndCloses() = runTest { fixture { root, owner ->
        for (tail in listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()))) {
            val data = ByteArray(8191) { 65 } + tail; val content = Content(data.size, data, chunk = 3)
            assertEquals(TextFailure.INVALID_UTF8, assertFailsWith<TextDocumentException> { prepare(owner, content) }.failure)
            assertEquals(1, content.closes); assertTrue(payloads(root).isEmpty())
        }
    } }
    @Test fun shortReadCannotPublishDocument() = runTest { fixture { root, owner ->
        val content = Content(8192, declared = 8193)
        assertEquals(TextFailure.INCONSISTENT_SIZE, assertFailsWith<TextDocumentException> { prepare(owner, content) }.failure)
        assertEquals(1, content.closes); assertTrue(payloads(root).isEmpty())
    } }
    @Test fun extraByteReadConsumesOnlyOneProbeAndDiscards() = runTest { fixture { root, owner ->
        val content = Content(10000, declared = 8192)
        assertEquals(TextFailure.INCONSISTENT_SIZE, assertFailsWith<TextDocumentException> { prepare(owner, content) }.failure)
        assertEquals(8193, content.position); assertEquals(1, content.closes); assertTrue(payloads(root).isEmpty())
    } }
    @Test fun exactSixteenMiBLimitStreamsWithBoundedBuffersAndIndex() = runTest { fixture { _, owner ->
        val content = Content(MAX_TEXT_DOCUMENT_BYTES, "x\n".encodeToByteArray()); val doc = prepare(owner, content)
        assertEquals(MAX_TEXT_DOCUMENT_BYTES, doc.codePoints); assertEquals(16384, doc.windowCount)
        assertTrue(content.maxBuffer <= TEXT_READ_BUFFER_BYTES + 4); assertTrue(content.maxRequest <= TEXT_READ_BUFFER_BYTES)
        assertTrue(assertIs<FileWindows>(doc.windows).indexCapacity <= MAX_TEXT_INDEX_ENTRIES)
        assertEquals(1, content.closes); assertEquals(1024, doc.window(doc.windowCount - 1).text.length)
    } }
    @Test fun oneByteOverSupportedLimitRejectsBeforeReading() = runTest { fixture { root, owner ->
        val content = Content(1, declared = MAX_TEXT_DOCUMENT_BYTES.toLong() + 1)
        assertEquals(TextFailure.TOO_LARGE, assertFailsWith<TextDocumentException> { prepare(owner, content) }.failure)
        assertEquals(0, content.position); assertEquals(1, content.closes); assertTrue(payloads(root).isEmpty())
    } }
    @Test fun unknownSizeFailsBeforeCreatingFile() = runTest { fixture { root, owner ->
        val content = Content(1, declared = null)
        assertEquals(TextFailure.UNKNOWN_SIZE, assertFailsWith<TextDocumentException> { prepare(owner, content) }.failure)
        assertEquals(1, content.closes); assertTrue(payloads(root).isEmpty())
    } }
    @Test fun zeroWhitespaceAndNulAreNotDocuments() = runTest { fixture { root, owner ->
        for (text in listOf("", " \n\t", "hello\u0000")) {
            val content = bytes(text)
            assertEquals(TextFailure.EMPTY, assertFailsWith<TextDocumentException> { prepare(owner, content) }.failure)
            assertEquals(1, content.closes); assertTrue(payloads(root).isEmpty())
        }
    } }
    @Test fun multiMegabyteBeginningMiddleEndAndBackwardStayBounded() = runTest { fixture { _, owner ->
        val pattern = "é中😀 abc\n".encodeToByteArray(); val content = Content(pattern.size * 300000, pattern)
        val doc = prepare(owner, content); val windows = assertIs<FileWindows>(doc.windows)
        for (offset in listOf(0, doc.codePoints / 2, doc.codePoints - 1, 100, doc.codePoints / 3)) {
            val window = doc.window(doc.windowFor(offset))
            assertTrue(window.startCodePoint <= offset && offset < window.startCodePoint + window.locations.codePoints)
            assertTrue(window.text.length <= TEXT_WINDOW_CODE_POINTS * 2); assertTrue(window.text.contains("😀"))
        }
        repeat(30) { doc.window(it * (doc.windowCount / 30)) }
        assertEquals(TEXT_WINDOW_CACHE_ENTRIES, windows.cachedWindows)
        assertEquals(1, content.closes); assertEquals(content.length, content.position)
    } }
    @Test fun newlineGroupingHasBoundedMinimumAndMaximum() = runTest { fixture { _, owner ->
        val doc = prepare(owner, bytes("x\n".repeat(3000)))
        for (i in 0 until doc.windowCount - 1) {
            val window = doc.window(i)
            assertTrue(window.text.endsWith("\n")); assertTrue(window.locations.codePoints in 1024..2048)
        }
    } }
    @Test fun eofOffsetSelectsLastWindowAndUnicodeLocalPosition() = runTest { fixture { _, owner ->
        val doc = prepare(owner, bytes("😀".repeat(3000))); val last = doc.window(doc.windowFor(doc.codePoints))
        assertEquals(doc.codePoints, last.globalOffset(last.text.length))
        assertEquals(2048, doc.windowStart(1)); assertEquals(2048, last.globalOffset(1))
    } }
    @Test fun priorProgressRecordRestoresWithoutReadingWholeDocument() = runTest { fixture { _, owner ->
        val doc = prepare(owner, bytes("😀".repeat(100000)))
        val stored = ReadingProgress(doc.progressId!!, ReadingLocator.Text(90000, 100000), 0.9, 1)
        assertEquals(90000, doc.restore(stored)); val windows = assertIs<FileWindows>(doc.windows)
        assertEquals(0, windows.cachedWindows)
        val window = doc.window(doc.windowFor(doc.restore(stored)))
        assertEquals(90000, window.globalOffset(window.locations.utf16Offset((90000 - window.startCodePoint).toLong())))
        assertEquals(1, windows.cachedWindows)
    } }
    @Test fun changedLengthRestoresByExistingNormalizedProgression() = runTest { fixture { _, owner ->
        val doc = prepare(owner, Content(3000000))
        val stored = ReadingProgress(doc.progressId!!, ReadingLocator.Text(250, 1000), 0.25, 1)
        assertEquals(750000, doc.restore(stored)); assertEquals(0.25, doc.progression(750000))
    } }
    @Test fun foreignIdentityNeverRestores() = runTest { fixture { _, owner ->
        val doc = prepare(owner, Content(3000000))
        val stored = ReadingProgress(doc.progressId!!.copy(resourceKey = "other"), ReadingLocator.Text(250, 1000), 0.25, 1)
        assertEquals(0, doc.restore(stored)); assertEquals(0, doc.windowFor(-1)); assertEquals(doc.windowCount - 1, doc.windowFor(Int.MAX_VALUE))
    } }
    @Test fun fullPersistentOwnerRestartKeepsUnicodeProgressNotBacking() = runTest { fixture { root, owner ->
        val storeDirectory = root.resolve("reading-progress-v1"); val first = prepare(owner, bytes("😀".repeat(300000)))
        val p = ProgressPersistence(FileReadingProgressStore(storeDirectory, dispatcher = StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler), { 123L })
        val reading = TextReadingProgress(first, null, p, this)
        reading.report(250000); reading.close(); p.close(); p.awaitClosed(); assertFalse(p.saveFailed.value)
        assertTrue(Files.list(storeDirectory).use { entries -> entries.anyMatch { it.toString().endsWith(".progress") } })
        first.close(); runCurrent()
        assertTrue(payloads(root).isEmpty())
        val restarted = ProgressPersistence(FileReadingProgressStore(storeDirectory, dispatcher = StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))
        val secondOwner = FileTextPreparer(root.resolve(TEXT_DIRECTORY_NAME), StandardTestDispatcher(testScheduler))
        try {
            val second = prepare(secondOwner, bytes("😀".repeat(300000)))
            val restored = TextReadingProgress(second, restarted.get(second.progressId!!), restarted, this)
            assertEquals(250000, restored.codePointOffset.value); assertNull(resource.revision)
            restored.close(); second.close()
        } finally { restarted.close(); restarted.awaitClosed(); secondOwner.close(); secondOwner.awaitClosed() }
    } }
    @Test fun cancellationDuringAcquisitionDiscardsAndCloses() = runTest { fixture { root, owner ->
        val entered = CompletableDeferred<Unit>(); val content = Content(3000000, beforeRead = { entered.complete(Unit); awaitCancellation() })
        val job = launch { prepare(owner, content) }; entered.await(); job.cancelAndJoin()
        assertEquals(1, content.closes); assertTrue(payloads(root).isEmpty())
    } }
    @Test fun cancellationBetweenDecodedBuffersCannotPublishPartialIndex() = runTest { fixture { root, owner ->
        val entered = CompletableDeferred<Unit>(); var reads = 0
        val content = Content(3000000, beforeRead = { if (++reads == 10) { entered.complete(Unit); awaitCancellation() } })
        val job = launch { prepare(owner, content) }; entered.await(); assertTrue(payloads(root).isNotEmpty()); job.cancelAndJoin()
        assertEquals(1, content.closes); assertTrue(payloads(root).isEmpty())
    } }
    @Test fun ioFailureDiscardsAndUsesFixedMessage() = runTest { fixture { root, owner ->
        val content = Content(3000000, beforeRead = { throw java.io.IOException("private details") })
        val failure = assertFailsWith<TextDocumentException> { prepare(owner, content) }
        assertEquals(TextFailure.STORAGE, failure.failure); assertFalse(failure.message!!.contains("private details"))
        assertEquals(1, content.closes); assertTrue(payloads(root).isEmpty())
    } }
    @Test fun storageUnavailableClosesSourceWithoutUnsafeFallback() = runTest {
        val owner = FileTextPreparer(null, StandardTestDispatcher(testScheduler)); val content = Content(100)
        try { assertEquals(TextFailure.STORAGE, assertFailsWith<TextDocumentException> { prepare(owner, content) }.failure) }
        finally { owner.close(); owner.awaitClosed() }
        assertEquals(1, content.closes); assertEquals(0, content.position)
    }
    @Test fun closeIsIdempotentAndPreventsNewWindowReads() = runTest { fixture { root, owner ->
        val doc = prepare(owner, Content(3000000)); doc.close(); doc.close(); runCurrent()
        assertFailsWith<IllegalStateException> { doc.window(0) }; assertTrue(payloads(root).isEmpty())
    } }
    @Test fun ownerCloseReleasesActiveDocuments() = runTest { fixture { root, owner ->
        val doc = prepare(owner, Content(3000000)); owner.close(); owner.close(); owner.awaitClosed()
        assertTrue(payloads(root).isEmpty()); assertFailsWith<IllegalStateException> { doc.window(0) }
    } }
    @Test fun activeOwnerFilesSurviveAnotherOwnerStartup() = runTest { fixture { root, owner ->
        val first = prepare(owner, Content(3000000)); val secondOwner = FileTextPreparer(root.resolve(TEXT_DIRECTORY_NAME), StandardTestDispatcher(testScheduler))
        try { val second = prepare(secondOwner, Content(100)); assertEquals(2, payloads(root).size); assertEquals(2048, first.window(1).text.length); second.close() }
        finally { secondOwner.close(); secondOwner.awaitClosed() }
    } }
    @Test fun staleOwnedSessionRemovedButUnrelatedFilesRemain() = runTest { fixture { root, owner ->
        val storage = root.resolve(TEXT_DIRECTORY_NAME); val stale = Files.createDirectories(storage.resolve("session-123"))
        Files.write(stale.resolve(".owner.lock"), byteArrayOf()); Files.write(stale.resolve("text-123.utf8"), byteArrayOf(65))
        val unrelated = storage.resolve("notes.txt"); Files.write(unrelated, byteArrayOf(66))
        prepare(owner, Content(100)).close(); assertFalse(Files.exists(stale)); assertTrue(Files.exists(unrelated))
    } }
    @Test fun symlinkRootFailsClosedAndDoesNotModifyTarget() = runTest { fixture { root, _ ->
        val target = Files.createDirectory(root.resolve("outside")); val link = root.resolve("link"); Files.createSymbolicLink(link, target)
        val owner = FileTextPreparer(link, StandardTestDispatcher(testScheduler)); val content = Content(100)
        try { assertEquals(TextFailure.STORAGE, assertFailsWith<TextDocumentException> { prepare(owner, content) }.failure) }
        finally { owner.close(); owner.awaitClosed() }
        assertEquals(0L, Files.list(target).use { it.count() }); assertEquals(1, content.closes)
    } }
    @Test fun symlinkStaleSessionNeverDeletesOutsideNamespace() = runTest { fixture { root, owner ->
        val storage = Files.createDirectories(root.resolve(TEXT_DIRECTORY_NAME)); val target = Files.createDirectory(root.resolve("outside"))
        val marker = target.resolve("text-123.utf8"); Files.write(marker, byteArrayOf(65))
        Files.createSymbolicLink(storage.resolve("session-123"), target)
        prepare(owner, Content(100)).close(); assertTrue(Files.exists(marker))
    } }
    @Test fun mutatedBackingFailsSafelyOnUncachedWindow() = runTest { fixture { root, owner ->
        val doc = prepare(owner, Content(10000)); Files.write(payloads(root).single(), byteArrayOf(65), StandardOpenOption.TRUNCATE_EXISTING)
        assertEquals(TextFailure.STORAGE, assertFailsWith<TextDocumentException> { doc.window(1) }.failure)
    } }
    @Test fun androidPathUsesCacheAndRejectsRelativeBase() {
        assertEquals(Paths.get("/private/cache/reader-text-v1"), androidTextDirectory(Paths.get("/private/cache")))
        assertNull(androidTextDirectory(Paths.get("relative/cache")))
    }
    @Test fun desktopPathIsPerUserCacheIndependentOfPersistentData() {
        assertEquals(Paths.get("/user/cache/org.infinilect.app/reader-text-v1"), desktopTextDirectory("Linux", "/home/user", mapOf("XDG_CACHE_HOME" to "/user/cache")))
        assertEquals(Paths.get("/home/user/.cache/org.infinilect.app/reader-text-v1"), desktopTextDirectory("Linux", "/home/user", mapOf("XDG_CACHE_HOME" to "relative")))
        assertNull(desktopTextDirectory("Linux", "relative", emptyMap()))
        assertEquals(Paths.get("/home/user/Library/Caches/org.infinilect.app/reader-text-v1"), desktopTextDirectory("Mac OS X", "/home/user", emptyMap()))
        assertEquals(Paths.get("/local/org.infinilect.app/reader-text-v1"), desktopTextDirectory("Windows", "/home/user", mapOf("LOCALAPPDATA" to "/local")))
    }
    @Test fun platformProvidedParentAliasWorksWithoutAllowingNamespaceSymlink() = runTest { fixture { root, _ ->
        val real = Files.createDirectory(root.resolve("private-cache"))
        val alias = root.resolve("os-alias"); Files.createSymbolicLink(alias, real)
        val owner = FileTextPreparer(androidTextDirectory(alias), StandardTestDispatcher(testScheduler))
        try {
            val doc = prepare(owner, Content(100)); assertEquals(100, doc.codePoints)
            assertEquals(1, payloads(real).size); doc.close()
        } finally { owner.close(); owner.awaitClosed() }
        assertTrue(payloads(real).isEmpty())
    } }

    @Test fun cacheOnlyEvictionAllowsFreshPreparationWithSameApplicationOwner() = runTest { fixture { root, owner ->
        val first = prepare(owner, Content(10000)); first.close(); runCurrent()
        root.resolve(TEXT_DIRECTORY_NAME).toFile().deleteRecursively()
        val second = prepare(owner, Content(20000))
        assertEquals(20000, second.codePoints); assertEquals(2048, second.window(0).text.length)
        second.close()
    } }

}
