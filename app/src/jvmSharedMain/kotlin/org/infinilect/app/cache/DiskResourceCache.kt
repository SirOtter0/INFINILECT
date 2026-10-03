// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.cache

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.infinilect.core.PublicationResource
import org.infinilect.core.ResourceContent
import org.infinilect.core.ResourceLoader
import org.infinilect.core.SourceId

/** Automatic, optional storage. Unknown revisions bypass it; sources/readers remain unaware.
 * Lifecycle owns all returned handles, including uncached ones. No source calls under a lock.
 */
internal class DiskResourceCache(
    directory: Path?,
    maxBytes: Long = DEFAULT_DISK_CACHE_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val store = DiskCacheStore(directory, maxBytes, clock)
    private val monitor = Any()
    private val active = mutableSetOf<OwnedContent>()
    private var closed = false

    fun loader(sourceId: SourceId, upstream: ResourceLoader): ResourceLoader = object : ResourceLoader {
        override suspend fun load(resource: PublicationResource): ResourceContent {
            require(resource.publicationId.sourceId == sourceId)
            checkOpen()
            currentCoroutineContext().ensureActive()
            val identity = try { resource.cacheKey?.encodeIdentity() } catch (_: IllegalArgumentException) { null }
            if (identity != null) {
                var opened: DiskCacheStore.Hit? = null
                try {
                    val hit = withContext(ioDispatcher) {
                        val candidate = store.open(identity) ?: return@withContext null
                        opened = candidate
                        try { candidate.verify(); candidate }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            candidate.close(); store.invalidate(candidate.name); opened = null; null
                        }
                    }
                    if (hit != null) {
                        currentCoroutineContext().ensureActive()
                        return own(CachedContent(hit)).also { opened = null }
                    }
                } finally {
                    // withContext has prompt cancellation even after a successful block.
                    opened?.close()
                }
            }
            checkOpen()
            val content = upstream.load(resource)
            var write: DiskCacheStore.Write? = null
            var wrapped: SourceContent? = null
            try {
                currentCoroutineContext().ensureActive()
                if (identity != null) withContext(ioDispatcher) { write = store.begin(identity, content.sizeBytes) }
                wrapped = SourceContent(content, write, identity != null)
                return own(wrapped)
            } catch (error: Throwable) {
                write?.abort()
                try { if (wrapped == null) content.close() else wrapped.close() }
                catch (closeError: Throwable) { if (closeError !== error) error.addSuppressed(closeError) }
                throw error
            }
        }
    }

    private fun checkOpen() = synchronized(monitor) { check(!closed) { "Resource cache owner is closed." } }
    private fun own(content: OwnedContent): ResourceContent = synchronized(monitor) {
        if (closed) { content.close(); error("Resource cache owner is closed.") }
        active += content
        content
    }

    private abstract inner class OwnedContent : ResourceContent {
        val ended = AtomicBoolean()
        final override fun close() {
            if (!ended.compareAndSet(false, true)) return
            try { release() } finally { synchronized(monitor) { active -= this } }
        }
        abstract fun release()
        fun checkRead(buffer: ByteArray, offset: Int, length: Int) {
            require(offset in 0..buffer.size && length in 0..buffer.size - offset)
            check(!ended.get()) { "Resource is closed." }
        }
    }

    private inner class CachedContent(private val hit: DiskCacheStore.Hit) : OwnedContent() {
        override val sizeBytes get() = hit.size
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkRead(buffer, offset, length)
            if (length == 0) return 0
            return try { withContext(ioDispatcher) { currentCoroutineContext().ensureActive(); hit.read(buffer, offset, length) } }
            catch (error: Throwable) { close(); throw error }
        }
        override fun release() = hit.close()
    }

    private inner class SourceContent(
        private val source: ResourceContent,
        private val write: DiskCacheStore.Write?,
        private val validateSize: Boolean,
    ) : OwnedContent() {
        private val expected = source.sizeBytes
        private var consumed = 0L
        private var eof = false
        private var readingJob: Job? = null
        override val sizeBytes get() = source.sizeBytes
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkRead(buffer, offset, length)
            if (length == 0) return 0
            try {
                val context = currentCoroutineContext()
                context.ensureActive(); readingJob = context[Job]
                if (eof) return -1
                if (validateSize && (expected != source.sizeBytes || expected?.let { it < 0 } == true)) throw IOException("Inconsistent resource size")
                val count = source.read(buffer, offset, length)
                context.ensureActive()
                if (validateSize && expected != source.sizeBytes) throw IOException("Inconsistent resource size")
                if (count == -1) {
                    if (validateSize && expected != null && expected != consumed) throw IOException("Incomplete resource")
                    eof = true
                } else {
                    check(count in 1..length)
                    if (count > Long.MAX_VALUE - consumed) throw IOException("Resource size overflow")
                    consumed += count
                    if (validateSize && expected != null && consumed > expected) throw IOException("Overlong resource")
                    if (write != null) withContext(ioDispatcher) { write.append(buffer, offset, count) }
                }
                return count
            } catch (error: Throwable) {
                write?.abort()
                try { close() } catch (closeError: Throwable) { if (closeError !== error) error.addSuppressed(closeError) }
                throw error
            }
        }
        override fun release() {
            var successful = false
            try { source.close(); successful = eof && readingJob?.isActive != false && synchronized(monitor) { !closed } }
            finally { if (successful) write?.complete() else write?.abort() }
        }
    }

    override fun close() {
        val handles = synchronized(monitor) {
            if (closed) return
            closed = true
            active.toList()
        }
        handles.forEach { try { it.close() } catch (_: Exception) { } }
        store.close()
    }
}
