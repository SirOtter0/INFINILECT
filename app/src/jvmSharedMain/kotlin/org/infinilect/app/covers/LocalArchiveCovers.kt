// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.covers

import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.infinilect.app.epub.*
import org.infinilect.app.media.RasterPolicy
import org.infinilect.app.reader.page.compareNaturalPath
import org.infinilect.app.zip.*
import org.infinilect.core.*

internal data class EncodedCover(val bytes: ByteArray, val mediaType: String)

/** Reads only cover metadata and the chosen image from already validated, digest-checked private
 * imports. Never prepares/copies an entire publication or resolves a remote/catalog URL. */
internal suspend fun localArchiveCover(path: Path, format: PublicationFormat): EncodedCover? = when (format) {
    PublicationFormat.EPUB -> epubCover(path)
    PublicationFormat.CBZ -> comicCover(path)
    else -> null
}
private suspend fun read(zip: ZipFile, name: String, size: Long, crc: Long, limit: Int): ByteArray {
    require(size in 1..limit.toLong())
    val entry = requireNotNull(zip.getEntry(name))
    require(!entry.isDirectory && entry.size == size && entry.crc == crc)
    val bytes = ByteArray(size.toInt())
    zip.getInputStream(entry).use { input ->
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = input.read(bytes, offset, minOf(8192, bytes.size - offset))
            require(count > 0); offset += count
        }
        require(input.read() == -1)
    }
    require(CRC32().apply { update(bytes) }.value == crc)
    return bytes
}
private suspend fun epubCover(path: Path): EncodedCover? {
    val limits = EpubLimits()
    val entries = inspectEpubZip(path, limits).filterNot { it.directory }.associateBy { it.path }
    ZipFile(path.toFile()).use { zip ->
        suspend fun bytes(path: EpubEntryPath, limit: Int): ByteArray {
            val entry = requireNotNull(entries[path]); return read(zip, entry.name, entry.size, entry.crc, limit)
        }
        require(bytes(EpubEntryPath("mimetype"), 20).decodeToString() == "application/epub+zip")
        // Encryption/signature declarations are not thumbnail authorization.
        require(entries.keys.none { it.value == "META-INF/encryption.xml" || it.value == "META-INF/signatures.xml" })
        val container = parseEpubXml(bytes(EpubEntryPath("META-INF/container.xml"), limits.xmlBytes), limits, currentCoroutineContext().job)
        val ns = "urn:oasis:names:tc:opendocument:xmlns:container"
        require(container.name == XmlName(ns, "container") && container.attr("version") == "1.0")
        val rootfile = container.children(ns, "rootfiles").single().children(ns, "rootfile").single()
        require(rootfile.attr("media-type") == "application/oebps-package+xml")
        val opfPath = resolveEpubPath(null, requireNotNull(rootfile.attr("full-path")))
        val opf = parseEpubXml(bytes(opfPath, limits.xmlBytes), limits, currentCoroutineContext().job)
        val opfNs = "http://www.idpf.org/2007/opf"
        require(opf.name == XmlName(opfNs, "package") && opf.attr("version") in listOf("2.0", "3.0"))
        val items = opf.children(opfNs, "manifest").single().children(opfNs, "item")
        require(items.size in 1..limits.manifest)
        val ids = items.map { requireNotNull(it.attr("id")) }
        require(ids.distinct().size == ids.size)
        val declared = items.filter { it.attr("properties")?.split(Regex("\\s+"))?.contains("cover-image") == true }
        val cover = if (declared.isNotEmpty()) declared.single() else {
            val legacy = opf.children(opfNs, "metadata").single().children(opfNs, "meta").filter { it.attr("name") == "cover" }
            if (legacy.isEmpty()) return null
            val id = requireNotNull(legacy.single().attr("content"))
            requireNotNull(items.singleOrNull { it.attr("id") == id })
        }
        val mediaType = cover.attr("media-type") ?: return null
        if (mediaType !in listOf("image/png", "image/jpeg")) return null
        val imagePath = resolveEpubPath(opfPath, requireNotNull(cover.attr("href")))
        return EncodedCover(bytes(imagePath, RasterPolicy.ENCODED_BYTES), mediaType)
    }
}
private suspend fun comicCover(path: Path): EncodedCover? {
    // Strict CBZ policy (including directory rejection) stays independent of EPUB compatibility.
    val entries = inspectBoundedZip(path, BoundedZipLimits())
    require(entries.size in 1..org.infinilect.app.reader.page.PagePolicy.MAX_PAGES)
    fun media(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> null
    }
    require(entries.all { !it.directory && media(it.path) != null })
    val first = entries.sortedWith { a, b -> compareNaturalPath(a.path, b.path) }.first()
    return ZipFile(path.toFile()).use { EncodedCover(read(it, first.name, first.size, first.crc, RasterPolicy.ENCODED_BYTES), requireNotNull(media(first.path))) }
}
