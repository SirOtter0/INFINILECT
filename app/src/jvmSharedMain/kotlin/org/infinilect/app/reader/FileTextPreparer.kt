// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileLock
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.core.*

internal const val TEXT_DIRECTORY_NAME = "reader-text-v1"
internal const val MAX_TEXT_INDEX_ENTRIES = MAX_TEXT_DOCUMENT_BYTES / TEXT_MIN_WINDOW_CODE_POINTS + 1
internal actual val textPreparationDispatcher: CoroutineDispatcher get() = Dispatchers.IO

/** Non-launcher callers (tests/opt-in Desktop checks) still use an absolute per-user cache. */
internal actual fun defaultTextPreparer(): TextPreparer = object : TextPreparer {
    override suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader,
                                 dispatcher: CoroutineDispatcher): TextDocument {
        val owner = FileTextPreparer(desktopTextDirectory())
        return try { owner.prepareOwned(publication, resource, loader, dispatcher, closeOwner = true) }
        catch (error: Throwable) { owner.close(); throw error }
    }
}

internal fun desktopTextDirectory(
    os: String = System.getProperty("os.name", "") ?: "", home: String? = System.getProperty("user.home"),
    environment: Map<String, String> = System.getenv(),
): Path? = try {
    fun absolute(value: String?) = value?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
    val user = absolute(home)
    val base = when {
        os.startsWith("Windows", true) -> absolute(environment["LOCALAPPDATA"]) ?: user?.resolve("AppData/Local")
        os.startsWith("Mac", true) -> user?.resolve("Library/Caches")
        else -> absolute(environment["XDG_CACHE_HOME"]) ?: user?.resolve(".cache")
    }
    base?.resolve("org.infinilect.app")?.resolve(TEXT_DIRECTORY_NAME)
} catch (_: Exception) { null }

internal fun androidTextDirectory(privateCacheDir: Path): Path? =
    privateCacheDir.takeIf { it.isAbsolute }?.resolve(TEXT_DIRECTORY_NAME)

/** One application owner. Session locks distinguish stale owners from concurrently active apps.
 * Never trusts publication identifiers as paths; only generated session/payload names are managed.
 */
internal class FileTextPreparer(
    private val directory: Path?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TextPreparer {
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val documents = ConcurrentHashMap.newKeySet<FileWindows>()
    private var session: Path? = null
    private var lockFile: RandomAccessFile? = null
    private var ownerLock: FileLock? = null
    private var shutdown: Job? = null

    override suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader,
                                 dispatcher: CoroutineDispatcher): TextDocument =
        prepareOwned(publication, resource, loader, dispatcher, false)

    internal suspend fun prepareOwned(publication: Publication, resource: PublicationResource, loader: ResourceLoader,
                                      dispatcher: CoroutineDispatcher, closeOwner: Boolean): TextDocument {
        var prepared: FileWindows? = null
        // Obtain the source handle before the dispatcher handoff so cancellation cannot leak it.
        val content = loader.load(resource)
        var failure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            val declared = content.sizeBytes ?: throw TextDocumentException(TextFailure.UNKNOWN_SIZE)
            if (declared < 0) throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
            if (declared > MAX_TEXT_DOCUMENT_BYTES) throw TextDocumentException(TextFailure.TOO_LARGE)
            if (declared == 0L) throw TextDocumentException(TextFailure.EMPTY)
            return withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                val file = mutex.withLock {
                    check(!closed.get())
                    val root = initialize()
                    Files.createFile(root.resolve("text-${UUID.randomUUID()}.utf8"))
                }
                try {
                    val index = streamAndIndex(content, declared, file)
                    currentCoroutineContext().ensureActive()
                    val windows = FileWindows(file, index, declared.toInt(), ioDispatcher) { value ->
                        documents.remove(value)
                        cleanupScope.launch { deletePayload(file) }
                        if (closeOwner) close()
                    }
                    prepared = windows
                    documents.add(windows)
                    if (closed.get()) { windows.close(); throw CancellationException("Text owner closed") }
                    TextDocument(publication.id, publication.title, windows,
                        ReadingProgressId(publication.id, resource.key, PublicationFormat.TEXT))
                } catch (error: Throwable) {
                    deletePayload(file)
                    throw error
                }
            }
        } catch (error: Throwable) {
            failure = error
            prepared?.close()
            if (error is java.io.IOException || error is SecurityException)
                throw TextDocumentException(TextFailure.STORAGE, error)
            throw error
        } finally {
            try { content.close() }
            catch (closeError: Throwable) {
                prepared?.close()
                if (failure == null) throw closeError
                if (failure !== closeError) failure.addSuppressed(closeError)
            }
        }
    }

    private fun initialize(): Path {
        session?.let {
            if (Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) &&
                Files.isRegularFile(it.resolve(".owner.lock"), LinkOption.NOFOLLOW_LINKS)) return it
            // OS cache eviction may remove an idle owner's directory. Rebuild, never trust a replacement.
            if (Files.exists(it, LinkOption.NOFOLLOW_LINKS)) throw TextDocumentException(TextFailure.STORAGE)
            releaseLock()
            session = null
        }
        val root = directory?.takeIf { it.isAbsolute }?.normalize()
            ?: throw TextDocumentException(TextFailure.STORAGE)
        // Trust the OS-supplied private base, including Android's /data/user/0 alias.
        // The cache-owned namespace itself must never be a symlink.
        if (Files.isSymbolicLink(root)) throw TextDocumentException(TextFailure.STORAGE)
        Files.createDirectories(root)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw TextDocumentException(TextFailure.STORAGE)
        // Query the attribute view directly: Android does not support getFileStore().
        // Windows relies on the per-user platform directory's inherited ACL.
        Files.getFileAttributeView(root, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.setPermissions(PosixFilePermissions.fromString("rwx------"))
        val canonical = root.toRealPath()
        cleanupStale(canonical)
        val created = Files.createDirectory(canonical.resolve("session-${UUID.randomUUID()}"))
        try {
            val file = RandomAccessFile(created.resolve(".owner.lock").toFile(), "rw")
            lockFile = file
            ownerLock = file.channel.tryLock() ?: throw TextDocumentException(TextFailure.STORAGE)
            session = created
            return created
        } catch (error: Throwable) {
            lockFile?.close(); lockFile = null
            deleteSession(created)
            throw error
        }
    }

    private fun releaseLock() {
        try { ownerLock?.release() } catch (_: Exception) {}
        try { lockFile?.close() } catch (_: Exception) {}
        ownerLock = null; lockFile = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        documents.toList().forEach { it.close() }
        shutdown = cleanupScope.launch {
            mutex.withLock {
                // Cancellation of preparation is owned by the application/session before this close.
                releaseLock()
                session?.let(::deleteSession)
                session = null
            }
        }
        shutdown!!.invokeOnCompletion { cleanupScope.cancel() }
    }
    override suspend fun awaitClosed() { shutdown?.join() }
}

