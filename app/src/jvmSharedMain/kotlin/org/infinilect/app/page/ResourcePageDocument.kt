// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.infinilect.core.*

/** Individual-page transport adapter. No archive, URI, filesystem or decoder policy shortcut. */
internal actual fun defaultPagePreparer(): PagePreparer = object : PagePreparer {
    override suspend fun prepare(publication: Publication, loader: ResourceLoader): PageDocument {
        currentCoroutineContext().ensureActive()
        return ResourcePageDocument(publication, loader)
    }
}

internal class ResourcePageDocument(publication: Publication, private val loader: ResourceLoader) : PageDocument {
    override val publicationId = publication.id
    override val title = publication.title
    // One logical ordered sequence per publication in this adapter. Not a fabricated revision.
    override val progressId = ReadingProgressId(publicationId, "page-sequence", PublicationFormat.PAGES)
    override val pages = publication.resources.filter { it.format == PublicationFormat.PAGES }.map(::PageEntry).toList()
    private val lock = Any()
    private var closed = false
    private val opening = mutableSetOf<Job>()
    private val handles = mutableSetOf<Handle>()
    init {
        require(pages.size in 1..PagePolicy.MAX_PAGES)
        require(pages.all { it.resource.publicationId == publicationId && it.key.length <= 512 })
        require(pages.map { it.key }.distinct().size == pages.size)
    }
    override suspend fun openPage(page: PageEntry): ResourceContent {
        require(page.resource.publicationId == publicationId && page in pages && PagePolicy.supported(page))
        val job = currentCoroutineContext().job
        synchronized(lock) { check(!closed); opening.add(job) }
        var content: ResourceContent? = null
        var transferred = false
        try {
            content = loader.load(page.resource)
            currentCoroutineContext().ensureActive()
            return synchronized(lock) {
                check(!closed)
                Handle(checkNotNull(content)).also { handles.add(it); transferred = true }
            }
        } finally {
            synchronized(lock) { opening.remove(job) }
            if (!transferred) content?.close()
        }
    }
    private inner class Handle(private val upstream: ResourceContent) : ResourceContent {
        private val done = AtomicBoolean(false)
        override val sizeBytes get() = upstream.sizeBytes
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int { check(!done.get()); return upstream.read(buffer, offset, length) }
        override fun close() {
            if (!done.compareAndSet(false, true)) return
            synchronized(lock) { handles.remove(this) }
            upstream.close()
        }
    }
    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            opening.toList() to handles.toList() // copied while mutation is locked; no concurrent-set iterator race
        }
        owned.first.forEach { it.cancel() }
        owned.second.forEach { try { it.close() } catch (_: Exception) { } }
    }
}
