// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class EpubMediaControllerTest {
    private val images = (1..4).map { EpubImage(EpubEntryPath("OPS/image$it.png"), "image/png", "Alt $it") }
    private class Document(private val images: List<EpubImage>) : EpubDocument {
        override val publicationId = PublicationId(SourceId("fixture"), "media")
        override val packagePath = EpubEntryPath("OPS/book.opf")
        override val metadata = EpubMetadata("id", "Title", listOf("en"), "2026-10-05T00:00:00Z")
        override val manifest = images.mapIndexed { i, image -> EpubManifestItem("i$i", image.path, image.mediaType) }
        override val spine = emptyList<EpubSpineItem>()
        override val navigationItemId = "nav"
        var opened = 0; var closed = 0; var size = 3L
        var action: suspend () -> Unit = {}
        override suspend fun openResource(path: EpubEntryPath): ResourceContent {
            opened++
            return object : ResourceContent {
                override val sizeBytes = size
                var read = false; var done = false
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    action(); if (read) return -1; read = true
                    repeat(3) { buffer[offset + it] = it.toByte() }; return 3
                }
                override fun close() { if (!done) closed++; done = true }
            }
        }
        override fun close() {}
    }
    private class Decoder : EpubRasterDecoder {
        var calls = 0; var active = 0; var maxActive = 0
        var action: suspend () -> Unit = {}
        override suspend fun decode(bytes: ByteArray, mediaType: String): EpubRaster {
            calls++; active++; maxActive = maxOf(maxActive, active)
            try { action(); return EpubRaster(1, 1, intArrayOf(0xffaabbcc.toInt())) } finally { active-- }
        }
    }
    private suspend fun TestScope.use(action: suspend (EpubMediaController, Document, Decoder) -> Unit) {
        val document = Document(images); val decoder = Decoder()
        val controller = EpubMediaController(document, this, decoder, StandardTestDispatcher(testScheduler))
        try { action(controller, document, decoder) } finally { controller.close() }
    }
    @Test fun bytesComeOnlyFromOwnedDocumentAndHandleCloses() = runTest { use { c, doc, decoder ->
        c.visible(images.take(1)); advanceUntilIdle(); assertIs<EpubMediaState.Ready>(c.state.value[images[0]])
        assertEquals(1, doc.opened); assertEquals(1, doc.closed); assertEquals(1, decoder.calls)
    } }
    @Test fun retentionAndConcurrentDecodesAreBounded() = runTest { use { c, _, decoder ->
        c.visible(images); advanceUntilIdle(); assertEquals(2, c.retained); assertEquals(2, decoder.calls); assertEquals(1, decoder.maxActive)
        c.visible(images.drop(2)); advanceUntilIdle(); assertEquals(images.drop(2).toSet(), c.state.value.keys)
    } }
    @Test fun repeatedImageSharesOneDecodedFrame() = runTest { use { c, _, decoder ->
        c.visible(List(100) { images[0] }); advanceUntilIdle(); assertEquals(1, c.retained); assertEquals(1, decoder.calls)
        c.visible(listOf(images[0])); advanceUntilIdle(); assertEquals(1, decoder.calls)
    } }
    @Test fun unsupportedSvgNeverOpensResource() = runTest { use { c, doc, decoder ->
        val svg = images[0].copy(mediaType = "image/svg+xml"); c.visible(listOf(svg)); advanceUntilIdle()
        assertSame(EpubMediaState.Unavailable, c.state.value[svg]); assertEquals(0, doc.opened); assertEquals(0, decoder.calls)
    } }
    @Test fun undeclaredImageNeverOpensResource() = runTest { use { c, doc, _ ->
        c.visible(listOf(images[0].copy(path = EpubEntryPath("OPS/missing.png")))); advanceUntilIdle(); assertEquals(0, doc.opened)
    } }
    @Test fun wrongManifestTypeNeverOpensResource() = runTest { use { c, doc, _ ->
        c.visible(listOf(images[0].copy(mediaType = "image/jpeg"))); advanceUntilIdle(); assertEquals(0, doc.opened)
    } }
    @Test fun oversizedEncodedResourceClosesWithoutDecode() = runTest { use { c, doc, decoder ->
        doc.size = EpubImagePolicy.ENCODED_BYTES + 1L; c.visible(images.take(1)); advanceUntilIdle()
        assertEquals(1, doc.closed); assertEquals(0, decoder.calls); assertSame(EpubMediaState.Unavailable, c.state.value[images[0]])
    } }
    @Test fun corruptDecoderFailureBecomesSafeFallback() = runTest { use { c, doc, decoder ->
        decoder.action = { error("private content") }; c.visible(images.take(1)); advanceUntilIdle()
        assertSame(EpubMediaState.Unavailable, c.state.value[images[0]]); assertEquals(1, doc.closed)
    } }
    @Test fun backDuringResourceReadClosesHandle() = runTest { use { c, doc, _ ->
        doc.action = { awaitCancellation() }; c.visible(images.take(1)); runCurrent(); c.close(); advanceUntilIdle()
        assertEquals(1, doc.closed); assertTrue(c.state.value.isEmpty())
    } }
    @Test fun noncooperativeLateDecodeAfterBackCannotPublish() = runTest { use { c, _, decoder ->
        val gate = CompletableDeferred<Unit>(); decoder.action = { withContext(NonCancellable) { gate.await() } }
        c.visible(images.take(1)); runCurrent(); c.close(); gate.complete(Unit); advanceUntilIdle(); assertTrue(c.state.value.isEmpty())
    } }
    @Test fun replacementDuringDecodePublishesOnlyCurrentSelection() = runTest { use { c, _, decoder ->
        val gate = CompletableDeferred<Unit>(); decoder.action = { if (decoder.calls == 1) withContext(NonCancellable) { gate.await() } }
        c.visible(images.take(1)); runCurrent(); c.visible(listOf(images[1])); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(setOf(images[1]), c.state.value.keys); assertEquals(1, decoder.maxActive)
    } }
    @Test fun closeIsIdempotentAndDropsPresentationState() = runTest { use { c, _, _ ->
        c.visible(images); advanceUntilIdle(); c.close(); c.close(); c.visible(images); assertEquals(0, c.retained)
    } }
    @Test fun chapterResetCancelsAndClearsOldMedia() = runTest { use { c, _, _ ->
        c.visible(images.take(1)); advanceUntilIdle(); c.reset(); assertTrue(c.state.value.isEmpty())
        c.visible(images.take(1)); advanceUntilIdle(); assertEquals(1, c.retained)
    } }
    @Test fun timeoutFailsSafelyAndClosesResource() = runTest { use { c, doc, _ ->
        doc.action = { awaitCancellation() }; c.visible(images.take(1)); advanceTimeBy(10_001); runCurrent()
        assertEquals(1, doc.closed); assertSame(EpubMediaState.Unavailable, c.state.value[images[0]])
    } }
    @Test fun decodedRepresentationRejectsAllocationBeyondPolicy() {
        assertFailsWith<IllegalArgumentException> { EpubRaster(2049, 1, IntArray(2049)) }
        assertFailsWith<IllegalArgumentException> { EpubRaster(2048, 2048, IntArray(0)) }
        assertFailsWith<IllegalArgumentException> { EpubRaster(1, 1, IntArray(0)) }
    }
    @Test fun inconsistentDeclaredSizeCannotReachDecoder() = runTest { use { c, doc, decoder ->
        doc.size = 2; c.visible(images.take(1)); advanceUntilIdle()
        assertSame(EpubMediaState.Unavailable, c.state.value[images[0]]); assertEquals(1, doc.closed); assertEquals(0, decoder.calls)
    } }
    @Test fun truncatedDeclaredSizeCannotReachDecoder() = runTest { use { c, doc, decoder ->
        doc.size = 4; c.visible(images.take(1)); advanceUntilIdle()
        assertSame(EpubMediaState.Unavailable, c.state.value[images[0]]); assertEquals(1, doc.closed); assertEquals(0, decoder.calls)
    } }

}
