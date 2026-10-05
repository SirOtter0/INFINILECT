// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.page

import kotlinx.coroutines.*
import org.infinilect.core.*

/** Controlled original development comic. Composition roots alone expose the debug opt-in. */
internal class DevelopmentComicSource : PublicationSource {
    override val id = SourceId("development-comic")
    private val publicationId = PublicationId(id, "original-panel-journey")
    private val resources = (0 until 24).map { index ->
        val dimensions = when (index % 6) {
            2 -> PageDimensions(768, 512)
            3 -> PageDimensions(640, 640)
            5 -> PageDimensions(320, 1536)
            else -> PageDimensions(512, 768)
        }
        PublicationResource(publicationId, "panel-${index.toString().padStart(2, '0')}",
            PublicationFormat.PAGES, if (index % 6 == 3) "image/jpeg" else "image/png",
            pageDimensions = dimensions)
    }
    private val publication = Publication(publicationId, "INFINILECT · Original panel journey 世界", PublicationType.COMIC,
        authors = listOf("INFINILECT contributors"), languages = listOf("en"), resources = resources,
        rights = "GPL-3.0-or-later — original project demonstration")
    override suspend fun search(query: String, pageToken: String?): SearchPage {
        require(query.length in 1..512 && query.isNotBlank() && pageToken == null)
        currentCoroutineContext().ensureActive()
        return SearchPage(listOf(publication))
    }
    override suspend fun getPublication(publicationId: PublicationId): Publication? {
        require(publicationId.sourceId == id)
        currentCoroutineContext().ensureActive()
        return publication.takeIf { it.id == publicationId }
    }
    override suspend fun loadResource(resource: PublicationResource): ResourceContent = withContext(Dispatchers.Default) {
        val index = resources.indexOf(resource)
        require(index >= 0 && resource.publicationId == publicationId)
        currentCoroutineContext().ensureActive()
        val bytes = if (resource.mediaType == "image/jpeg") comicJpeg() else comicPng(index, checkNotNull(resource.pageDimensions))
        object : ResourceContent {
            override val sizeBytes = bytes.size.toLong()
            private var position = 0
            private var closed = false
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check(!closed); require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
                currentCoroutineContext().ensureActive()
                if (length == 0) return 0
                if (position == bytes.size) return -1
                val count = minOf(length, 8192, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count); position += count
                return count
            }
            override fun close() { closed = true }
        }
    }
}
