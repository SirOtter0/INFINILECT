// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.page

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.infinilect.core.*

/** Debug-only original fixture. Physical ZIP order intentionally differs from natural page order. */
internal class DevelopmentCbzSource : PublicationSource {
    override val id = SourceId("development-cbz")
    private val publicationId = PublicationId(id, "original-panel-journey-cbz")
    private val resource = PublicationResource(publicationId, "original-development-cbz", PublicationFormat.CBZ,
        "application/vnd.comicbook+zip")
    private val publication = Publication(publicationId, "INFINILECT original CBZ demonstration", PublicationType.COMIC,
        authors = listOf("INFINILECT contributors"), languages = listOf("en"), resources = listOf(resource),
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
    override suspend fun loadResource(resource: PublicationResource): ResourceContent {
        require(resource == this.resource)
        val bytes = developmentCbzBytes()
        return object : ResourceContent {
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

internal suspend fun developmentCbzBytes(): ByteArray {
    val pages = listOf(
        "pages/10.jpg" to (9 to PageDimensions(768, 512)),
        "pages/2.png" to (1 to PageDimensions(512, 768)),
        "pages/1.png" to (0 to PageDimensions(512, 768)),
        "pages/04/nested.png" to (3 to PageDimensions(640, 640)),
        "pages/03.jpg" to (2 to PageDimensions(768, 512)),
        "pages/5.png" to (4 to PageDimensions(320, 1536)),
        "pages/6.png" to (5 to PageDimensions(512, 768)),
        "pages/7.png" to (6 to PageDimensions(512, 768)),
        "pages/8.jpg" to (7 to PageDimensions(768, 512)),
        "pages/9.png" to (8 to PageDimensions(512, 768)),
    )
    val payloads = pages.map { (path, indexed) ->
        currentCoroutineContext().ensureActive()
        path to if (path.endsWith(".jpg")) comicJpeg() else comicPng(indexed.first, indexed.second)
    }
    return ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((path, content) in payloads) {
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(path).apply { time = 1_700_000_000_000L })
                zip.write(content); zip.closeEntry()
            }
        }
        bytes.toByteArray().also { check(it.size <= 4 * 1024 * 1024) }
    }
}
