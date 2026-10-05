// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Path
import java.util.zip.ZipFile
import org.infinilect.app.zip.*
import org.infinilect.core.EpubEntryPath

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
    val entries = try { inspectBoundedZip(path, limits.zipLimits()) }
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
