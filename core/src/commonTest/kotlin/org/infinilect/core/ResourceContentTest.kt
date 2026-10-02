// SPDX-License-Identifier: GPL-3.0-only
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResourceContentTest {
    @Test fun smallTextSupportsUnknownSizeAndShortReads() {
        val bytes = "Open knowledge. Infinite reading.".encodeToByteArray()
        val content = TestContent(bytes, sizeBytes = null, chunkSize = 1)
        assertContentEquals(bytes, runImmediate { content.readBytes(maxBytes = bytes.size) })
        assertTrue(content.closed)
    }

    @Test fun shortReadsPreserveBytesAcrossMultipleWorkingBuffers() {
        val bytes = ByteArray(20003) { (it % 251).toByte() }
        val content = TestContent(bytes, sizeBytes = null, chunkSize = 257)
        assertContentEquals(bytes, runImmediate { content.readBytes(bytes.size) })
        assertEquals(bytes.size, content.position)
        assertTrue(content.closed)
    }

    @Test fun knownSizeDoesNotAllocateFromUntrustedMetadata() {
        val content = TestContent(byteArrayOf(), sizeBytes = Long.MAX_VALUE)
        assertFailsWith<ResourceLimitExceededException> { runImmediate { content.readBytes(16) } }
        assertEquals(0, content.readCalls)
        assertTrue(content.closed)
    }

    @Test fun unknownSizeOverflowConsumesOnlyOneExtraByteAndCloses() {
        val content = TestContent(ByteArray(100), sizeBytes = null)
        val error = assertFailsWith<ResourceLimitExceededException> { runImmediate { content.readBytes(7) } }
        assertEquals(7, error.maxBytes)
        assertEquals(8, content.position)
        assertTrue(content.closed)
    }

    @Test fun understatedSizeCannotBypassActualByteLimit() {
        val content = TestContent(ByteArray(10), sizeBytes = 1)
        assertFailsWith<ResourceLimitExceededException> { runImmediate { content.readBytes(4) } }
        assertEquals(5, content.position)
        assertTrue(content.closed)
    }

    @Test fun exactLimitSucceedsAndZeroLimitAllowsOnlyEmptyContent() {
        val full = TestContent(byteArrayOf(1, 2, 3), sizeBytes = 3)
        assertContentEquals(byteArrayOf(1, 2, 3), runImmediate { full.readBytes(3) })
        val empty = TestContent(byteArrayOf(), sizeBytes = null)
        assertContentEquals(byteArrayOf(), runImmediate { empty.readBytes(0) })
        val nonempty = TestContent(byteArrayOf(1), sizeBytes = null)
        assertFailsWith<ResourceLimitExceededException> { runImmediate { nonempty.readBytes(0) } }
        assertEquals(1, nonempty.position)
        assertTrue(full.closed && empty.closed && nonempty.closed)
    }

    @Test fun intMaxLimitDoesNotOverflowOrPreallocateTheLimit() {
        val content = TestContent(byteArrayOf(1), sizeBytes = null)
        assertContentEquals(byteArrayOf(1), runImmediate { content.readBytes(Int.MAX_VALUE) })
    }

    @Test fun readFailureAndCancellationPropagateAndClose() {
        for (failure in listOf(IllegalStateException("I/O failure"), CancellationException("cancelled"))) {
            val content = TestContent(byteArrayOf(), readFailure = failure)
            val actual = assertFailsWith<Exception> { runImmediate { content.readBytes(8) } }
            assertSame(failure, actual)
            assertTrue(content.closed)
        }
    }

    @Test fun closeFailureDoesNotMaskCancellation() {
        val cancelled = CancellationException("cancelled")
        val closeFailure = IllegalStateException("close failed")
        val content = TestContent(byteArrayOf(), readFailure = cancelled, closeFailure = closeFailure)
        val actual = assertFailsWith<CancellationException> { runImmediate { content.readBytes(8) } }
        assertSame(cancelled, actual)
        assertTrue(actual.suppressedExceptions.contains(closeFailure))
        assertTrue(content.closed)
    }

    @Test fun closeFailureAfterSuccessIsReported() {
        val error = IllegalStateException("close failed")
        val content = TestContent(byteArrayOf(), closeFailure = error)
        assertSame(error, assertFailsWith<IllegalStateException> { runImmediate { content.readBytes(8) } })
    }

    @Test fun invalidLimitAndSizeStillClose() {
        val content = TestContent(byteArrayOf())
        assertFailsWith<IllegalArgumentException> { runImmediate { content.readBytes(-1) } }
        assertTrue(content.closed)
        val invalidSize = TestContent(byteArrayOf(), sizeBytes = -1)
        assertFailsWith<IllegalStateException> { runImmediate { invalidSize.readBytes(1) } }
        assertTrue(invalidSize.closed)
    }

    @Test fun invalidReadCountsFailInsteadOfLoopingOrCopyingOutsideBuffer() {
        for (count in listOf(0, -2, 3)) {
            val invalid = object : ResourceContent {
                override val sizeBytes: Long? = null
                var closed = false
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = count
                override fun close() { closed = true }
            }
            assertFailsWith<IllegalStateException> { runImmediate { invalid.readBytes(1) } }
            assertTrue(invalid.closed)
        }
    }

    @Test fun incrementalConsumerCanReadPartiallyAndCloseEarly() {
        val content = TestContent(byteArrayOf(1, 2, 3, 4), chunkSize = 2)
        val buffer = ByteArray(4) { 9 }
        runImmediate {
            try {
                assertEquals(0, content.read(buffer, offset = 4, length = 0))
                assertEquals(0, content.position)
                assertEquals(2, content.read(buffer, offset = 1, length = 3))
                assertContentEquals(byteArrayOf(9, 1, 2, 9), buffer)
                assertEquals(2, content.position)
            } finally {
                content.close()
            }
        }
        content.close() // idempotent
        assertFailsWith<IllegalStateException> { runImmediate { content.read(buffer) } }
    }

    /** Synchronous in-memory fixture; real transport cancellation needs adapter integration tests. */
    private class TestContent(
        private val bytes: ByteArray,
        override val sizeBytes: Long? = bytes.size.toLong(),
        private val chunkSize: Int = Int.MAX_VALUE,
        private val readFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : ResourceContent {
        var position = 0
        var readCalls = 0
        var closed = false
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            require(offset in 0..buffer.size && length in 0..(buffer.size - offset))
            readCalls++
            readFailure?.let { throw it }
            if (length == 0) return 0
            if (position == bytes.size) return -1
            val count = minOf(length, chunkSize, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun close() {
            if (closed) return
            closed = true
            closeFailure?.let { throw it }
        }
    }

    private fun <T> runImmediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome) { "Fixture unexpectedly suspended" }.getOrThrow()
    }
}
