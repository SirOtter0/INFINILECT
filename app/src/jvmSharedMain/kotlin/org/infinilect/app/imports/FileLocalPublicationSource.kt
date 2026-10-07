// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import java.io.*
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.reader.*
import org.infinilect.app.reader.page.PagePreparer
import org.infinilect.core.*

internal const val IMPORT_DIRECTORY_NAME = "local-imports-v1"
internal const val MAX_LOCAL_IMPORT_BYTES = 32L * 1024 * 1024
internal val LOCAL_SOURCE_ID = SourceId("local-imports")
private const val BUFFER_BYTES = 8192
private const val MAX_RECORD_BYTES = 16384
private val DIGEST_NAME = Regex("[0-9a-f]{64}")
private val PART_NAME = Regex("import-[0-9a-f-]{36}\\.part")
private val ENTRY_NAME = Regex("[0-9a-f]{64}\\.import")

/** One application owner, serialized imports/catalog operations, at most eight payload handles.
 * The committed unit is a directory containing immutable payload + bounded checksummed metadata.
 * Only SHA-256/UUID-generated names reach storage. No external location is retained. */
internal class FileLocalPublicationSource(
    private val directory: Path?,
    private val text: TextPreparer,
    private val epub: EpubPreparer,
    private val pages: PagePreparer,
    private val maxBytes: Long = MAX_LOCAL_IMPORT_BYTES,
    private val maxEntries: Int = 256,
    private val maxTotalBytes: Long = 512L * 1024 * 1024,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pdf: org.infinilect.app.reader.pdf.PdfPreparer? = null,
) : PublicationSource, LocalPublicationImporter {
    override val id = LOCAL_SOURCE_ID
    private val closed = AtomicBoolean()
    private val mutex = Mutex()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    private val contents = ConcurrentHashMap.newKeySet<ResourceContent>()
    private val cleanup = CoroutineScope(SupervisorJob() + io)
    private var root: Path? = null
    private var owner: FileChannel? = null
    private var lock: FileLock? = null
    private var shutdown: Job? = null
    init { require(maxBytes in 1..MAX_LOCAL_IMPORT_BYTES && maxEntries in 1..256 && maxTotalBytes in 1..512L*1024*1024) }

    override suspend fun import(selection: LocalFileSelection): Publication = operation(timed = false) {
        var content: ResourceContent? = null
        var partial: Path? = null
        try {
            val base = initialize()
            val (total,key) = withTimeout(120_000) {
                val opened = try { selection.open().also { content = it } } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { fail(ImportFailure.TRANSFER) }
                contents.add(opened)
                checkOpen()
                val staging = Files.createDirectory(base.resolve("import-${UUID.randomUUID()}.part"))
                partial = staging
                val payload = staging.resolve("payload")
                val digest = MessageDigest.getInstance("SHA-256")
                val declared = opened.sizeBytes
                if (declared != null && (declared <= 0 || declared > maxBytes)) fail(if (declared > maxBytes) ImportFailure.LIMIT else ImportFailure.TRANSFER)
                var total = 0L
                Files.newOutputStream(payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        checkOpen()
                        val requested = minOf(buffer.size.toLong(), (declared ?: maxBytes) - total + 1).toInt()
                        val n = try { opened.read(buffer, 0, requested) } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { fail(ImportFailure.TRANSFER) }
                        if (n == -1) break
                        if (n !in 1..requested) fail(ImportFailure.TRANSFER)
                        total += n
                        if (total > maxBytes) fail(ImportFailure.LIMIT)
                        if (declared != null && total > declared) fail(ImportFailure.TRANSFER)
                        digest.update(buffer, 0, n); out.write(buffer, 0, n)
                    }
                }
                if (total == 0L || declared != null && total != declared) fail(ImportFailure.TRANSFER)
                opened.close(); contents.remove(opened); content = null
                total to digest.digest().hex()
            }
            val staging = checkNotNull(partial)
            val payload = staging.resolve("payload")
            val target = base.resolve("$key.import")
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                val previous = readRecord(target, key) ?: fail(ImportFailure.STORAGE)
                // Never claim deduplication from metadata alone if owned bytes were corrupted.
                if (hashPayload(target.resolve("payload"), total) != key) fail(ImportFailure.STORAGE)
                return@operation previous.publication
            }
            val committed = entries(base)
            val used = committed.sumOf { readRecord(it, it.fileName.toString().removeSuffix(".import"))?.size ?: maxTotalBytes }
            if (committed.size >= maxEntries || total > maxTotalBytes - used) fail(ImportFailure.LIMIT)
            val publication = validate(payload, key, fallbackTitle(selection.displayName))
            val record = Record(publication, total)
            val metadata = staging.resolve("record")
            Files.write(metadata, encode(record), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            forceFile(payload); forceFile(metadata)
            checkOpen()
            // Same-filesystem rename publishes the complete directory; no existing entry is replaced.
            try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(staging, target) }
            partial = null
            forceDirectory(base)
            publication
        } finally {
            content?.let { contents.remove(it); try { it.close() } catch (_: Exception) { } }
            partial?.let(::deletePartial)
        }
    }

    override suspend fun search(query: String, pageToken: String?): SearchPage = operation {
        require(query.isNotBlank() && query.length <= 512 && pageToken == null)
        SearchPage(entries(initialize()).mapNotNull { readRecord(it, it.fileName.toString().removeSuffix(".import"))?.publication }
            .filter { query == "*" || it.title.contains(query, ignoreCase = true) || it.authors.any { a -> a.contains(query, ignoreCase = true) } }
            .sortedWith(compareBy<Publication> { it.title }.thenBy { it.id.localId }))
    }

    override suspend fun getPublication(publicationId: PublicationId): Publication? = operation {
        require(publicationId.sourceId == id && DIGEST_NAME.matches(publicationId.localId))
        readRecord(initialize().resolve("${publicationId.localId}.import"), publicationId.localId)?.publication
    }

    override suspend fun loadResource(resource: PublicationResource): ResourceContent {
        var acquired: ResourceContent? = null
        try { return operation {
        require(resource.publicationId.sourceId == id && DIGEST_NAME.matches(resource.publicationId.localId))
        val key = resource.publicationId.localId
        val entry = initialize().resolve("$key.import")
        val record = readRecord(entry, key) ?: fail(ImportFailure.STORAGE)
        require(record.publication.resources.single() == resource)
        if (contents.size >= 8) fail(ImportFailure.LIMIT)
        val handle = payloadContent(entry.resolve("payload"), record.size, key) { contents.remove(it) }
        acquired = handle
        contents.add(handle)
        if (closed.get()) { handle.close(); throw CancellationException() }
        handle
        } } catch(e: Throwable) {
            // withContext may discard the result when cancellation wins its return dispatch.
            // Ownership transfers only when this method actually returns to its caller.
            try { acquired?.close() } catch (_: Exception) { }
            throw e
        }
    }

    private suspend fun validate(path: Path, key: String, title: String): Publication {
        val identity = PublicationId(id, key)
        fun candidate(format: PublicationFormat) = Publication(identity, title,
            if (format == PublicationFormat.CBZ) PublicationType.COMIC else if (format == PublicationFormat.EPUB) PublicationType.BOOK else PublicationType.DOCUMENT,
            resources = listOf(PublicationResource(identity, "content", format, when (format) {
                PublicationFormat.EPUB -> "application/epub+zip"; PublicationFormat.CBZ -> "application/vnd.comicbook+zip"; PublicationFormat.PDF -> "application/pdf"; else -> "text/plain"
            }, revision = key)))
        val loader = object : ResourceLoader { override suspend fun load(resource: PublicationResource): ResourceContent =
            payloadContent(path, Files.size(path), key) }
        val prefix = ByteArray(8)
        val n = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.read(prefix) }
        if (n >= 2 && prefix[0] == 80.toByte() && prefix[1] == 75.toByte()) {
            val book = candidate(PublicationFormat.EPUB)
            try {
                val doc = epub.prepare(book, book.resources.single(), loader)
                try {
                    val meta = doc.metadata
                    return book.copy(title = safeDisplay(meta.title, 512).ifBlank { title },
                        authors = meta.creators.take(64).map { safeDisplay(it, 512) }.filter { it.isNotBlank() },
                        languages = meta.languages.take(32).map { safeDisplay(it, 128) }.filter { it.isNotBlank() })
                } finally { doc.close() }
            } catch (e: CancellationException) { throw e }
            catch (e: EpubException) { if (e.failure == EpubFailure.STORAGE || e.failure == EpubFailure.TRANSFER) fail(ImportFailure.STORAGE) }
            val comic = candidate(PublicationFormat.CBZ)
            try { pages.prepare(comic, loader).close(); return comic }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail(ImportFailure.UNSUPPORTED) }
        }
        if (n >= 4 && prefix.copyOf(4).contentEquals(byteArrayOf(37,80,68,70))) {
            val preparer = pdf ?: fail(ImportFailure.UNSUPPORTED)
            val publication = candidate(PublicationFormat.PDF)
            try {
                preparer.prepare(publication,publication.resources.single(),loader).close()
                checkOpen()
                return publication
            } catch (e: CancellationException) { throw e }
            catch (e: PdfException) { fail(when (e.failure) {
                PdfFailure.LIMIT, PdfFailure.GEOMETRY -> ImportFailure.LIMIT
                PdfFailure.ENCRYPTED -> ImportFailure.PDF_ENCRYPTED
                PdfFailure.STORAGE, PdfFailure.CLOSED -> ImportFailure.STORAGE
                PdfFailure.TRANSFER -> ImportFailure.TRANSFER
                else -> ImportFailure.UNSUPPORTED
            }) }
        }
        val publication = candidate(PublicationFormat.TEXT)
        try {
            val doc = text.prepare(publication, publication.resources.single(), loader, io)
            try {
                // Valid UTF-8 alone does not make binary/control-heavy data plain text.
                for (i in 0 until doc.windowCount) {
                    checkOpen()
                    if (doc.window(i).text.any { (it.code in 0..31 && it !in "\t\n\r\u000c") || it.code in 127..159 }) fail(ImportFailure.UNSUPPORTED)
                }
            } finally { doc.close() }
            return publication
        } catch (e: CancellationException) { throw e }
        catch (e: LocalImportException) { throw e }
        catch (e: TextDocumentException) { fail(if (e.failure == TextFailure.TOO_LARGE) ImportFailure.LIMIT else if (e.failure == TextFailure.STORAGE) ImportFailure.STORAGE else ImportFailure.UNSUPPORTED) }
    }

    private suspend fun <T> operation(timed: Boolean = true, action: suspend () -> T): T {
        val job = currentCoroutineContext().job; jobs.add(job)
        try { return withContext(io) {
            if (timed) withTimeout(120_000) { mutex.withLock { checkOpen(); action() } }
            else mutex.withLock { checkOpen(); action() }
        } }
        catch (e: CancellationException) { throw e }
        catch (e: LocalImportException) { throw e }
        catch (e: IllegalArgumentException) { throw e }
        catch (_: Exception) { fail(ImportFailure.STORAGE) }
        finally { jobs.remove(job) }
    }

    private fun initialize(): Path {
        root?.let { if (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)) return it; fail(ImportFailure.STORAGE) }
        val base = directory?.takeIf { it.isAbsolute }?.normalize() ?: fail(ImportFailure.STORAGE)
        if (Files.isSymbolicLink(base)) fail(ImportFailure.STORAGE)
        Files.createDirectories(base)
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) fail(ImportFailure.STORAGE)
        Files.getFileAttributeView(base, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.setPermissions(PosixFilePermissions.fromString("rwx------"))
        val canonical = base.toRealPath()
        val ownerPath = canonical.resolve(".owner.lock")
        if (Files.isSymbolicLink(ownerPath)) fail(ImportFailure.STORAGE)
        val channel = FileChannel.open(ownerPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val acquired = try { channel.tryLock() ?: fail(ImportFailure.STORAGE) } catch (e: Exception) { channel.close(); throw e }
        owner = channel; lock = acquired; root = canonical
        Files.newDirectoryStream(canonical).use { paths ->
            var seen = 0
            for (p in paths) { if(++seen > 2048) fail(ImportFailure.LIMIT); if (PART_NAME.matches(p.fileName.toString())) deletePartial(p) }
        }
        return canonical
    }

    private fun entries(base: Path): List<Path> = Files.newDirectoryStream(base).use { paths ->
        val result = mutableListOf<Path>(); var count = 0
        for (p in paths) {
            if (++count > 2048) fail(ImportFailure.LIMIT)
            if (ENTRY_NAME.matches(p.fileName.toString())) { result.add(p); if (result.size > maxEntries) fail(ImportFailure.LIMIT) }
        }
        result
    }

    private data class Record(val publication: Publication, val size: Long)
    private fun encode(record: Record): ByteArray {
        PublicationSnapshot.from(record.publication).validate()
        val body = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(1); out.writeUTF(record.publication.id.localId); out.writeLong(record.size)
                out.writeUTF(record.publication.resources.single().format.name); out.writeUTF(record.publication.title)
                out.writeInt(record.publication.authors.size); record.publication.authors.forEach(out::writeUTF)
                out.writeInt(record.publication.languages.size); record.publication.languages.forEach(out::writeUTF)
            }; bytes.toByteArray()
        }
        if (body.size + 32 > MAX_RECORD_BYTES) fail(ImportFailure.LIMIT)
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    private fun readRecord(entry: Path, key: String): Record? { return try {
        if (!DIGEST_NAME.matches(key) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) return null
        val metadata = entry.resolve("record"); val payload = entry.resolve("payload")
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) !in 33..MAX_RECORD_BYTES.toLong() ||
            !Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = Files.newInputStream(metadata, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(MAX_RECORD_BYTES + 1); var count = 0
            while (count < buffer.size) { val n = input.read(buffer,count,buffer.size-count); if(n == -1) break; if(n <= 0) return null; count += n }
            if(count !in 33..MAX_RECORD_BYTES) return null
            buffer.copyOf(count)
        }
        val body = bytes.copyOf(bytes.size - 32)
        if (!MessageDigest.getInstance("SHA-256").digest(body).contentEquals(bytes.copyOfRange(body.size, bytes.size))) return null
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            if (input.readInt() != 1 || input.readUTF() != key) return null
            val size = input.readLong(); if (size !in 1..maxBytes || Files.size(payload) != size) return null
            val format = PublicationFormat.valueOf(input.readUTF())
            if (format !in listOf(PublicationFormat.TEXT, PublicationFormat.EPUB, PublicationFormat.CBZ, PublicationFormat.PDF)) return null
            val title = input.readUTF()
            fun list(max: Int): List<String> { val count = input.readInt(); require(count in 0..max); return List(count) { input.readUTF() } }
            val authors = list(64); val languages = list(32); if (input.read() != -1) return null
            val identity = PublicationId(id, key)
            val resource = PublicationResource(identity, "content", format, when(format) { PublicationFormat.EPUB -> "application/epub+zip"; PublicationFormat.CBZ -> "application/vnd.comicbook+zip"; PublicationFormat.PDF -> "application/pdf"; else -> "text/plain" }, key)
            val pub = Publication(identity, title, if (format == PublicationFormat.CBZ) PublicationType.COMIC else if (format == PublicationFormat.EPUB) PublicationType.BOOK else PublicationType.DOCUMENT,
                authors, listOf(resource), languages)
            PublicationSnapshot.from(pub).validate(); Record(pub,size)
        }
    } catch (_: Exception) { null } }

    private suspend fun hashPayload(path: Path, expected: Long): String {
        val content = payloadContent(path, expected, null)
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(BUFFER_BYTES)
        try { while (true) { val n = content.read(buffer); if (n == -1) break; digest.update(buffer,0,n) } }
        finally { content.close() }
        return digest.digest().hex()
    }

    private fun payloadContent(path: Path, expected: Long, digest: String?, dispose: (ResourceContent) -> Unit = {}): ResourceContent {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != expected) fail(ImportFailure.STORAGE)
        val input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)
        return object : ResourceContent {
            private val done = AtomicBoolean(); private val hash = MessageDigest.getInstance("SHA-256")
            private var read = 0L; private var ended = false
            override val sizeBytes = expected
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                require(offset in 0..buffer.size && length in 0..buffer.size-offset); check(!done.get())
                if (length == 0) return 0
                try { return withContext(io) {
                    currentCoroutineContext().ensureActive(); check(!done.get())
                    if (ended) return@withContext -1
                    val n = input.read(buffer,offset,minOf(length.toLong(),BUFFER_BYTES.toLong(),expected-read+1).toInt())
                    if (n == -1) { if (read != expected || digest != null && hash.digest().hex() != digest) fail(ImportFailure.TRANSFER); ended = true }
                    else { if (n <= 0 || read+n > expected) fail(ImportFailure.TRANSFER); read += n; hash.update(buffer,offset,n) }
                    n
                } } catch (e: Throwable) { close(); throw e }
            }
            override fun close() { if(done.compareAndSet(false,true)) { try { input.close() } finally { dispose(this) } } }
        }
    }

    private fun forceFile(path: Path) { FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { it.force(true) } }
    private fun forceDirectory(path: Path) { try { FileChannel.open(path,StandardOpenOption.READ).use { it.force(true) } } catch (_: Exception) { /* Some platform providers cannot fsync directories. */ } }
    private fun deletePartial(path: Path) {
        try {
            if (!PART_NAME.matches(path.fileName.toString()) || !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) return
            for (name in listOf("payload","record")) Files.deleteIfExists(path.resolve(name))
            Files.deleteIfExists(path)
        } catch (_: Exception) { }
    }
    private suspend fun checkOpen() { currentCoroutineContext().ensureActive(); if(closed.get()) throw CancellationException("Import owner closed") }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        jobs.forEach { it.cancel() }; contents.forEach { try { it.close() } catch (_: Exception) { } }
        shutdown = cleanup.launch { mutex.withLock { try { lock?.release() } finally { owner?.close(); lock=null;owner=null;root=null } } }
        shutdown?.invokeOnCompletion { cleanup.cancel() }
    }
    override suspend fun awaitClosed() { shutdown?.join() }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun fail(failure: ImportFailure): Nothing = throw LocalImportException(failure)
internal fun fallbackTitle(name: String?): String {
    val leaf = name.orEmpty().take(4096).substringAfterLast('/').substringAfterLast('\\')
    return safeDisplay(leaf.substringBeforeLast('.', leaf), 256).ifBlank { "Imported publication" }
}
private fun safeDisplay(value: String, max: Int): String {
    val clean = value.filterNot { it.isISOControl() }.trim()
    val end = minOf(clean.length,max)
    return clean.substring(0,if(end>0 && clean[end-1].isHighSurrogate()) end-1 else end)
}
