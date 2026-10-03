// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.cache

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val DEFAULT_DISK_CACHE_BYTES = 64L * 1024 * 1024
internal const val CACHE_DIRECTORY_NAME = "resource-cache-v1"
private const val MAGIC = 0x494e464341434845L // INFCACHE
private const val MAX_ENTRIES = 1024
private val ENTRY_NAME = Regex("e-[0-9a-f]{64}\\.entry")
private val TEMP_NAME = Regex("t-[0-9a-f-]{36}\\.part")

/** Filesystem boundary shared by JVM/Android (API 26+). No remote URLs or source policy.
 * One owner per directory, enforced by an exclusive OS file lock. Short metadata/file
 * operations are synchronized; network reads and full-payload verification never hold it.
 */
internal class DiskCacheStore(
    private val directory: Path?,
    private val maxBytes: Long,
    private val clock: () -> Long,
) : AutoCloseable {
    private data class Entry(val path: Path, val bytes: Long, var used: Long)
    private val entries = mutableMapOf<String, Entry>()
    private val readers = mutableSetOf<Hit>()
    private val writers = mutableSetOf<Write>()
    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var initialized = false
    private var enabled = false
    private var healthy = true
    private var closed = false
    private var lastUse = 0L

    init { require(maxBytes >= 0) }

    @Synchronized private fun prepare(): Boolean {
        if (closed) return false
        if (initialized) return enabled && healthy
        initialized = true
        val root = directory ?: return false
        try {
            require(root.isAbsolute) { "Cache storage must be absolute." }
            Files.createDirectories(root)
            check(Files.isDirectory(root, NOFOLLOW_LINKS))
            // Android private cache and Desktop per-user directory. Unsupported POSIX
            // permissions (Windows) retain inherited per-user directory permissions.
            try { Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------")) }
            catch (_: UnsupportedOperationException) { }
            lockChannel = FileChannel.open(root.resolve(".lock"), CREATE, WRITE, NOFOLLOW_LINKS)
            fileLock = lockChannel!!.tryLock() ?: throw IOException("Cache already owned")
            Files.newDirectoryStream(root).use { files ->
                for (path in files) {
                    val name = path.fileName.toString()
                    if (TEMP_NAME.matches(name)) { delete(path); continue }
                    if (!ENTRY_NAME.matches(name)) continue // Never delete unrelated names/directories.
                    try {
                        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                            val header = readHeader(channel)
                            check(name == "e-${cacheDigest(header.identity)}.entry")
                            check(channel.size() <= maxBytes)
                            entries[name] = Entry(path, channel.size(), Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis())
                            lastUse = maxOf(lastUse, entries.getValue(name).used)
                        }
                    } catch (_: Exception) { delete(path) }
                    // Bound the index even with thousands of tiny completed entries.
                    if (entries.size > MAX_ENTRIES) evict(0)
                }
            }
            enabled = healthy
            evict(0)
        } catch (_: Exception) {
            enabled = false
            entries.clear()
            releaseLock()
        }
        return enabled && healthy
    }

    private data class Header(val identity: ByteArray, val size: Long, val digest: ByteArray, val start: Long)

    private fun readHeader(channel: FileChannel): Header {
        val input = DataInputStream(Channels.newInputStream(channel)) // Channel owned by caller.
        check(input.readLong() == MAGIC && input.readInt() == 1)
        val count = input.readInt()
        check(count in 1..MAX_CACHE_IDENTITY_BYTES)
        val identity = ByteArray(count).also(input::readFully)
        val size = input.readLong()
        check(size >= 0)
        val digest = ByteArray(32).also(input::readFully)
        val start = channel.position()
        check(size <= Long.MAX_VALUE - start && channel.size() == start + size)
        return Header(identity, size, digest, start)
    }

    @Synchronized fun open(identity: ByteArray): Hit? {
        if (!prepare()) return null
        val name = "e-${cacheDigest(identity)}.entry"
        val entry = entries[name] ?: return null
        var channel: FileChannel? = null
        return try {
            channel = FileChannel.open(entry.path, READ, NOFOLLOW_LINKS)
            val header = readHeader(channel)
            check(header.identity.contentEquals(identity) && channel.size() == entry.bytes)
            Hit(name, channel, header.size, header.digest, header.start).also { readers += it }
        } catch (_: Exception) {
            try { channel?.close() } catch (_: Exception) { }
            remove(name)
            null
        }
    }

    /** Verifies on the same open descriptor later consumed; no whole-file allocation. */
    internal inner class Hit(
        val name: String,
        private val channel: FileChannel,
        val size: Long,
        private val expectedDigest: ByteArray,
        private val start: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        suspend fun verify() {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = channel.read(ByteBuffer.wrap(bytes))
                if (n == -1) break
                check(n > 0)
                digest.update(bytes, 0, n)
            }
            check(channel.size() == start + size && MessageDigest.isEqual(expectedDigest, digest.digest()))
            channel.position(start)
            touch(name)
        }
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed.get())
            val remaining = start + size - channel.position()
            check(remaining >= 0)
            return channel.read(ByteBuffer.wrap(buffer, offset, length)).also {
                check(it == -1 || it in 1..length)
                check(it == -1 || it <= remaining)
                if (it == -1) check(channel.size() == start + size)
            }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { channel.close() } catch (_: Exception) { }
            finally { synchronized(this@DiskCacheStore) { readers -= this } }
        }
    }

    @Synchronized fun invalidate(name: String) { remove(name) }

    @Synchronized private fun touch(name: String) {
        val entry = entries[name] ?: return
        val now = maxOf(clock(), if (lastUse == Long.MAX_VALUE) lastUse else lastUse + 1)
        lastUse = now
        entry.used = now
        try { Files.setLastModifiedTime(entry.path, FileTime.fromMillis(now)) } catch (_: Exception) { }
    }

    @Synchronized fun begin(identity: ByteArray, expected: Long?): Write? {
        if (!prepare()) return null
        val name = "e-${cacheDigest(identity)}.entry"
        if (entries.containsKey(name) || writers.any { it.name == name }) return null
        val header = header(identity, 0, ByteArray(32))
        if (expected != null && (expected < 0 || expected > maxBytes - header.size)) return null
        if (!evict(header.size.toLong(), addingEntry = true)) return null
        val path = directory!!.resolve("t-${UUID.randomUUID()}.part")
        var channel: FileChannel? = null
        return try {
            channel = FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
            try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
            catch (_: UnsupportedOperationException) { }
            writeFully(channel, header)
            Write(name, path, channel, identity, header.size.toLong()).also { writers += it }
        } catch (_: Exception) {
            try { channel?.close() } catch (_: Exception) { }
            delete(path)
            null
        }
    }

    internal inner class Write(
        val name: String,
        val path: Path,
        private val channel: FileChannel,
        private val identity: ByteArray,
        var storedBytes: Long,
    ) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var payloadBytes = 0L
        private var finished = false

        fun append(buffer: ByteArray, offset: Int, length: Int) = synchronized(this@DiskCacheStore) {
            if (finished) return@synchronized
            try {
                check(!closed && healthy && evict(length.toLong()))
                writeFully(channel, buffer, offset, length)
                storedBytes += length
                payloadBytes += length
                digest.update(buffer, offset, length)
            } catch (_: Exception) { abort() } // Cache failure never changes source bytes.
        }

        fun complete() = synchronized(this@DiskCacheStore) {
            if (finished) return@synchronized
            try {
                check(!closed && healthy)
                channel.position(0)
                writeFully(channel, header(identity, payloadBytes, digest.digest()))
                channel.force(true)
                channel.close()
                val destination = directory!!.resolve(name)
                check(!Files.exists(destination, NOFOLLOW_LINKS))
                try { Files.move(path, destination, ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(path, destination) }
                entries[name] = Entry(destination, storedBytes, 0)
                touch(name)
                finished = true
                writers -= this
            } catch (_: Exception) { abort() }
        }

        fun abort() = synchronized(this@DiskCacheStore) {
            if (finished) return@synchronized
            finished = true
            try { channel.close() } catch (_: Exception) { }
            delete(path)
            writers -= this
        }
    }

    /** Counts complete containers and live temp headers/payload, not just logical payload. */
    private fun evict(extra: Long, addingEntry: Boolean = false): Boolean {
        fun bytes(): Long = (entries.values.map { it.bytes } + writers.map { it.storedBytes })
            .fold(0L) { total, bytes -> if (bytes > Long.MAX_VALUE - total) Long.MAX_VALUE else total + bytes }
        val candidates = entries.entries.sortedWith(compareBy({ it.value.used }, { it.key }))
        for ((name, _) in candidates) {
            if (extra <= maxBytes && bytes() <= maxBytes - extra &&
                entries.size + writers.size + (if (addingEntry) 1 else 0) <= MAX_ENTRIES) break
            if (readers.none { it.name == name }) remove(name)
        }
        return extra <= maxBytes && bytes() <= maxBytes - extra &&
            entries.size + writers.size + (if (addingEntry) 1 else 0) <= MAX_ENTRIES
    }

    private fun remove(name: String) {
        if (readers.any { it.name == name }) return
        val entry = entries[name] ?: return
        if (delete(entry.path)) entries.remove(name)
    }

    private fun delete(path: Path): Boolean = try { Files.deleteIfExists(path); true }
    catch (_: Exception) { healthy = false; false }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        writers.toList().forEach { it.abort() }
        readers.toList().forEach { try { it.close() } catch (_: Exception) { } }
        entries.clear()
        releaseLock()
    }

    private fun releaseLock() {
        try { fileLock?.release() } catch (_: Exception) { }
        try { lockChannel?.close() } catch (_: Exception) { }
        fileLock = null; lockChannel = null
    }

    private fun header(identity: ByteArray, size: Long, digest: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeLong(MAGIC); it.writeInt(1); it.writeInt(identity.size)
                it.write(identity); it.writeLong(size); it.write(digest)
            }
        }.toByteArray()

    private fun writeFully(channel: FileChannel, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val buffer = ByteBuffer.wrap(bytes, offset, length)
        while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
    }
}
