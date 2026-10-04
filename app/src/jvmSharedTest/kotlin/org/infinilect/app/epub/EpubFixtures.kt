// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.infinilect.core.*

/** Tiny, original synthetic documents: no downloaded or copyrighted publication fixture. */
internal class EpubFixture(val packagePath: String = "OPS/package.opf") {
    val entries = linkedMapOf<String, ByteArray>()
    var deflate = false
    init {
        entries["mimetype"] = "application/epub+zip".encodeToByteArray()
        entries["META-INF/container.xml"] = """
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles><rootfile full-path="$packagePath" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent().encodeToByteArray()
        val parent = packagePath.substringBeforeLast('/', "")
        fun local(name: String) = if (parent.isEmpty()) name else "$parent/$name"
        entries[packagePath] = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book">urn:synthetic:foundation</dc:identifier>
                <dc:title>Résumé 📚</dc:title><dc:language>fr</dc:language><dc:language>en</dc:language>
                <dc:creator>作者</dc:creator><dc:rights>Original test fixture</dc:rights>
                <meta property="dcterms:modified">2026-10-04T00:00:00Z</meta>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="chapter"/></spine>
            </package>
        """.trimIndent().encodeToByteArray()
        entries[local("nav.xhtml")] = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>Contents</title></head><body><nav epub:type="toc"><ol>
              <li><a href="chapter.xhtml#start">Chapter</a></li></ol></nav></body>
            </html>
        """.trimIndent().encodeToByteArray()
        entries[local("chapter.xhtml")] = """
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head>
            <body><p id="start">Hello, 世界 📖</p></body></html>
        """.trimIndent().encodeToByteArray()
    }
    fun change(path: String, transform: (String) -> String) {
        entries[path] = transform(checkNotNull(entries[path]).decodeToString()).encodeToByteArray()
    }
    fun opf(transform: (String) -> String) = change(packagePath, transform)
    fun zip(): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                entry.time = 1_700_000_000_000L
                if (!deflate || name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }
        out.toByteArray()
    }
}

internal class EpubBytes(
    private val bytes: ByteArray,
    override val sizeBytes: Long? = bytes.size.toLong(),
    private val chunk: Int = 317,
    private val beforeRead: suspend () -> Unit = {},
) : ResourceContent {
    var offset = 0
    var closed = false
    var closeCalls = 0
    var maximumRequest = 0
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed)
        beforeRead()
        maximumRequest = maxOf(maximumRequest, length)
        if (this.offset == bytes.size) return -1
        val n = minOf(chunk, length, bytes.size - this.offset)
        bytes.copyInto(buffer, offset, this.offset, this.offset + n)
        this.offset += n
        return n
    }
    override fun close() { if (!closed) { closed = true; closeCalls++ } }
}

internal val epubId = PublicationId(SourceId("synthetic"), "fixture")
internal val epubResource = PublicationResource(epubId, "book", PublicationFormat.EPUB, "application/epub+zip")
internal val epubPublication = Publication(epubId, "Fixture", PublicationType.BOOK, resources = listOf(epubResource))
internal fun epubLoader(content: ResourceContent) = object : ResourceLoader {
    override suspend fun load(resource: PublicationResource): ResourceContent {
        check(resource == epubResource)
        return content
    }
}
internal fun payloads(base: Path): List<Path> = Files.walk(base).use { stream ->
    stream.filter { it.fileName.toString().startsWith("epub-") && it.fileName.toString().endsWith(".zip") }.toList()
}
