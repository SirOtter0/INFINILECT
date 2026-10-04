// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.reader.*
import org.infinilect.core.*
internal fun desktopEpubDirectory(os: String = (System.getProperty("os.name", "") ?: ""), home: String?=System.getProperty("user.home"), environment: Map<String, String>  = System.getenv()): Path?  = desktopTextDirectory(os, home, environment)?.parent?.resolve(EPUB_DIRECTORY_NAME)
internal fun androidEpubDirectory(cacheDir: Path): Path?  = cacheDir.takeIf {
    it.isAbsolute
}
?.resolve(EPUB_DIRECTORY_NAME)

/** Lazy private session storage, two prepared documents, one serialized preparation at a time.
* No ZIP entry is ever extracted to a filesystem path. All work/cleanup runs on IO.
*/

internal class FileEpubPreparer(private val directory: Path?, private val limits: EpubLimits = EpubLimits(), private val dispatcher: CoroutineDispatcher = Dispatchers.IO): EpubPreparer {
    private val closed = AtomicBoolean()
    private val mutex = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob()+dispatcher)
    private val documents = ConcurrentHashMap.newKeySet<PreparedEpub>()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    private val contents = ConcurrentHashMap.newKeySet<ResourceContent>()
    private var root: Path?=null
    private var ownerFile: RandomAccessFile?=null
    private var lock: FileLock?=null
    private var shutdown: Job?=null
    override suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader): EpubDocument {
        require(resource.format==PublicationFormat.EPUB && resource.mediaType=="application/epub+zip" && resource in publication.resources)
        var prepared: PreparedEpub?=null
        try {
            val result = withContext(dispatcher) {
                withTimeout(60_000) {
                    val job = currentCoroutineContext().job
                    jobs.add(job)
                    try {
                        mutex.withLock {
                            check(!closed.get())
                            if (documents.size>=2) limit()
                            currentCoroutineContext().ensureActive()
                            val content = loader.load(resource)
                            contents.add(content)
                            var file: Path?=null
                            var zip: ZipFile?=null
                            try {
                                currentCoroutineContext().ensureActive()
                                check(!closed.get())
                                val declared = content.sizeBytes
                                if (declared!=null && (declared<0 || declared>limits.archiveBytes)) {
                                    if (declared<0) throw EpubException(EpubFailure.TRANSFER) else limit()
                                }
                                file = Files.createFile(initialize().resolve("epub-${UUID.randomUUID()}.zip"))
                                stream(content, file, declared)
                                val entries = inspectEpubZip(file, limits)
                                zip = ZipFile(file.toFile(), Charsets.UTF_8)
                                verifyEpubEntries(zip, entries, limits)
                                val pkg = readEpubPackage(zip, entries, limits, currentCoroutineContext().job)
                                currentCoroutineContext().ensureActive()
                                check(!closed.get())
                                val value = PreparedEpub(publication.id, pkg, zip, dispatcher) {
                                    doc ->
                                    cleanup.launch {
                                        try {
                                            doc.release()
                                            deleteFile(doc.file)
                                        }
                                        finally {
                                            documents.remove(doc)
                                        }
                                    }
                                }
                                .also {
                                    it.file = checkNotNull(file)
                                }
                                documents.add(value)
                                prepared = value
                                zip = null
                                file = null
                                if (closed.get()) {
                                    value.close()
                                    throw CancellationException("EPUB owner closed")
                                }
                                value
                            }
                            finally {
                                contents.remove(content)
                                try {
                                    content.close()
                                }
                                finally {
                                    try {
                                        zip?.close()
                                    }
                                    finally {
                                        file?.let(::deleteFile)
                                    }
                                }
                            }
                        }
                    }
                    finally {
                        jobs.remove(job)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return result
        }
        catch (error: Throwable) {
            prepared?.close()
            when (error) {
                is CancellationException -> throw error
                is EpubException -> throw error
                is java.util.zip.ZipException, is java.io.EOFException -> throw EpubException(EpubFailure.INVALID, error)
                is java.io.IOException -> throw EpubException(EpubFailure.STORAGE, error)
                is SecurityException -> throw EpubException(EpubFailure.STORAGE, error)
                else -> throw EpubException(EpubFailure.INVALID, error)
            }
        }
    }
    private suspend fun stream(content: ResourceContent, file: Path, declared: Long?) {
        val buffer = ByteArray(EPUB_BUFFER_BYTES)
        var total = 0L
        Files.newOutputStream(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
            output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                check(!closed.get())
                val max = declared ?: limits.archiveBytes
                val length = minOf(buffer.size.toLong(), max-total+1).toInt()
                val n = try {
                    content.read(buffer, 0, length)
                }
                catch (error: CancellationException) {
                    throw error
                }
                catch (error: Exception) {
                    throw EpubException(EpubFailure.TRANSFER, error)
                }
                if (n==-1) break
                if (n !in 1..length) throw EpubException(EpubFailure.TRANSFER)
                total+=n
                if (total>limits.archiveBytes) limit()
                if (declared!=null && total>declared) throw EpubException(EpubFailure.TRANSFER)
                output.write(buffer, 0, n)
            }
        }
        if (total==0L || declared!=null && total!=declared) throw EpubException(EpubFailure.TRANSFER)
    }
    private fun initialize(): Path {
        root?.let {
            requireEpub(Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS))
            return it
        }
        val base = directory?.takeIf {
            it.isAbsolute
        }
        ?.normalize() ?: throw EpubException(EpubFailure.STORAGE)
        if (Files.isSymbolicLink(base)) throw EpubException(EpubFailure.STORAGE)
        Files.createDirectories(base)
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) throw EpubException(EpubFailure.STORAGE)
        Files.getFileAttributeView(base, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?.setPermissions(PosixFilePermissions.fromString("rwx------"))
        val canonical = base.toRealPath()
        cleanupStale(canonical)
        val session = Files.createDirectory(canonical.resolve("session-${UUID.randomUUID()}"))
        try {
            val handle = RandomAccessFile(session.resolve(".owner.lock").toFile(), "rw")
            ownerFile = handle
            lock = handle.channel.tryLock() ?: throw EpubException(EpubFailure.STORAGE)
            root = session
            return session
        }
        catch (error: Throwable) {
            ownerFile?.close()
            ownerFile = null
            deleteSession(session)
            throw error
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobs.toList().forEach {
            it.cancel()
        }
        contents.toList().forEach {
            try {
                it.close()
            }
            catch (_: Exception) {
            }
        }
        documents.toList().forEach {
            it.close()
        }
        shutdown = cleanup.launch {
            mutex.withLock {
                documents.toList().forEach {
                    doc ->
                    doc.close()
                    doc.release()
                    deleteFile(doc.file)
                    documents.remove(doc)
                }
                try {
                    lock?.release()
                }
                catch (_: Exception) {
                }
                try {
                    ownerFile?.close()
                }
                catch (_: Exception) {
                }
                lock = null
                ownerFile = null
                root?.let(::deleteSession)
                root = null
            }
        }
        shutdown!!.invokeOnCompletion {
            cleanup.cancel()
        }
    }
    override suspend fun awaitClosed() {
        shutdown?.join()
    }
}

private class PreparedEpub(override val publicationId: PublicationId, pkg: EpubPackage, private val zip: ZipFile, private val dispatcher: CoroutineDispatcher, private val dispose: (PreparedEpub)->Unit): EpubDocument {
    lateinit var file: Path
    override val packagePath = pkg.path
    override val metadata = pkg.metadata
    override val manifest = pkg.manifest
    override val spine = pkg.spine
    override val navigationItemId = pkg.navigation
    private val allowed = manifest.map {
        it.path
    }
    .toSet()
    private val closed = AtomicBoolean()
    private var released = false
    private val releaseLock = Any()
    private val mutex = Mutex()
    private val handles = ConcurrentHashMap.newKeySet<EntryContent>()
    override suspend fun openResource(path: EpubEntryPath): ResourceContent {
        var opened: EntryContent?=null
        try {
            return withContext(dispatcher) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    check(!closed.get())
                    require(path in allowed)
                    if (handles.size>=8) limit()
                    val entry = zip.getEntry(path.value) ?: invalid()
                    val handle = EntryContent(zip.getInputStream(entry), entry.size, entry.crc, dispatcher) {
                        handles.remove(it)
                    }
                    opened = handle
                    handles.add(handle)
                    if (closed.get()) {
                        handle.close()
                        throw CancellationException("EPUB document closed")
                    }
                    handle
                }
            }
        }
        catch (error: Throwable) {
            opened?.close()
            throw error
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handles.toList().forEach {
            try {
                it.close()
            }
            catch (_: Exception) {
            }
        }
        dispose(this)
    }
    fun release() = synchronized(releaseLock) {
        if (!released) {
            // Cleanup is best-effort and must still release the owner lock/session
            // when a provider reports an IOException while closing its descriptor.
            try { zip.close() } catch (_: java.io.IOException) { }
            finally { released = true }
        }
    }
}

