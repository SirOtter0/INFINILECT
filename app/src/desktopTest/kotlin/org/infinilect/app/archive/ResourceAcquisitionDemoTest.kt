// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.infinilect.app.acquisition.demonstrateAcquisition
import org.infinilect.core.*

class ResourceAcquisitionDemoTest {
    private val resource = PublicationResource(PublicationId(SourceId("neutral"), "item"), "text", PublicationFormat.TEXT, "text/plain")
    private class Content(private val bytes: ByteArray, private val failure: Throwable? = null) : ResourceContent, ResourceLoader {
        override val sizeBytes = bytes.size.toLong()
        var position = 0
        var closed = false
        override suspend fun load(resource: PublicationResource) = this
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            failure?.let { throw it }
            if (position == bytes.size) return -1
            val count = minOf(length, bytes.size - position, 13) // Exercise partial reads.
            bytes.copyInto(buffer, offset, position, position + count); position += count
            return count
        }
        override fun close() { closed = true }
    }

    @Test fun prefixEndingInsideUtf8DoesNotRequireReadingMoreBytes() = runTest {
        val bytes = ByteArray(514) { 'a'.code.toByte() }.apply { this[511] = 0xc3.toByte(); this[512] = 0xa9.toByte() }
        val content = Content(bytes)
        assertEquals(512, demonstrateAcquisition(content, resource).sampledBytes)
        assertEquals(512, content.position)
        assertTrue(content.closed)
    }

    @Test fun emptyInvalidOrBinaryTextAlwaysClosesTheHandle() = runTest {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xff.toByte()), byteArrayOf(0), "   ".encodeToByteArray())) {
            val content = Content(bytes)
            assertFails { demonstrateAcquisition(content, resource) }
            assertTrue(content.closed)
        }
    }

    @Test fun cancellationAlwaysClosesWithoutHandingAnAdvancedHandleToAnotherConsumer() = runTest {
        val content = Content("small".encodeToByteArray(), kotlinx.coroutines.CancellationException())
        assertFailsWith<kotlinx.coroutines.CancellationException> { demonstrateAcquisition(content, resource) }
        assertTrue(content.closed)
    }
}
