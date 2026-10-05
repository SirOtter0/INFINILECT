// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.infinilect.core.*

/** Original project content only. Explicit debug/opt-in route, never a production catalog.
 * No network, imported files, resource URLs or authorization shortcut for other sources.
 */
internal class DevelopmentEpubSource : PublicationSource {
    override val id = SourceId("development-epub")
    private val publicationId = PublicationId(id, "original-reader-demonstration")
    private val resource = PublicationResource(publicationId, "original-epub", PublicationFormat.EPUB, "application/epub+zip")
    private val publication = Publication(publicationId, "INFINILECT original EPUB demonstration", PublicationType.BOOK,
        authors = listOf("INFINILECT contributors"), languages = listOf("en"), resources = listOf(resource), rights = "GPL-3.0-or-later — original project demonstration")
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
        currentCoroutineContext().ensureActive()
        val bytes = developmentEpubBytes()
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

internal fun developmentEpubBytes(): ByteArray {
    val entries = linkedMapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        "OPS/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book">urn:infinilect:original-demo</dc:identifier><dc:title>INFINILECT original EPUB demonstration</dc:title><dc:language>en</dc:language><dc:creator>INFINILECT contributors</dc:creator><dc:rights>GPL-3.0-or-later</dc:rights><meta property="dcterms:modified">2026-10-04T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${(1..3).joinToString("") { "<item id=\"chapter$it\" href=\"chapter$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}</manifest><spine>${(1..3).joinToString("") { "<itemref idref=\"chapter$it\"/>" }}</spine></package>""",
        "OPS/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>${(1..3).joinToString("") { "<li><a href=\"chapter$it.xhtml#start\">Original chapter $it</a></li>" }}</ol></nav></body></html>""",
    )
    for (chapter in 1..3) entries["OPS/chapter$chapter.xhtml"] = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $chapter</title></head><body><h1 id="start">Original chapter $chapter</h1><p>This is an <em>original</em> INFINILECT demonstration with <strong>semantic EPUB reading</strong>. Unicode: 世界 📚 café.</p><ul><li>Sources obtain publications.</li><li>Readers display them.</li></ul><blockquote><p>Open knowledge. Infinite reading.</p></blockquote><p><a href="chapter${chapter % 3 + 1}.xhtml#start">Follow an internal chapter link</a></p>${(1..40).joinToString("") { "<p id=\"passage$it\">Passage $it in chapter $chapter. This original paragraph gives the reader enough material to scroll, save a semantic position, return to the contents, and reopen after restarting the application. No remote content or scripting is used.</p>" }}</body></html>"""
    return ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip ->
            entries.forEach { (path, text) ->
                val bytes = text.encodeToByteArray()
                val entry = ZipEntry(path).apply { time = 1_700_000_000_000L; method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }
        out.toByteArray().also { check(it.size <= 64 * 1024) }
    }
}