private class EntryContent(private val input: java.io.InputStream, override val sizeBytes: Long, private val expectedCrc: Long, private val dispatcher: CoroutineDispatcher, private val dispose: (EntryContent)->Unit): ResourceContent {
    private val closed = AtomicBoolean()
    private var count = 0L
    private val crc = CRC32()
    private var ended = false
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset in 0..buffer.size && length in 0..buffer.size-offset)
        check(!closed.get())
        if (length==0) return 0
        try {
            return withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                check(!closed.get())
                if (ended) return@withContext -1
                val n = input.read(buffer, offset, minOf(length.toLong(), EPUB_BUFFER_BYTES.toLong(), sizeBytes-count+1).toInt())
                if (n==-1) {
                    requireEpub(count==sizeBytes && crc.value==expectedCrc)
                    ended = true
                }
                else {
                    requireEpub(n>0 && n<=length)
                    count+=n
                    requireEpub(count<=sizeBytes)
                    crc.update(buffer, offset, n)
                }
                n
            }
        }
        catch (error: Throwable) {
            close()
            throw error
        }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                input.close()
            }
            finally {
                dispose(this)
            }
        }
    }
}
private val sessionPattern = Regex("session-[0-9a-f-]{36}")
private val payloadPattern = Regex("epub-[0-9a-f-]{36}\\.zip")
private fun deleteFile(path: Path) {
    try {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path)
    }
    catch (_: Exception) {
    }
}
private fun deleteSession(path: Path) {
    try {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(path).use {
            items ->
            var scanned = 0
            for (item in items) {
                if (++scanned>128) break
                if (payloadPattern.matches(item.fileName.toString()) || item.fileName.toString()==".owner.lock") deleteFile(item)
            }
        }
        Files.deleteIfExists(path)
    }
    catch (_: Exception) {
    }
}
private fun cleanupStale(base: Path) {
    Files.newDirectoryStream(base).use {
        items ->
        var scanned = 0
        for (item in items) {
            if (++scanned>128) break
            if (!sessionPattern.matches(item.fileName.toString()) || !Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) continue
            val lock = item.resolve(".owner.lock")
            if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) continue
            try {
                RandomAccessFile(lock.toFile(), "rw").use {
                    handle ->
                    val held = handle.channel.tryLock() ?: return@use
                    try {
                        deleteSession(item)
                    }
                    finally {
                        held.release()
                    }
                }
            }
            catch (_: Exception) {
            }
        }
    }
}
