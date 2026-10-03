// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlin.test.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class OpenPublicationControllerTest {
    private val id = PublicationId(SourceId("fixture"), "1")
    private val textResource = PublicationResource(id, "text", PublicationFormat.TEXT, "text/plain")
    private val publication = Publication(id, "A small text", PublicationType.DOCUMENT,
        resources = listOf(PublicationResource(id, "pdf", PublicationFormat.PDF, "application/pdf"), textResource))
    private val summary = publication.copy(resources = emptyList())

    private class Content(
        val bytes: ByteArray,
        var declared: Long? = bytes.size.toLong(),
        val chunk: Int = 3,
        val beforeRead: suspend (Content) -> Unit = {},
        val closeError: Exception? = null,
    ) : ResourceContent {
        override val sizeBytes get() = declared
        var position = 0
        var reads = 0
        var closes = 0
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(closes == 0)
            reads++
            beforeRead(this)
            if (position == bytes.size) return -1
            val count = minOf(length, chunk, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun close() { closes++; closeError?.let { throw it } }
    }

    private inner class Fixture(
        val details: suspend () -> Publication? = { publication },
        val acquire: suspend () -> ResourceContent = { Content("real fixture text".encodeToByteArray()) },
    ) {
        val requestedIds = mutableListOf<PublicationId>()
        val loaded = mutableListOf<PublicationResource>()
        val source = object : PublicationSource {
            override val id = this@OpenPublicationControllerTest.id.sourceId
            override suspend fun search(query: String, pageToken: String?): SearchPage = error("Not used")
            override suspend fun getPublication(publicationId: PublicationId): Publication? {
                requestedIds += publicationId
                return details()
            }
            override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("Use loader")
        }
        val loader = object : ResourceLoader {
            override suspend fun load(resource: PublicationResource): ResourceContent {
                loaded += resource
                return acquire()
            }
        }
        fun controller(scope: TestScope) = OpenPublicationController(source, loader, scope,
            StandardTestDispatcher(scope.testScheduler))
    }

    @Test fun opensSelectedTextOnceConsumesFullResourceAndCloses() = runTest {
        val content = Content("Full text, not a prefix.\nSecond line.".encodeToByteArray())
        val fixture = Fixture(acquire = { content })
        val controller = fixture.controller(this)
        assertIs<OpenPublicationState.Idle>(controller.state.value)
        controller.open(summary)
        assertEquals(summary, assertIs<OpenPublicationState.Loading>(controller.state.value).publication)
        advanceUntilIdle()
        val document = assertIs<OpenPublicationState.Ready>(controller.state.value).document
        assertEquals(TextDocument(id, publication.title, content.bytes.decodeToString()), document)
        assertEquals(listOf(id), fixture.requestedIds)
        assertEquals(listOf(textResource), fixture.loaded)
        assertEquals(content.bytes.size, content.position)
        assertEquals(1, content.closes)
        assertTrue(content.reads > 1)
    }

    @Test fun onlyPdfOrMissingTextDoesNotInvokeLoader() = runTest {
        val fixture = Fixture(details = { publication.copy(resources = publication.resources.take(1)) })
        val controller = fixture.controller(this)
        controller.open(summary); advanceUntilIdle()
        assertEquals("No readable text format is available for this publication.",
            assertIs<OpenPublicationState.Error>(controller.state.value).userMessage)
        assertTrue(fixture.loaded.isEmpty())
    }

    @Test fun missingPublicationGivesControlledError() = runTest {
        val fixture = Fixture(details = { null })
        val controller = fixture.controller(this)
        controller.open(summary); advanceUntilIdle()
        assertEquals("This publication is no longer available.",
            assertIs<OpenPublicationState.Error>(controller.state.value).userMessage)
        assertTrue(fixture.loaded.isEmpty())
    }

    @Test fun foreignPublicationIsRejectedBeforeSourceAccess() = runTest {
        val fixture = Fixture(); val controller = fixture.controller(this)
        controller.open(summary.copy(id = PublicationId(SourceId("foreign"), "1")))
        advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value)
        assertTrue(fixture.requestedIds.isEmpty()); assertTrue(fixture.loaded.isEmpty())
    }

    @Test fun detailsForAnotherItemAreRejectedBeforeAcquisition() = runTest {
        val fixture = Fixture(details = { summary.copy(id = id.copy(localId = "other")) })
        val controller = fixture.controller(this)
        controller.open(summary); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value)
        assertTrue(fixture.loaded.isEmpty())
    }

    @Test fun knownOversizeRejectsWithoutReadAndCloses() = runTest {
        val content = Content(byteArrayOf(), Long.MAX_VALUE)
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertEquals(TextFailure.TOO_LARGE.userMessage,
            assertIs<OpenPublicationState.Error>(controller.state.value).userMessage)
        assertEquals(0, content.reads); assertEquals(1, content.closes)
    }

    @Test fun unknownSizeRejectsWithoutReadAndCloses() = runTest {
        val content = Content(byteArrayOf(65), null)
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertEquals(TextFailure.UNKNOWN_SIZE.userMessage,
            assertIs<OpenPublicationState.Error>(controller.state.value).userMessage)
        assertEquals(0, content.reads); assertEquals(1, content.closes)
    }

    @Test fun zeroByteResourceRejectedAndClosed() = runTest {
        val content = Content(byteArrayOf())
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertEquals(TextFailure.EMPTY.userMessage,
            assertIs<OpenPublicationState.Error>(controller.state.value).userMessage)
        assertEquals(0, content.reads); assertEquals(1, content.closes)
    }

    @Test fun negativeSizeRejectedBeforeAllocation() = runTest {
        val content = Content(byteArrayOf(), -1)
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value)
        assertEquals(0, content.reads); assertEquals(1, content.closes)
    }

    @Test fun exactByteLimitIsReadableAndCloses() = runTest {
        val content = Content(ByteArray(MAX_TEXT_DOCUMENT_BYTES) { 65 }, chunk = 8192)
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertEquals(MAX_TEXT_DOCUMENT_BYTES, assertIs<OpenPublicationState.Ready>(controller.state.value).document.text.length)
        assertEquals(MAX_TEXT_DOCUMENT_BYTES, content.position); assertEquals(1, content.closes)
    }

    @Test fun actualStreamExceedingLimitConsumesAtMostOneProbeByte() = runTest {
        val content = Content(ByteArray(MAX_TEXT_DOCUMENT_BYTES + 10) { 65 },
            MAX_TEXT_DOCUMENT_BYTES.toLong(), 8192)
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value)
        assertEquals(MAX_TEXT_DOCUMENT_BYTES + 1, content.position); assertEquals(1, content.closes)
    }

    @Test fun understatedSizeRejectedBeforeRemainingBytesAreConsumed() = runTest {
        val content = Content("more text".encodeToByteArray(), declared = 1, chunk = 8192)
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value)
        assertEquals(2, content.position); assertEquals(1, content.closes)
    }

    @Test fun earlyEofRejectedAndClosedEvenIfCleanupAlsoFails() = runTest {
        val content = Content(byteArrayOf(65), declared = 3, closeError = IllegalStateException("private close detail"))
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertEquals(TextFailure.INCONSISTENT_SIZE.userMessage,
            assertIs<OpenPublicationState.Error>(controller.state.value).userMessage)
        assertEquals(1, content.closes)
    }

    @Test fun changingSizeDuringReadRejectedAndClosed() = runTest {
        val content = Content("hello".encodeToByteArray(), beforeRead = { it.declared = null })
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value)
        assertEquals(1, content.closes)
    }

    @Test fun invalidAndTruncatedUtf8RejectedWithoutReplacement() = runTest {
        for (bytes in listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(65, 0xe2.toByte(), 0x82.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), ByteArray(600) { 65 } + byteArrayOf(0xff.toByte()))) {
            val content = Content(bytes)
            val controller = Fixture(acquire = { content }).controller(this)
            controller.open(summary); advanceUntilIdle()
            assertEquals(TextFailure.INVALID_UTF8.userMessage,
                assertIs<OpenPublicationState.Error>(controller.state.value).userMessage)
            assertEquals(1, content.closes)
        }
    }

    @Test fun validMultibyteUtf8SplitAcrossSingleByteReads() = runTest {
        val expected = "Español 日本語 🦦\nSecond line"
        val content = Content(expected.encodeToByteArray(), chunk = 1)
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertEquals(expected, assertIs<OpenPublicationState.Ready>(controller.state.value).document.text)
        assertEquals(1, content.closes)
    }

    @Test fun stripsExactlyOneLeadingBomAndPreservesInteriorBom() = runTest {
        val content = Content("\uFEFFtext\uFEFFend".encodeToByteArray(), chunk = 1)
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertEquals("text\uFEFFend", assertIs<OpenPublicationState.Ready>(controller.state.value).document.text)
        assertEquals(1, content.closes)
    }

    @Test fun bomOnlyWhitespaceAndNulDoNotBecomeReadableDocuments() = runTest {
        for (text in listOf("\uFEFF", " \n\t", "text\u0000binary")) {
            val content = Content(text.encodeToByteArray())
            val controller = Fixture(acquire = { content }).controller(this)
            controller.open(summary); advanceUntilIdle()
            assertEquals(TextFailure.EMPTY.userMessage,
                assertIs<OpenPublicationState.Error>(controller.state.value).userMessage)
            assertEquals(1, content.closes)
        }
    }

    @Test fun readFailureClosesAndHidesInternalDetails() = runTest {
        val content = Content(byteArrayOf(65), beforeRead = { error("https://internal/path token=private") })
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        val message = assertIs<OpenPublicationState.Error>(controller.state.value).userMessage
        assertEquals("Could not acquire this text. The source may be unavailable. Please try again.", message)
        assertEquals(1, content.closes)
    }

    @Test fun unexpectedZeroReadFailsAndClosesInsteadOfSpinning() = runTest {
        val content = Content(byteArrayOf(65), chunk = 0)
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value)
        assertEquals(1, content.reads); assertEquals(1, content.closes)
    }

    @Test fun acquisitionFailureIsSafeAndCanBeRetried() = runTest {
        var fail = true
        val fixture = Fixture(acquire = { if (fail) error("private source detail") else Content("retry".encodeToByteArray()) })
        val controller = fixture.controller(this)
        controller.open(summary); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value)
        fail = false
        controller.open(summary); advanceUntilIdle()
        assertEquals("retry", assertIs<OpenPublicationState.Ready>(controller.state.value).document.text)
        assertEquals(2, fixture.loaded.size)
    }

    @Test fun repeatedOpenWhileLoadingIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = Fixture(details = { gate.await(); publication })
        val controller = fixture.controller(this)
        controller.open(summary); runCurrent()
        controller.open(summary); controller.open(summary)
        assertEquals(1, fixture.requestedIds.size)
        gate.complete(Unit); advanceUntilIdle()
        assertIs<OpenPublicationState.Ready>(controller.state.value)
        assertEquals(1, fixture.loaded.size)
    }

    @Test fun cancellationDuringReadClosesAndReturnsToIdle() = runTest {
        val entered = CompletableDeferred<Unit>()
        val content = Content(byteArrayOf(65), beforeRead = { entered.complete(Unit); awaitCancellation() })
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); entered.await()
        controller.cancel(); advanceUntilIdle()
        assertIs<OpenPublicationState.Idle>(controller.state.value)
        assertEquals(1, content.closes)
    }

    @Test fun cancelBeforeLateDetailsPreventsAcquisition() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = Fixture(details = { withContext(NonCancellable) { gate.await() }; publication })
        val controller = fixture.controller(this)
        controller.open(summary); runCurrent()
        controller.cancel(); gate.complete(Unit); advanceUntilIdle()
        assertIs<OpenPublicationState.Idle>(controller.state.value)
        assertTrue(fixture.loaded.isEmpty())
    }

    @Test fun cancellationDuringHandleHandoffStillClosesWithoutReading() = runTest {
        val gate = CompletableDeferred<Unit>()
        val content = Content("late".encodeToByteArray())
        val controller = Fixture(acquire = { withContext(NonCancellable) { gate.await() }; content }).controller(this)
        controller.open(summary); runCurrent()
        controller.cancel(); gate.complete(Unit); advanceUntilIdle()
        assertIs<OpenPublicationState.Idle>(controller.state.value)
        assertEquals(0, content.reads); assertEquals(1, content.closes)
    }

    @Test fun cancelledOldReadCannotOverwriteNewReadyAndBothHandlesClose() = runTest {
        val gate = CompletableDeferred<Unit>(); var acquisitions = 0
        val old = Content("old".encodeToByteArray(), beforeRead = { withContext(NonCancellable) { gate.await() } })
        val fresh = Content("fresh".encodeToByteArray())
        val controller = Fixture(acquire = { if (acquisitions++ == 0) old else fresh }).controller(this)
        controller.open(summary); runCurrent()
        controller.cancel(); controller.open(summary); runCurrent()
        assertEquals("fresh", assertIs<OpenPublicationState.Ready>(controller.state.value).document.text)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("fresh", assertIs<OpenPublicationState.Ready>(controller.state.value).document.text)
        assertEquals(1, old.closes); assertEquals(1, fresh.closes)
    }

    @Test fun applicationScopeCancellationClosesActiveHandle() = runTest {
        val owner = Job(coroutineContext[Job]); val scope = CoroutineScope(coroutineContext + owner)
        val entered = CompletableDeferred<Unit>()
        val content = Content(byteArrayOf(65), beforeRead = { entered.complete(Unit); awaitCancellation() })
        val fixture = Fixture(acquire = { content })
        val controller = OpenPublicationController(fixture.source, fixture.loader, scope, StandardTestDispatcher(testScheduler))
        controller.open(summary); entered.await()
        owner.cancel(); advanceUntilIdle()
        assertIs<OpenPublicationState.Idle>(controller.state.value); assertEquals(1, content.closes)
    }

    @Test fun timeoutClosesHandleAndDisplaysSafeError() = runTest {
        val content = Content(byteArrayOf(65), beforeRead = { awaitCancellation() })
        val controller = Fixture(acquire = { content }).controller(this)
        controller.open(summary); runCurrent()
        advanceTimeBy(60_001); runCurrent()
        assertEquals("Opening this text timed out. Please try again.",
            assertIs<OpenPublicationState.Error>(controller.state.value).userMessage)
        assertEquals(1, content.closes)
    }

    @Test fun closedControllerCannotStartAnotherAcquisition() = runTest {
        val fixture = Fixture(); val controller = fixture.controller(this)
        controller.close(); controller.open(summary); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(controller.state.value)
        assertTrue(fixture.requestedIds.isEmpty()); assertTrue(fixture.loaded.isEmpty())
    }
}
