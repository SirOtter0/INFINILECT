// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipFile
import org.infinilect.app.zip.*
import org.infinilect.core.EpubEntryPath

/** Routing/error identity only: the bounded 58-byte OCF first-entry marker.
 * Never authorizes import; complete ZIP/container/package validation still must pass. */
internal fun hasEpubMarker(path: Path): Boolean {
    val bytes = ByteArray(58)
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { stream ->
        var offset = 0
        while (offset < bytes.size) {
            val count = stream.read(bytes, offset, bytes.size - offset)
            if (count <= 0) return false
            offset += count
        }
    }
    fun u16(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    fun u32(offset: Int) = (0..3).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 255) shl (8 * i)) }
    return u32(0) == 0x04034b50L && u16(8) == 0 && u16(6) and 9 == 0 &&
        u32(18) == 20L && u32(22) == 20L && u16(26) == 8 && u16(28) == 0 &&
        bytes.copyOfRange(30, 38).contentEquals("mimetype".encodeToByteArray()) &&
        bytes.copyOfRange(38, 58).contentEquals("application/epub+zip".encodeToByteArray())
}

/** EPUB projection of the shared ZIP32 structural metadata. EPUB semantics remain here. */
internal data class EpubZipEntry(
    val name: String,
    val path: EpubEntryPath,
    val directory: Boolean,
    val method: Int,
    val flags: Int,
    val crc: Long,
    val compressed: Long,
    val size: Long,
    val offset: Long,
    val rawName: ByteArray,
    val localExtraLength: Int,
)

private fun EpubLimits.zipLimits() = BoundedZipLimits(archiveBytes, expandedBytes, entryBytes, entries, ratio)

internal suspend fun inspectEpubZip(path: Path, limits: EpubLimits): List<EpubZipEntry> {
    val entries = try { inspectBoundedZip(path, limits.zipLimits(), epubCompatibility = true) }
    catch (error: BoundedZipException) {
        when (error.failure) { ZipFailure.LIMIT -> limit(); ZipFailure.INVALID, ZipFailure.TRANSFER -> invalid() }
    }
    requireEpub(entries.isNotEmpty())
    val mapped = entries.map { entry ->
        val zipPath = try { EpubEntryPath(entry.path) } catch (_: IllegalArgumentException) { invalid() }
        EpubZipEntry(entry.name, zipPath, entry.directory, entry.method, entry.flags, entry.crc,
            entry.compressed, entry.size, entry.offset, entry.rawName, entry.localExtraLength)
    }
    val first = mapped.sortedBy { it.offset }.first()
    requireEpub(first.name == "mimetype" && first.offset == 0L && first.method == 0 && first.flags and 8 == 0 &&
        first.size == 20L && first.localExtraLength == 0)
    return mapped
}

internal suspend fun verifyEpubEntries(zip: ZipFile, entries: List<EpubZipEntry>, limits: EpubLimits) {
    return try {
        verifyBoundedZipEntries(zip, entries.map {
            BoundedZipEntry(it.name, it.path.value, it.directory, it.method, it.flags, it.crc, it.compressed,
                it.size, it.offset, it.rawName, it.localExtraLength)
        }, limits.zipLimits()) { entry, prefix ->
            if (entry.path == "mimetype") requireEpub(prefix.decodeToString() == "application/epub+zip")
            else if (!entry.directory) {
                requireEpub(!Regex("(?i).*\\.(zip|epub|jar|cbz|7z|rar|gz)$").matches(entry.path))
                requireEpub(!(prefix.size >= 4 && prefix[0] == 80.toByte() && prefix[1] == 75.toByte() &&
                    (prefix[2] == 3.toByte() && prefix[3] == 4.toByte() || prefix[2] == 5.toByte() && prefix[3] == 6.toByte())))
            }
        }
    } catch (error: BoundedZipException) {
        when (error.failure) { ZipFailure.LIMIT -> limit(); ZipFailure.INVALID, ZipFailure.TRANSFER -> invalid() }
    }
}
