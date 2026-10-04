// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.infinilect.core.EpubEntryPath
internal data class EpubZipEntry(val name: String, val path: EpubEntryPath, val directory: Boolean, val method: Int, val flags: Int, val crc: Long, val compressed: Long, val size: Long, val offset: Long, val rawName: ByteArray)

/** Check both directories and local records before trusting ZipFile's permissive ZIP reader.
* ZIP32 STORED/DEFLATED only, no prefix/trailer/overlap/ZIP64/split archives or extraction.
*/
internal suspend fun inspectEpubZip(path: Path, limits: EpubLimits): List<EpubZipEntry>  = RandomAccessFile(path.toFile(), "r").use {
    file ->
    val length = file.length()
    requireEpub(length in 22..limits.archiveBytes)
    val tailSize = minOf(length, 1046).toInt() // bounded 1024-byte archive comment
    file.seek(length - tailSize)
    val tail = ByteArray(tailSize)
    file.readFully(tail)
    fun u16(a: ByteArray, p: Int) = (a[p].toInt() and 255) or ((a[p+1].toInt() and 255) shl 8)
    fun u32(a: ByteArray, p: Int): Long = (0..3).fold(0L) {
        v, i -> v or ((a[p+i].toLong() and 255) shl (8*i))
    }
    val e = (tailSize-22 downTo 0).firstOrNull {
        u32(tail, it)==0x06054b50L && it+22+u16(tail, it+20)==tailSize
    }
    ?: invalid()
    requireEpub(u16(tail, e+4)==0 && u16(tail, e+6)==0 && u16(tail, e+8)==u16(tail, e+10))
    val count = u16(tail, e+10)
    if (count>limits.entries) limit()
    requireEpub(count>0)
    val centralSize = u32(tail, e+12)
    val central = u32(tail, e+16)
    requireEpub(central+centralSize==length-tailSize+e && centralSize<=limits.archiveBytes)
    file.seek(central)
    val entries = ArrayList<EpubZipEntry>(count)
    val names = HashSet<String>()
    var expanded = 0L
    fun extra(bytes: ByteArray) {
        var i = 0
        while (i<bytes.size) {
            requireEpub(bytes.size-i>=4)
            val tag = u16(bytes, i)
            val size = u16(bytes, i+2)
            i+=4
            requireEpub(size<=bytes.size-i && tag!=1 && tag!=0x7075)
            i+=size // no ZIP64/alternate Unicode names
        }
    }
    repeat(count) {
        currentCoroutineContext().ensureActive()
        val h = ByteArray(46)
        file.readFully(h)
        requireEpub(u32(h, 0)==0x02014b50L)
        val flags = u16(h, 8)
        val method = u16(h, 10)
        val crc = u32(h, 16)
        val compressed = u32(h, 20)
        val size = u32(h, 24)
        requireEpub((method==0 || method==8) && u16(h, 6) in 10..20)
        requireEpub(flags and (0x800 or 0x8 or if (method==8) 0x6 else 0).inv()==0)
        requireEpub(u16(h, 34)==0 && compressed!=0xffffffffL && size!=0xffffffffL)
        val n = u16(h, 28)
        val x = u16(h, 30)
        val c = u16(h, 32)
        requireEpub(n in 1..513 && x<=1024 && c<=1024)
        val raw = ByteArray(n)
        file.readFully(raw)
        val name = try {
            raw.decodeToString(throwOnInvalidSequence = true)
        }
        catch (_: Exception) {
            invalid()
        }
        val directory = name.endsWith('/')
        val canonical = try {
            EpubEntryPath(if (directory) name.dropLast(1) else name)
        }
        catch (_: IllegalArgumentException) {
            invalid()
        }
        requireEpub(names.add(canonical.value.lowercase(Locale.ROOT)))
        val mode = (u32(h, 38) ushr 16).toInt() and 0xf000
        requireEpub(mode==0 || mode==if (directory) 0x4000 else 0x8000)
        requireEpub(!directory || size==0L && compressed==0L && method==0)
        if (size>limits.entryBytes || size>maxOf(1L, compressed)*limits.ratio) limit()
        expanded+=size
        if (expanded>limits.expandedBytes) limit()
        val extras = ByteArray(x)
        file.readFully(extras)
        extra(extras)
        file.seek(file.filePointer+c)
        requireEpub(file.filePointer<=central+centralSize)
        entries.add(EpubZipEntry(name, canonical, directory, method, flags, crc, compressed, size, u32(h, 42), raw))
    }
    requireEpub(file.filePointer==central+centralSize)
    val ordered = entries.sortedBy {
        it.offset
    }
    var expected = 0L
    ordered.forEach {
        entry ->
        currentCoroutineContext().ensureActive()
        requireEpub(entry.offset==expected)
        file.seek(entry.offset)
        val h = ByteArray(30)
        file.readFully(h)
        requireEpub(u32(h, 0)==0x04034b50L && u16(h, 6)==entry.flags && u16(h, 8)==entry.method && u16(h, 4) in 10..20)
        val n = u16(h, 26)
        val x = u16(h, 28)
        requireEpub(n==entry.rawName.size && x<=1024)
        val raw = ByteArray(n)
        file.readFully(raw)
        requireEpub(raw.contentEquals(entry.rawName))
        val extras = ByteArray(x)
        file.readFully(extras)
        extra(extras)
        if (entry.flags and 8==0) requireEpub(u32(h, 14)==entry.crc && u32(h, 18)==entry.compressed && u32(h, 22)==entry.size)
        else requireEpub(listOf(u32(h, 14), u32(h, 18), u32(h, 22)).all {
            it==0L
        }
        ||
        u32(h, 14)==entry.crc && u32(h, 18)==entry.compressed && u32(h, 22)==entry.size)
        expected = file.filePointer+entry.compressed
        requireEpub(expected<=central)
        if (entry.flags and 8!=0) {
            file.seek(expected)
            val first = ByteArray(4)
            file.readFully(first)
            val descriptor = ByteArray(12)
            if (u32(first, 0)==0x08074b50L) file.readFully(descriptor)
            else {
                first.copyInto(descriptor)
                file.readFully(descriptor, 4, 8)
            }
            requireEpub(u32(descriptor, 0)==entry.crc && u32(descriptor, 4)==entry.compressed && u32(descriptor, 8)==entry.size)
            expected = file.filePointer
        }
    }
    requireEpub(expected==central)
    val first = ordered.first()
    requireEpub(first.name=="mimetype" && first.offset==0L && first.method==0 && first.flags and 8==0 && first.size==20L)
    file.seek(0)
    val mimeHeader = ByteArray(30)
    file.readFully(mimeHeader)
    requireEpub(u16(mimeHeader, 28)==0)
    // A regular file cannot also be an ancestor directory, including case aliases.
    val files = entries.filterNot {
        it.directory
    }
    .map {
        it.path.value.lowercase(Locale.ROOT)
    }
    .toSet()
    for (entry in entries) {
        val segments = entry.path.value.lowercase(Locale.ROOT).split('/')
        for (i in 1 until segments.size) requireEpub(segments.take(i).joinToString("/") !in files)
    }
    entries
}
internal suspend fun verifyEpubEntries(zip: ZipFile, entries: List<EpubZipEntry>, limits: EpubLimits) {
    requireEpub(zip.size()==entries.size)
    val buffer = ByteArray(EPUB_BUFFER_BYTES)
    for (entry in entries) {
        currentCoroutineContext().ensureActive()
        val item = zip.getEntry(entry.name) ?: invalid()
        requireEpub(item.size==entry.size && item.compressedSize==entry.compressed && item.crc==entry.crc && item.method==entry.method)
        val crc = CRC32()
        var count = 0L
        val prefix = ByteArray(4)
        var prefixCount = 0
        val mime = if (entry.name=="mimetype") ByteArray(20) else null
        zip.getInputStream(item).use {
            stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), entry.size-count+1).toInt())
                if (n==-1) break
                requireEpub(n>0 && n<=buffer.size)
                count+=n
                requireEpub(count<=entry.size)
                if (count>limits.entryBytes) limit()
                crc.update(buffer, 0, n)
                mime?.let {
                    buffer.copyInto(it, (count-n).toInt(), 0, n)
                }
                val p = minOf(n, 4-prefixCount)
                buffer.copyInto(prefix, prefixCount, 0, p)
                prefixCount+=p
            }
        }
        requireEpub(count==entry.size && crc.value==entry.crc)
        if (entry.name=="mimetype") requireEpub(checkNotNull(mime).decodeToString()=="application/epub+zip")
        else if (!entry.directory) {
            requireEpub(!Regex("(?i).*\\.(zip|epub|jar|cbz|7z|rar|gz)$").matches(entry.name))
            requireEpub(!(prefixCount==4 && prefix[0]==80.toByte() && prefix[1]==75.toByte() &&
            (prefix[2]==3.toByte() && prefix[3]==4.toByte() || prefix[2]==5.toByte() && prefix[3]==6.toByte())))
        }
    }
}
