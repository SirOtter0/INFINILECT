// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.zip

import java.io.RandomAccessFile
import java.util.Locale
import java.text.Normalizer
import org.infinilect.core.EpubEntryPath
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ZipFailure { INVALID, LIMIT, TRANSFER }
internal class BoundedZipException(val failure: ZipFailure) : Exception()
internal fun zipRequire(value: Boolean) { if (!value) throw BoundedZipException(ZipFailure.INVALID) }
internal fun zipLimit(): Nothing = throw BoundedZipException(ZipFailure.LIMIT)
internal fun zipTransfer(): Nothing = throw BoundedZipException(ZipFailure.TRANSFER)

/** Limits for the deliberately supported ZIP32 subset; no archive is extracted to paths. */
internal data class BoundedZipLimits(
    val archiveBytes: Long = 32L * 1024 * 1024,
    val expandedBytes: Long = 64L * 1024 * 1024,
    val entryBytes: Long = 8L * 1024 * 1024,
    val entries: Int = 512,
    val ratio: Int = 100,
) {
    init {
        require(archiveBytes in 1..32L * 1024 * 1024)
        require(expandedBytes in 1..64L * 1024 * 1024)
        require(entryBytes in 1..8L * 1024 * 1024)
        require(entries in 1..512 && ratio in 1..100)
    }
}

