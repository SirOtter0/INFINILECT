// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

/** Single-consumer, sequential bytes. Does not imply in-memory storage or random access. */
interface ResourceContent {
    /** Nonnegative total byte size, or null if unknown. Must not be used as an allocation request. */
    val sizeBytes: Long?

    /**
     * Reads at most [length] bytes into [buffer] starting at [offset], advancing the cursor.
     * Valid bounds: offset in 0..buffer.size, length in 0..(buffer.size - offset).
     * A positive request returns 1..length (possibly a short read), or -1 at EOF, never 0.
     * A zero-length request returns 0 without advancing. Invalid bounds throw
     * IllegalArgumentException; reads after close throw IllegalStateException.
     * Implementations must propagate cancellation and avoid fetching the whole resource.
     * Concurrent reads/close are unsupported; the caller owns one handle at a time.
     */
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int

    /**
     * Idempotently releases/cancels underlying I/O without suspending or waiting for a
     * transfer to finish, so it is usable in finally even in a cancelled coroutine.
     * Closing early aborts consumption; an incomplete cache write must not be published.
     */
    fun close()
}

class ResourceLimitExceededException(val maxBytes: Int) : Exception("Resource exceeds $maxBytes bytes")

/**
 * Convenience for small, newly opened resources only. Consumes bytes, then always closes.
 * [maxBytes] is mandatory; size metadata is an early rejection hint, not the only check.
 * Reads at most maxBytes + 1 bytes to distinguish an exact-limit resource from overflow.
 * Chunk/result payload is at most twice maxBytes, plus an at-most-8 KiB working buffer.
 */
suspend fun ResourceContent.readBytes(maxBytes: Int): ByteArray {
    var failure: Throwable? = null
    try {
        require(maxBytes >= 0) { "Byte limit must not be negative" }
        val size = sizeBytes
        check(size == null || size >= 0) { "Resource size must be nonnegative or unknown" }
        if (size != null && size > maxBytes.toLong()) throw ResourceLimitExceededException(maxBytes)

        val buffer = ByteArray(minOf(8192L, maxBytes.toLong() + 1).toInt())
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        var buffered = 0
        while (true) {
            val requested = minOf((buffer.size - buffered).toLong(), (maxBytes - total).toLong() + 1).toInt()
            val count = read(buffer, offset = buffered, length = requested)
            if (count == -1) break
            check(count in 1..requested) { "Resource read must return bytes or EOF for a positive request" }
            if (count > maxBytes - total) throw ResourceLimitExceededException(maxBytes)
            total += count
            buffered += count
            if (buffered == buffer.size) {
                chunks += buffer.copyOf()
                buffered = 0
            }
        }
        if (buffered > 0) chunks += buffer.copyOf(buffered)
        val result = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, destinationOffset = offset)
            offset += chunk.size
        }
        return result
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            close()
        } catch (closeError: Throwable) {
            val primary = failure
            if (primary == null) throw closeError
            if (primary !== closeError) primary.addSuppressed(closeError)
        }
    }
}