/** Primitive sparse index: at most one entry per 1024 code points (+ terminal position).
 * A window ends at a newline after 1024 points, or at 2048 even without line breaks.
 */
internal class TextIndex {
    private var bytes = IntArray(32)
    private var points = IntArray(32)
    var count = 0; private set
    var codePoints = 0; private set
    private var windowPoints = 0
    private var nextWindow = true
    private var bytePosition = 0
    private var first = true
    private var readable = false
    val capacity get() = bytes.size
    fun accept(codePoint: Int) {
        val width = when { codePoint < 0x80 -> 1; codePoint < 0x800 -> 2; codePoint < 0x10000 -> 3; else -> 4 }
        if (first && codePoint == 0xFEFF) { first = false; bytePosition += width; return }
        first = false
        if (codePoint == 0) throw TextDocumentException(TextFailure.EMPTY)
        if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) readable = true
        if (nextWindow) {
            if (count == MAX_TEXT_INDEX_ENTRIES) throw TextDocumentException(TextFailure.TOO_LARGE)
            if (count == bytes.size) {
                val size = minOf(bytes.size * 2, MAX_TEXT_INDEX_ENTRIES)
                bytes = bytes.copyOf(size); points = points.copyOf(size)
            }
            bytes[count] = bytePosition; points[count] = codePoints; count++
            windowPoints = 0; nextWindow = false
        }
        codePoints++; windowPoints++; bytePosition += width
        if (windowPoints == TEXT_WINDOW_CODE_POINTS || codePoint == 10 && windowPoints >= TEXT_MIN_WINDOW_CODE_POINTS)
            nextWindow = true
    }
    fun finish(size: Int) {
        if (bytePosition != size) throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
        if (!readable || codePoints == 0) throw TextDocumentException(TextFailure.EMPTY)
    }
    fun byteStart(index: Int): Int { require(index in 0 until count); return bytes[index] }
    fun pointStart(index: Int): Int { require(index in 0 until count); return points[index] }
}

