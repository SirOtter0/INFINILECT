// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.media.RasterPolicy
import org.infinilect.app.media.inspectRaster
import org.infinilect.app.zip.*
import org.infinilect.core.*

internal const val CBZ_PREPARATION_DIRECTORY = "cbz-preparation-v1"
private const val CBZ_ARCHIVE_MIME = "application/vnd.comicbook+zip"
private const val CBZ_BUFFER_BYTES = 8192
private const val CBZ_MAX_HANDLES = 3
private const val CBZ_MAX_DOCUMENTS = 2
private val CBZ_FILE_NAME = Regex("cbz-[0-9a-f-]{36}\\.zip")
private val CBZ_PART_NAME = Regex("cbz-[0-9a-f-]{36}\\.part")
private val CBZ_OWNER_NAME = ".owner.lock"
private val CBZ_LIMITS = BoundedZipLimits()

private class CbzPreparationFailure(val reason: ZipFailure) : Exception()

/** The ZIP/resource boundary is owned here. Core PageDocument only sees ordinal page keys. */
internal class CbzPagePreparer(
    private val directory: Path?,
    private val fallback: PagePreparer = defaultPagePreparer(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PagePreparer {
    private val closed = AtomicBoolean()
    private val mutex = Mutex()
    private val jobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val documents = java.util.concurrent.ConcurrentHashMap.newKeySet<CbzDocument>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + io)
    private var ownerFile: FileChannel? = null
    private var ownerLock: java.nio.channels.FileLock? = null
    private var root: Path? = null
    private var shutdown: Job? = null

    override suspend fun prepare(publication: Publication, loader: ResourceLoader): PageDocument {
        val resources = publication.resources.filter { it.format == PublicationFormat.CBZ }
        if (resources.isEmpty()) return fallback.prepare(publication, loader)
        require(publication.type == PublicationType.COMIC && resources.size == 1 && publication.resources.size == 1)
        val resource = resources.single()
        require(resource.publicationId == publication.id && resource.mediaType == CBZ_ARCHIVE_MIME && resource.key.length <= 512)
        val job = currentCoroutineContext().job
        jobs.add(job)
        var payload: Path? = null
        var document: CbzDocument? = null
        try {
            val result = withContext(io) {
                withTimeout(60_000) {
                    mutex.withLock {
                        check(!closed.get())
                        if (documents.size >= CBZ_MAX_DOCUMENTS) zipLimit()
                        currentCoroutineContext().ensureActive()
                        val content = loader.load(resource)
                        try {
                            val base = initialize()
                            val part = base.resolve("cbz-${UUID.randomUUID()}.part")
                            payload = part
                            Files.createFile(part)
                            stream(content, part)
                            val structural = inspectBoundedZip(part, CBZ_LIMITS)
                            if (structural.isEmpty()) zipRequire(false)
                            val published = part.resolveSibling(part.fileName.toString().removeSuffix(".part") + ".zip")
                            try { Files.move(part, published, StandardCopyOption.ATOMIC_MOVE) }
                            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(part, published) }
                            payload = published
                            val zip = ZipFile(published.toFile(), Charsets.UTF_8)
                            try {
                                verifyBoundedZipEntries(zip, structural, CBZ_LIMITS)
                                val files = structural.filterNot { it.directory }
                                if (files.isEmpty()) zipRequire(false)
                                if (files.size > PagePolicy.MAX_PAGES) zipLimit()
                                val ordered = files.sortedWith(Comparator { a, b -> compareNaturalPath(a.path, b.path) })
                                val preparedPages = ordered.mapIndexed { index, entry ->
                                    currentCoroutineContext().ensureActive()
                                    val mediaType = mediaTypeFor(entry.path) ?: throw CbzPreparationFailure(ZipFailure.INVALID)
                                    if (entry.size !in 1..RasterPolicy.ENCODED_BYTES.toLong()) zipLimit()
                                    val bytes = readBoundedEntry(zip, entry, RasterPolicy.ENCODED_BYTES)
                                    val dimensions = try { inspectRaster(bytes, mediaType) }
                                    catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { throw CbzPreparationFailure(ZipFailure.INVALID) }
                                    val pageResource = PublicationResource(publication.id,
                                        "cbz-page-${index.toString().padStart(4, '0')}", PublicationFormat.PAGES,
                                        mediaType, pageDimensions = PageDimensions(dimensions.first, dimensions.second))
                                    pageResource to entry
                                }
                                val doc = CbzDocument(publication.id, publication.title, published, zip, preparedPages, io) { closedDoc ->
                                    cleanupScope.launch {
                                        try { closedDoc.release(); safeDelete(closedDoc.file) }
                                        finally { documents.remove(closedDoc) }
                                    }
                                }
                                documents.add(doc); document = doc; payload = null
                                if (closed.get()) { doc.close(); throw CancellationException("CBZ owner closed") }
                                doc
                            } catch (error: Throwable) { zip.close(); throw error }
                        } finally {
                            try { content.close() } finally { payload?.let(::safeDelete); payload = null }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return result
        } catch (error: Throwable) {
            document?.close()
            when (error) {
                is CancellationException -> throw error
                is CbzPreparationFailure -> throw error
                is BoundedZipException -> throw CbzPreparationFailure(error.failure)
                is java.io.IOException -> throw CbzPreparationFailure(ZipFailure.TRANSFER)
                else -> throw CbzPreparationFailure(ZipFailure.INVALID)
            }
        } finally { jobs.remove(job) }
    }

    private suspend fun stream(content: ResourceContent, path: Path) {
        val declared = content.sizeBytes
        if (declared != null && (declared <= 0 || declared > CBZ_LIMITS.archiveBytes)) zipLimit()
        val buffer = ByteArray(CBZ_BUFFER_BYTES)
        var total = 0L
        Files.newOutputStream(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
            while (true) {
                currentCoroutineContext().ensureActive(); check(!closed.get())
                val max = declared ?: CBZ_LIMITS.archiveBytes
                val length = minOf(buffer.size.toLong(), max - total + 1).toInt()
                val count = try { content.read(buffer, 0, length) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { throw CbzPreparationFailure(ZipFailure.TRANSFER) }
                if (count == -1) break
                if (count !in 1..length) throw CbzPreparationFailure(ZipFailure.TRANSFER)
                total += count
                if (total > CBZ_LIMITS.archiveBytes) zipLimit()
                if (declared != null && total > declared) throw CbzPreparationFailure(ZipFailure.TRANSFER)
                output.write(buffer, 0, count)
            }
        }
        if (total == 0L || declared != null && total != declared) throw CbzPreparationFailure(ZipFailure.TRANSFER)
    }

    private fun initialize(): Path {
        root?.let { return it }
        val base = directory?.takeIf { it.isAbsolute }?.normalize() ?: throw CbzPreparationFailure(ZipFailure.TRANSFER)
        if (Files.isSymbolicLink(base)) throw CbzPreparationFailure(ZipFailure.TRANSFER)
        Files.createDirectories(base)
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) throw CbzPreparationFailure(ZipFailure.TRANSFER)
        try { Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------")) }
        catch (_: UnsupportedOperationException) { }
        val canonical = base.toRealPath()
        cleanupStale(canonical)
        root = canonical
        return canonical
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobs.forEach { it.cancel() }
        documents.forEach { it.close() }
        shutdown = cleanupScope.launch {
            mutex.withLock {
                documents.forEach { doc -> doc.close(); doc.release(); safeDelete(doc.file); documents.remove(doc) }
                try { ownerLock?.release() } catch (_: Exception) { }
                try { ownerFile?.close() } catch (_: Exception) { }
                ownerLock = null; ownerFile = null
                root?.let { base ->
                    try { Files.newDirectoryStream(base).use { files -> for (file in files) if (CBZ_FILE_NAME.matches(file.fileName.toString()) || CBZ_PART_NAME.matches(file.fileName.toString())) safeDelete(file) } }
                    catch (_: Exception) { }
                }
                root = null
            }
        }
        shutdown?.invokeOnCompletion { cleanupScope.cancel() }
    }

    override suspend fun awaitClosed() { shutdown?.join() }

    private fun cleanupStale(base: Path) {
        // Only clean our namespace while exclusive owner lock is available.
        val owner = base.resolve(CBZ_OWNER_NAME)
        if (Files.isSymbolicLink(owner)) throw CbzPreparationFailure(ZipFailure.TRANSFER)
        val handle = FileChannel.open(owner, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        try {
            val lock = handle.tryLock() ?: throw CbzPreparationFailure(ZipFailure.TRANSFER)
            ownerFile = handle; ownerLock = lock
        } catch (error: Throwable) { handle.close(); throw error }
        try {
            Files.newDirectoryStream(base).use { items ->
                var seen = 0
                for (path in items) {
                    if (++seen > 128) break
                    val name = path.fileName.toString()
                    if (CBZ_FILE_NAME.matches(name) || CBZ_PART_NAME.matches(name)) safeDelete(path)
                }
            }
        } catch (_: Exception) { }
        // The lock descriptor remains held for the preparer's lifetime.
    }
}

private class CbzDocument(
    override val publicationId: PublicationId,
    override val title: String,
    internal val file: Path,
    private val zip: ZipFile,
    private val prepared: List<Pair<PublicationResource, BoundedZipEntry>>,
    private val io: CoroutineDispatcher,
    private val dispose: (CbzDocument) -> Unit,
) : PageDocument {
    override val progressId = ReadingProgressId(publicationId, "page-sequence", PublicationFormat.PAGES)
    override val pages: List<PageEntry> = prepared.map { PageEntry(it.first) }
    private val lock = Any()
    private var closed = false
    private var released = false
    private val releaseLock = Any()
    private val opening = mutableSetOf<Job>()
    private val handles = mutableSetOf<PageHandle>()

    override suspend fun openPage(page: PageEntry): ResourceContent {
        require(page.resource.publicationId == publicationId && page in pages && PagePolicy.supported(page))
        val job = currentCoroutineContext().job
        synchronized(lock) { check(!closed); if (handles.size + opening.size >= CBZ_MAX_HANDLES) zipLimit(); opening.add(job) }
        var handle: PageHandle? = null
        var content: CbzPageHandle? = null
        var transferred = false
        try {
            val pair = prepared[pages.indexOf(page)]
            val entry = zip.getEntry(pair.second.name) ?: throw CbzPreparationFailure(ZipFailure.INVALID)
            content = CbzPageHandle(zip.getInputStream(entry), entry.size, entry.crc, io)
            return synchronized(lock) {
                check(!closed)
                PageHandle(checkNotNull(content)) { handles.remove(it) }.also { handles.add(it); handle = it; transferred = true }
            }
        } finally {
            synchronized(lock) { opening.remove(job) }
            if (!transferred) content?.close()
        }
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            opening.toList() to handles.toList()
        }
        owned.first.forEach { it.cancel() }; owned.second.forEach { try { it.close() } catch (_: Exception) { } }
        dispose(this)
    }

    fun release() = synchronized(releaseLock) {
        if (!released) { try { zip.close() } catch (_: Exception) { } finally { released = true } }
    }

    private class PageHandle(private val content: ResourceContent, private val dispose: (PageHandle) -> Unit) : ResourceContent {
        private val done = AtomicBoolean(false)
        override val sizeBytes get() = content.sizeBytes
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int { check(!done.get()); return content.read(buffer, offset, length) }
        override fun close() { if (done.compareAndSet(false, true)) { dispose(this); content.close() } }
    }
}

private class CbzPageHandle(
    private val input: java.io.InputStream,
    override val sizeBytes: Long,
    private val expectedCrc: Long,
    private val io: CoroutineDispatcher,
) : ResourceContent {
    private val closed = AtomicBoolean()
    private val crc = CRC32()
    private var count = 0L
    private var ended = false
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset in 0..buffer.size && length in 0..buffer.size - offset)
        check(!closed.get())
        if (length == 0) return 0
        try {
            return withContext(io) {
                currentCoroutineContext().ensureActive(); check(!closed.get())
                if (ended) return@withContext -1
                val n = input.read(buffer, offset, minOf(length.toLong(), CBZ_BUFFER_BYTES.toLong(), sizeBytes - count + 1).toInt())
                if (n == -1) { zipRequire(count == sizeBytes && crc.value == expectedCrc); ended = true }
                else { zipRequire(n > 0); count += n; zipRequire(count <= sizeBytes); crc.update(buffer, offset, n) }
                n
            }
        } catch (error: Throwable) { close(); throw error }
    }
    override fun close() { if (closed.compareAndSet(false, true)) input.close() }
}

/** Natural comparison across the complete relative path: ASCII case-folded, digit runs by
 * numeric value, then fewer leading zeroes, then original ordinal path as a stable tie-break. */
internal fun compareNaturalPath(left: String, right: String): Int {
    var a = 0; var b = 0
    while (a < left.length && b < right.length) {
        val ca = left[a]; val cb = right[b]
        val da = ca in '0'..'9'; val db = cb in '0'..'9'
        if (da && db) {
            var ae = a; while (ae < left.length && left[ae] in '0'..'9') ae++
            var be = b; while (be < right.length && right[be] in '0'..'9') be++
            var asig = a; while (asig < ae - 1 && left[asig] == '0') asig++
            var bsig = b; while (bsig < be - 1 && right[bsig] == '0') bsig++
            val al = ae - asig; val bl = be - bsig
            if (al != bl) return al.compareTo(bl)
            for (i in 0 until al) if (left[asig + i] != right[bsig + i]) return left[asig + i].compareTo(right[bsig + i])
            val az = asig - a; val bz = bsig - b
            if (az != bz) return az.compareTo(bz)
            a = ae; b = be
        } else {
            val fa = if (ca in 'A'..'Z') ca + ('a' - 'A') else ca
            val fb = if (cb in 'A'..'Z') cb + ('a' - 'A') else cb
            if (fa != fb) return fa.compareTo(fb)
            a++; b++
        }
    }
    if (a != left.length || b != right.length) return (left.length - a).compareTo(right.length - b)
    return left.compareTo(right)
}

private fun mediaTypeFor(path: String): String? = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    else -> null
}

private suspend fun readBoundedEntry(zip: ZipFile, entry: BoundedZipEntry, maxBytes: Int): ByteArray {
    if (entry.size !in 1..maxBytes.toLong()) zipLimit()
    val bytes = ByteArray(entry.size.toInt()); var position = 0
    zip.getInputStream(zip.getEntry(entry.name) ?: throw CbzPreparationFailure(ZipFailure.INVALID)).use { input ->
        while (position < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = input.read(bytes, position, minOf(CBZ_BUFFER_BYTES, bytes.size - position))
            if (count <= 0) throw CbzPreparationFailure(ZipFailure.INVALID)
            position += count
        }
        if (input.read() != -1) throw CbzPreparationFailure(ZipFailure.INVALID)
    }
    return bytes
}

private fun safeDelete(path: Path) {
    try { if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path) } catch (_: Exception) { }
}
