// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.io.*
import java.nio.channels.Channels
import java.nio.file.*
import java.security.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*

/** App-only seam: core documents never expose a filesystem path or persistent content cache. */
internal interface EpubSemanticCacheOwner { fun semanticCache(): EpubSemanticCache }
internal object EpubSemanticCachePolicy {
    const val CHAPTERS = 2
    const val CHAPTER_BYTES = 4L * 1024 * 1024
    const val DOCUMENT_BYTES = CHAPTERS * CHAPTER_BYTES
    const val SESSION_FILES = 2 * CHAPTERS // the preparer owns at most two documents
}
internal data class EpubSemanticMetadata(val anchors: Map<String, EpubPosition>, val anchorBlocks: Map<String, Int>, val blocks: Int, val points: Int)
internal class EpubSemanticCacheUnavailable : IOException()
private class CorruptSemanticCache : IOException()
internal data class CachedWindow(val start: Int, val logical: Int, val count: Int, val offset: Long, val bytes: Long, val digest: ByteArray)
private data class CachedChapter(val file: Path, val bytes: Long, val digest: ByteArray, val windows: List<CachedWindow>, val metadata: EpubSemanticMetadata, val initialQuery: EpubWindowRequest, val initialMatch: Int?)
private const val CACHE_MAGIC = 0x4550554257494E31L // EPUBWIN1, session-private, never a durable schema

/** Shared by this preparer's documents. Failed deletion keeps its reservation even
 * after a document closes, so repeated opens cannot accumulate orphaned cache files.
 * The short allocation/deletion critical section runs only on IO, not on the UI.
 */