private suspend fun streamAndIndex(content: ResourceContent, declared: Long, file: Path): TextIndex {
    val input = ByteBuffer.allocate(TEXT_READ_BUFFER_BYTES + 4)
    val output = CharBuffer.allocate(TEXT_READ_BUFFER_BYTES)
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val index = TextIndex()
    var total = 0L
    Files.newOutputStream(file, StandardOpenOption.WRITE).use { sink ->
        suspend fun decode(end: Boolean) {
            input.flip()
            while (true) {
                currentCoroutineContext().ensureActive()
                output.clear()
                val result = decoder.decode(input, output, end)
                if (result.isError) {
                    try { result.throwException() }
                    catch (error: java.nio.charset.CharacterCodingException) { throw TextDocumentException(TextFailure.INVALID_UTF8, error) }
                }
                output.flip()
                while (output.hasRemaining()) {
                    val character = output.get()
                    val cp = if (Character.isHighSurrogate(character)) {
                        check(output.hasRemaining()); Character.toCodePoint(character, output.get())
                    } else character.code
                    index.accept(cp)
                }
                if (result.isUnderflow) break
            }
            input.compact()
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (content.sizeBytes != declared) throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
            val carry = input.position()
            val requested = minOf(TEXT_READ_BUFFER_BYTES.toLong(), declared - total + 1).toInt()
            val count = content.read(input.array(), carry, requested)
            currentCoroutineContext().ensureActive()
            if (content.sizeBytes != declared) throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
            if (count == -1) {
                if (total != declared) throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
                decode(true)
                index.finish(declared.toInt())
                break
            }
            if (count !in 1..requested || count.toLong() > declared - total)
                throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
            sink.write(input.array(), carry, count)
            total += count
            input.position(carry + count)
            decode(false)
        }
    }
    return index
}

internal class FileWindows(
    private val file: Path, private val index: TextIndex, private val byteLength: Int,
    private val dispatcher: CoroutineDispatcher, private val release: (FileWindows) -> Unit,
) : TextWindows {
    private val closed = AtomicBoolean(false)
    private val guard = Any()
    private val readMutex = Mutex()
    private val cache = LinkedHashMap<Int, TextWindow>(TEXT_WINDOW_CACHE_ENTRIES, 0.75f, true)
    override val codePoints get() = index.codePoints
    override val count get() = index.count
    override fun start(index: Int) = this.index.pointStart(index)
    internal val cachedWindows get() = synchronized(guard) { cache.size }
    internal val indexCapacity get() = index.capacity
    override suspend fun read(index: Int): TextWindow = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        readMutex.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(guard) {
                check(!closed.get())
                cache[index]?.let { return@withLock it }
            }
            val start = this@FileWindows.index.byteStart(index)
            val end = if (index + 1 < count) this@FileWindows.index.byteStart(index + 1) else byteLength
            check(end - start in 1..TEXT_WINDOW_CODE_POINTS * 4)
            val bytes = ByteArray(end - start)
            try {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != byteLength.toLong())
                    throw TextDocumentException(TextFailure.STORAGE)
                RandomAccessFile(file.toFile(), "r").use { handle -> handle.seek(start.toLong()); handle.readFully(bytes) }
                val window = TextWindow(index, start(index), bytes.decodeToString(throwOnInvalidSequence = true))
                check(window.locations.codePoints <= TEXT_WINDOW_CODE_POINTS)
                currentCoroutineContext().ensureActive()
                synchronized(guard) {
                    check(!closed.get())
                    cache[index] = window
                    if (cache.size > TEXT_WINDOW_CACHE_ENTRIES) cache.remove(cache.keys.first())
                }
                window
            } catch (error: java.io.IOException) { throw TextDocumentException(TextFailure.STORAGE, error) }
        }
    }
    override fun close() {
        synchronized(guard) {
            if (!closed.compareAndSet(false, true)) return
            cache.clear()
        }
        // No filesystem IO or waiting for an in-flight read on the UI thread.
        release(this)
    }
}

private fun deletePayload(path: Path) { try { if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path) } catch (_: Exception) {} }
private val sessionName = Regex("session-(?:[0-9]+|[0-9a-f-]{36})")
private val payloadName = Regex("text-(?:[0-9]+|[0-9a-f-]{36})\\.utf8")
private fun deleteSession(path: Path) {
    try {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(path).use { entries ->
            for (entry in entries) if (payloadName.matches(entry.fileName.toString()) || entry.fileName.toString() == ".owner.lock") deletePayload(entry)
        }
        Files.deleteIfExists(path) // Fails safely if unrelated files remain.
    } catch (_: Exception) {}
}
private fun cleanupStale(root: Path) {
    Files.newDirectoryStream(root).use { entries ->
        var scanned = 0
        for (entry in entries) {
            if (++scanned > 128) break
            if (!sessionName.matches(entry.fileName.toString()) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue
            val lock = entry.resolve(".owner.lock")
            if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) continue
            try {
                RandomAccessFile(lock.toFile(), "rw").use { handle ->
                    val held = handle.channel.tryLock() ?: return@use
                    try { deleteSession(entry) } finally { held.release() }
                }
            } catch (_: Exception) {} // Active overlapping owner / unavailable lock: leave untouched.
        }
    }
}