/** Internal archive metadata only. `path` is never exposed through PageDocument/core. */
internal data class BoundedZipEntry(
    val name: String,
    val path: String,
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

/** Manual ZIP32 structural validation before using the JDK ZipFile reader. */
internal suspend fun inspectBoundedZip(path: java.nio.file.Path, limits: BoundedZipLimits, epubCompatibility: Boolean = false): List<BoundedZipEntry> =
    RandomAccessFile(path.toFile(), "r").use { file ->
        val length = file.length()
        zipRequire(length in 22..limits.archiveBytes)
        val tailSize = minOf(length, 1046).toInt() // EOCD + at most 1024 comment bytes
        file.seek(length - tailSize)
        val tail = ByteArray(tailSize); file.readFully(tail)
        fun u16(a: ByteArray, p: Int) = (a[p].toInt() and 255) or ((a[p + 1].toInt() and 255) shl 8)
        fun u32(a: ByteArray, p: Int): Long = (0..3).fold(0L) { v, i -> v or ((a[p + i].toLong() and 255) shl (8 * i)) }
        val eocd = (tailSize - 22 downTo 0).firstOrNull {
            u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tailSize
        } ?: throw BoundedZipException(ZipFailure.INVALID)
        zipRequire(u16(tail, eocd + 4) == 0 && u16(tail, eocd + 6) == 0 && u16(tail, eocd + 8) == u16(tail, eocd + 10))
        val count = u16(tail, eocd + 10)
        if (count > limits.entries) zipLimit()
        val centralSize = u32(tail, eocd + 12); val central = u32(tail, eocd + 16)
        zipRequire(central + centralSize == length - tailSize + eocd && centralSize <= limits.archiveBytes)
        file.seek(central)
        val entries = ArrayList<BoundedZipEntry>(count)
        val names = HashSet<String>()
        var expanded = 0L
        fun checkExtra(bytes: ByteArray) {
            var i = 0
            while (i < bytes.size) {
                zipRequire(bytes.size - i >= 4)
                val tag = u16(bytes, i); val size = u16(bytes, i + 2); i += 4
                zipRequire(size <= bytes.size - i && tag != 1 && tag != 0x7075) // ZIP64/alternate names
                i += size
            }
        }
        repeat(count) {
            currentCoroutineContext().ensureActive()
            val h = ByteArray(46); file.readFully(h)
            zipRequire(u32(h, 0) == 0x02014b50L)
            val flags = u16(h, 8); val method = u16(h, 10); val crc = u32(h, 16)
            val compressed = u32(h, 20); val size = u32(h, 24)
            zipRequire((method == 0 || method == 8) && u16(h, 6) in 10..20)
            zipRequire(flags and (0x800 or 0x8 or if (method == 8) 0x6 else 0).inv() == 0)
            zipRequire(u16(h, 34) == 0 && compressed != 0xffffffffL && size != 0xffffffffL && u32(h, 42) != 0xffffffffL)
            val nameLength = u16(h, 28); val extraLength = u16(h, 30); val commentLength = u16(h, 32)
            zipRequire(nameLength in 1..513 && extraLength <= 1024 && commentLength <= 1024)
            val raw = ByteArray(nameLength); file.readFully(raw)
            val name = try { raw.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { throw BoundedZipException(ZipFailure.INVALID) }
            val directory = name.endsWith('/')
            val canonical = if (directory) name.dropLast(1) else name
            zipRequire(canonical.length in 1..512 && canonical.split('/').size <= 32)
            if (epubCompatibility) {
                try { EpubEntryPath(canonical) } catch (_: IllegalArgumentException) { zipRequire(false) }
                zipRequire(Normalizer.isNormalized(canonical, Normalizer.Form.NFC))
            } else zipRequire(canonical.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._~-/" })
            zipRequire(canonical.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
            zipRequire(names.add(canonical.lowercase(Locale.ROOT)))
            val mode = (u32(h, 38) ushr 16).toInt() and 0xf000
            zipRequire(mode == 0 || mode == if (directory) 0x4000 else 0x8000) // reject symlinks/special files
            zipRequire(!directory || size == 0L && (epubCompatibility || compressed == 0L && method == 0))
            if (size > limits.entryBytes || size > maxOf(1L, compressed) * limits.ratio) zipLimit()
            expanded += size; if (expanded > limits.expandedBytes) zipLimit()
            val extras = ByteArray(extraLength); file.readFully(extras); checkExtra(extras)
            file.seek(file.filePointer + commentLength)
            zipRequire(file.filePointer <= central + centralSize)
            entries += BoundedZipEntry(name, canonical, directory, method, flags, crc, compressed, size, u32(h, 42), raw, -1)
        }
        zipRequire(file.filePointer == central + centralSize)
        val ordered = entries.sortedBy { it.offset }
        var expected = 0L
        for (entry in ordered) {
            currentCoroutineContext().ensureActive()
            zipRequire(entry.offset == expected)
            file.seek(entry.offset)
            val h = ByteArray(30); file.readFully(h)
            zipRequire(u32(h, 0) == 0x04034b50L && u16(h, 6) == entry.flags && u16(h, 8) == entry.method && u16(h, 4) in 10..20)
            val nameLength = u16(h, 26); val extraLength = u16(h, 28)
            zipRequire(nameLength == entry.rawName.size && extraLength <= 1024)
            val index = entries.indexOf(entry)
            entries[index] = entry.copy(localExtraLength = extraLength)
            val raw = ByteArray(nameLength); file.readFully(raw); zipRequire(raw.contentEquals(entry.rawName))
            val extras = ByteArray(extraLength); file.readFully(extras); checkExtra(extras)
            if (entry.flags and 8 == 0) zipRequire(u32(h, 14) == entry.crc && u32(h, 18) == entry.compressed && u32(h, 22) == entry.size)
            else zipRequire(listOf(u32(h, 14), u32(h, 18), u32(h, 22)).all { it == 0L } ||
                u32(h, 14) == entry.crc && u32(h, 18) == entry.compressed && u32(h, 22) == entry.size)
            expected = file.filePointer + entry.compressed; zipRequire(expected <= central)
            if (entry.flags and 8 != 0) {
                file.seek(expected); val first = ByteArray(4); file.readFully(first)
                val descriptor = ByteArray(12)
                if (u32(first, 0) == 0x08074b50L) file.readFully(descriptor)
                else { first.copyInto(descriptor); file.readFully(descriptor, 4, 8) }
                zipRequire(u32(descriptor, 0) == entry.crc && u32(descriptor, 4) == entry.compressed && u32(descriptor, 8) == entry.size)
                expected = file.filePointer
            }
        }
        zipRequire(expected == central)
        val files = entries.filterNot { it.directory }.map { it.path.lowercase(Locale.ROOT) }.toSet()
        for (entry in entries) {
            val parts = entry.path.lowercase(Locale.ROOT).split('/')
            for (i in 1 until parts.size) zipRequire(parts.take(i).joinToString("/") !in files)
        }
        entries
    }

/** Verifies actual decompressed byte count and CRC; callbacks see only a bounded prefix. */
internal suspend fun verifyBoundedZipEntries(
    zip: ZipFile,
    entries: List<BoundedZipEntry>,
    limits: BoundedZipLimits,
    inspectPrefix: (BoundedZipEntry, ByteArray) -> Unit = { _, _ -> },
) {
    zipRequire(zip.size() == entries.size)
    val buffer = ByteArray(8192)
    for (entry in entries) {
        currentCoroutineContext().ensureActive()
        val item = zip.getEntry(entry.name) ?: throw BoundedZipException(ZipFailure.INVALID)
        zipRequire(item.size == entry.size && item.compressedSize == entry.compressed && item.crc == entry.crc && item.method == entry.method)
        val crc = CRC32(); val prefix = ByteArray(20); var prefixSize = 0; var count = 0L
        zip.getInputStream(item).use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val maxRead = minOf(buffer.size.toLong(), entry.size - count + 1).toInt()
                val n = stream.read(buffer, 0, maxRead)
                if (n == -1) break
                zipRequire(n > 0 && n <= maxRead)
                count += n; zipRequire(count <= entry.size)
                if (count > limits.entryBytes) zipLimit()
                crc.update(buffer, 0, n)
                val copy = minOf(n, prefix.size - prefixSize)
                if (copy > 0) { buffer.copyInto(prefix, prefixSize, 0, copy); prefixSize += copy }
            }
        }
        zipRequire(count == entry.size && crc.value == entry.crc)
        inspectPrefix(entry, prefix.copyOf(prefixSize))
    }
}