internal class EpubSemanticFiles(private val directory: Path) {
    private val lock = Any()
    private val owned = mutableSetOf<Path>()
    internal val retainedFiles get() = synchronized(lock) { owned.size }
    fun create(): Path = synchronized(lock) {
        if (owned.size >= EpubSemanticCachePolicy.SESSION_FILES || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw EpubSemanticCacheUnavailable()
        Files.createFile(directory.resolve("epub-semantic-${UUID.randomUUID()}.bin")).also(owned::add)
    }
    fun delete(path: Path) = synchronized(lock) {
        check(path in owned)
        Files.deleteIfExists(path); owned.remove(path)
        Unit
    }
}

/** Two indexed chapter files, <=8MiB including construction. No semantic text retained here.
 * One serialized operation. Files are random owned names inside the preparer's locked private
 * session; metadata/checksums live only in this document. Never trust/reuse files after restart.
 */
internal class EpubSemanticCache(private val files: EpubSemanticFiles, private val chapterBytes: Long = EpubSemanticCachePolicy.CHAPTER_BYTES) {
    constructor(directory: Path, chapterBytes: Long = EpubSemanticCachePolicy.CHAPTER_BYTES) : this(EpubSemanticFiles(directory), chapterBytes)
    init { require(chapterBytes in 8..EpubSemanticCachePolicy.CHAPTER_BYTES) }
    private val closed = AtomicBoolean()
    private val mutex = Mutex()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    private val entries = linkedMapOf<EpubEntryPath, CachedChapter>()
    private val owned = linkedSetOf<Path>() // includes incomplete files until deletion succeeds
    private val unavailable = mutableSetOf<EpubEntryPath>() // <=128 owned spine paths; capacity failures only
    internal var builds = 0; private set
    internal var hits = 0; private set
    internal val retainedBytes get() = entries.values.sumOf { it.bytes }
    internal val retainedChapters get() = entries.size
    internal val retainedWindowDescriptors get() = entries.values.sumOf { it.windows.size }
    internal val retainedAnchorRecords get() = entries.values.sumOf { it.metadata.anchors.size + it.metadata.anchorBlocks.size }
    fun invalidate() { closed.set(true); jobs.forEach { it.cancel() } }
    suspend fun closeAndJoin() {
        invalidate()
        mutex.withLock {
            // Try every owned file even if one deletion fails. Shutdown's session sweep is
            // a second best-effort cleanup; a failed deletion never permits another build.
            owned.toList().forEach { try { delete(it) } catch (_: IOException) { } }
            entries.clear(); unavailable.clear()
        }
    }
    suspend fun window(document: EpubDocument, path: EpubEntryPath, query: EpubWindowRequest,
                       build: suspend (Writer) -> EpubSemanticMetadata): EpubChapter {
        val job = currentCoroutineContext().job
        jobs.add(job)
        try { return mutex.withLock {
            job.ensureActive(); if (closed.get()) throw CancellationException("EPUB document closed")
            if (path in unavailable) throw EpubSemanticCacheUnavailable()
            var entry = entries[path]
            if (entry != null) {
                try {
                    val result = read(document, path, entry, query)
                    entries.remove(path); entries[path] = entry; hits++; return@withLock result
                } catch (error: CancellationException) { throw error }
                catch (_: IOException) { delete(entry.file); entries.remove(path); entry = null }
                catch (_: IllegalArgumentException) { delete(entry.file); entries.remove(path); entry = null }
            }
            // Reserve the full construction quota BEFORE writing. Two files including partial
            // construction, never two old 4MiB chapters plus another 4MiB temporary chapter.
            while (entries.size >= EpubSemanticCachePolicy.CHAPTERS) {
                val key = entries.keys.first(); delete(entries.getValue(key).file); entries.remove(key)
            }
            // Remove any failed construction before reserving another slot.
            owned.filter { file -> entries.values.none { it.file == file } }.toList().forEach(::delete)
            check(owned.size < EpubSemanticCachePolicy.CHAPTERS)
            val file = files.create()
            owned.add(file)
            var committed = false
            try {
                val writer = Writer(file, chapterBytes, query)
                val metadata: EpubSemanticMetadata
                writer.use { metadata = build(it); it.finish() }
                job.ensureActive(); if (closed.get()) throw CancellationException("EPUB document closed")
                entry = CachedChapter(file, writer.size, writer.fileHash, writer.windows.toList(), metadata, query, writer.exact)
                val result = read(document, path, entry, query)
                entries[path] = entry; committed = true; builds++
                result
            } catch (error: EpubSemanticCacheUnavailable) {
                unavailable.add(path); throw error
            } catch (_: IOException) {
                throw EpubSemanticCacheUnavailable()
            } finally { if (!committed) delete(file) }
        } } catch (error: CancellationException) { throw error }
        catch (_: IOException) { throw EpubSemanticCacheUnavailable() }
        finally { jobs.remove(job) }
    }
    private fun delete(path: Path) { files.delete(path); owned.remove(path) }
    private fun opened(entry: CachedChapter): java.nio.channels.SeekableByteChannel {
        val channel = Files.newByteChannel(entry.file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        try {
            if (channel.size() != entry.bytes || entry.bytes !in 8..chapterBytes) throw CorruptSemanticCache()
            val input = DataInputStream(Channels.newInputStream(channel))
            if (input.readLong() != CACHE_MAGIC) throw CorruptSemanticCache()
            return channel
        } catch (error: Throwable) { channel.close(); throw error }
    }
    private suspend fun read(document: EpubDocument, path: EpubEntryPath, entry: CachedChapter, query: EpubWindowRequest): EpubChapter {
        val metadata = entry.metadata
        val block = when (query) {
            is EpubWindowRequest.Block -> query.index.coerceIn(0, metadata.blocks - 1)
            is EpubWindowRequest.Anchor -> metadata.anchorBlocks[query.value] ?: invalid()
            EpubWindowRequest.End -> metadata.blocks - 1
            is EpubWindowRequest.Locator -> if (query == entry.initialQuery) entry.initialMatch else findLocator(entry, query.value)
        }
        val window = if (block != null) entry.windows.last { it.start <= block }
            else entry.windows.last { it.logical <= (metadata.points * (query as EpubWindowRequest.Locator).value.chapterProgression).toInt() }
        val blocks = ArrayList<EpubBlock>(window.count)
        val manifest = document.manifest.associateBy { it.path }
        val spine = document.spine.map { ref -> document.manifest.single { it.id == ref.itemId }.path }.toSet()
        opened(entry).use { channel ->
            channel.position(window.offset)
            val hash = MessageDigest.getInstance("SHA-256")
            val input = DataInputStream(DigestInputStream(BufferedInputStream(Channels.newInputStream(channel), EPUB_BUFFER_BYTES), hash))
            var units = 0; var runs = 0; var consumed = 0L
            repeat(window.count) {
                currentCoroutineContext().ensureActive()
                val size = input.readInt()
                if (size !in 12..chapterBytes || consumed + 4 + size > window.bytes) throw CorruptSemanticCache()
                // Read one bounded block, not a window-sized encoded byte array.
                val value = readBlock(input, manifest, spine, EpubWindowPolicy.TEXT_UNITS - units, EpubWindowPolicy.APPEND_EVENTS - runs)
                if (encodedSize(value) != size) throw CorruptSemanticCache()
                blocks.add(value); units += value.text.length; runs += value.runs.size; consumed += 4 + size
            }
            if (consumed != window.bytes || !MessageDigest.isEqual(hash.digest(), window.digest)) throw CorruptSemanticCache()
        }
        currentCoroutineContext().ensureActive()
        return EpubChapter(path, blocks, metadata.anchors, window.start, metadata.blocks, metadata.points)
    }
    private suspend fun findLocator(entry: CachedChapter, locator: ReadingLocator.Epub): Int? {
        var exact: Int? = null
        opened(entry).use { channel ->
            channel.position(0)
            val digest = MessageDigest.getInstance("SHA-256")
            val input = DataInputStream(DigestInputStream(BufferedInputStream(Channels.newInputStream(channel), EPUB_BUFFER_BYTES), digest))
            if (input.readLong() != CACHE_MAGIC) throw CorruptSemanticCache()
            val scratch = ByteArray(EPUB_BUFFER_BYTES)
            repeat(entry.metadata.blocks) { index ->
                currentCoroutineContext().ensureActive()
                val bytes = input.readInt(); if (bytes !in 12..chapterBytes) throw CorruptSemanticCache()
                val address = readAddress(input)
                val offset = input.readInt(); input.readInt() // whole-chapter logical start
                if (address == locator.elementPath && offset <= locator.codePointOffset) exact = index
                var remaining = bytes - (4 + address.size * 4 + 8)
                if (remaining < 0) throw CorruptSemanticCache()
                while (remaining > 0) { val n = input.read(scratch, 0, minOf(scratch.size, remaining)); if (n <= 0) throw CorruptSemanticCache(); remaining -= n }
            }
            if (input.read() != -1 || !MessageDigest.isEqual(digest.digest(), entry.digest)) throw CorruptSemanticCache()
        }
        return exact
    }
    internal class Writer(file: Path, private val maxBytes: Long, private val query: EpubWindowRequest) : Closeable {
        private val windowHash = MessageDigest.getInstance("SHA-256")
        private val wholeHash = MessageDigest.getInstance("SHA-256")
        var size = 0L; private set
        private val raw = BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), EPUB_BUFFER_BYTES)
        private val output = DataOutputStream(object : OutputStream() {
            private val single = ByteArray(1)
            override fun write(b: Int) { single[0] = b.toByte(); write(single, 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (size + len > maxBytes) throw EpubSemanticCacheUnavailable()
                raw.write(b, off, len); size += len; windowHash.update(b, off, len); wholeHash.update(b, off, len)
            }
            override fun close() { raw.close() }
        })
        internal val windows = mutableListOf<CachedWindow>()
        var exact: Int? = null; private set
        lateinit var fileHash: ByteArray; private set
        private var start = 0; private var logical = 0; private var offset = 8L; private var count = 0; private var units = 0; private var events = 0
        init { try { output.writeLong(CACHE_MAGIC); windowHash.reset() } catch (error: Throwable) { raw.close(); throw error } }
        fun accept(block: EpubBlock, index: Int, appended: Int) {
            val locator = (query as? EpubWindowRequest.Locator)?.value
            if (locator != null && block.elementPath == locator.elementPath && block.startOffset <= locator.codePointOffset) exact = index
            if (count > 0 && (count == EpubWindowPolicy.BLOCKS || units + block.text.length > EpubWindowPolicy.TEXT_UNITS || events + appended > EpubWindowPolicy.APPEND_EVENTS)) checkpoint()
            if (count == 0) { start = index; logical = block.logicalStart; offset = size }
            if (windows.size >= EpubWindowPolicy.INDEX_ENTRIES) limit()
            output.writeInt(encodedSize(block)); writeBlock(output, block)
            count++; units += block.text.length; events += appended
        }
        private fun checkpoint() {
            if (count == 0) return
            windows.add(CachedWindow(start, logical, count, offset, size - offset, windowHash.digest()))
            count = 0; units = 0; events = 0
        }
        fun finish() { checkpoint(); raw.flush(); fileHash = wholeHash.digest() }
        override fun close() { output.close() }
    }
}

private fun utf8Size(value: String): Int {
    var size = 0; var i = 0
    while (i < value.length) { val c = value[i++].code; size += when { c < 128 -> 1; c < 2048 -> 2; c in 0xd800..0xdbff -> { i++; 4 }; else -> 3 } }
    return size
}
private fun stringSize(value: String) = 4 + utf8Size(value)
private fun encodedSize(b: EpubBlock): Int = 4 + b.elementPath.size * 4 + 8 + 4 + 4 + 1 + (if (b.listMarker != null) 9 else 0) + 1 +
    (b.image?.let { stringSize(it.path.value) + stringSize(it.mediaType) + stringSize(it.alt) } ?: 0) + 4 +
    b.runs.sumOf { 1 + stringSize(it.text) + (it.target?.let { t -> stringSize(t.path.value) + 1 + (t.anchor?.let(::stringSize) ?: 0) } ?: 0) }
private fun writeString(out: DataOutputStream, value: String) { val bytes = value.encodeToByteArray(); out.writeInt(bytes.size); out.write(bytes) }
private fun writeBlock(out: DataOutputStream, b: EpubBlock) {
    out.writeInt(b.elementPath.size); b.elementPath.forEach(out::writeInt); out.writeInt(b.startOffset); out.writeInt(b.logicalStart)
    out.writeInt(b.kind.ordinal); out.writeInt(b.headingLevel)
    out.writeBoolean(b.listMarker != null); b.listMarker?.let { out.writeBoolean(it.ordered); out.writeInt(it.ordinal); out.writeInt(it.depth) }
    out.writeBoolean(b.image != null); b.image?.let { writeString(out, it.path.value); writeString(out, it.mediaType); writeString(out, it.alt) }
    out.writeInt(b.runs.size)
    b.runs.forEach { r ->
        out.writeByte((if (r.emphasis) 1 else 0) or (if (r.strong) 2 else 0) or (if (r.target != null) 4 else 0)); writeString(out, r.text)
        r.target?.let { writeString(out, it.path.value); out.writeBoolean(it.anchor != null); it.anchor?.let { a -> writeString(out, a) } }
    }
}
private fun readAddress(input: DataInputStream): List<Int> {
    val count = input.readInt(); if (count !in 1..32) throw CorruptSemanticCache()
    return List(count) { input.readInt().also { if (it !in 0..20_000) throw CorruptSemanticCache() } }
}
private fun readString(input: DataInputStream, maxUnits: Int): String {
    val bytes = input.readInt(); if (bytes !in 0..maxUnits * 4) throw CorruptSemanticCache()
    val value = ByteArray(bytes); input.readFully(value)
    return value.decodeToString(throwOnInvalidSequence = true).also { if (it.length > maxUnits) throw CorruptSemanticCache() }
}
private fun readBlock(input: DataInputStream, manifest: Map<EpubEntryPath, EpubManifestItem>, spine: Set<EpubEntryPath>, windowUnits: Int, windowRuns: Int): EpubBlock {
    val path = readAddress(input); val offset = input.readInt(); val logical = input.readInt()
    if (offset !in 0..1_048_576 || logical !in 0..1_048_576) throw CorruptSemanticCache()
    val kind = input.readInt(); if (kind !in EpubBlockKind.entries.indices) throw CorruptSemanticCache()
    val heading = input.readInt(); if (heading !in 1..6) throw CorruptSemanticCache()
    val marker = if (input.readBoolean()) {
        EpubListMarker(input.readBoolean(), input.readInt(), input.readInt()).also { if (it.ordinal !in 1..9999 || it.depth !in 0..7) throw CorruptSemanticCache() }
    } else null
    val image = if (input.readBoolean()) {
        EpubImage(EpubEntryPath(readString(input, 512)), readString(input, 128), readString(input, 256)).also { image ->
            if (manifest[image.path]?.mediaType != image.mediaType) throw CorruptSemanticCache()
        }
    } else null
    val count = input.readInt(); if (count !in 0..minOf(windowRuns, EpubWindowPolicy.APPEND_EVENTS)) throw CorruptSemanticCache()
    var units = 0
    val runs = List(count) {
        val flags = input.readUnsignedByte(); if (flags and 7 != flags) throw CorruptSemanticCache()
        val text = readString(input, minOf(8192 - units, windowUnits - units)); units += text.length
        val target = if (flags and 4 != 0) {
            val targetPath = EpubEntryPath(readString(input, 512))
            val anchor = if (input.readBoolean()) readString(input, 128).also { if (!validEpubContentAnchor(it)) throw CorruptSemanticCache() } else null
            if (targetPath !in spine) throw CorruptSemanticCache()
            EpubTarget(targetPath, anchor)
        } else null
        EpubRun(text, flags and 1 != 0, flags and 2 != 0, target)
    }
    return EpubBlock(path, offset, EpubBlockKind.entries[kind], runs, logical, heading, marker, image)
}
