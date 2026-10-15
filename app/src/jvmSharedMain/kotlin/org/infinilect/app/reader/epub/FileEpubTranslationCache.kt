// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import java.io.*
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.coroutines.*

/** Disposable private results, not progress. No plaintext keys, external paths or database. */
internal class FileEpubTranslationCache(private val directory: Path?) : EpubTranslationCache {
    companion object { private val lock = Any() }
    private val owned = Regex("[0-9a-f]{64}\\.translation")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun name(key: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> key.forEach { value ->
            val part = value.toByteArray(Charsets.UTF_8); out.writeInt(part.size); out.write(part)
        } }
        return digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) } + ".translation"
    }
    private suspend fun <T> operation(fallback: T, action: (Path, kotlin.coroutines.CoroutineContext) -> T): T = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext(); context.ensureActive()
        try { synchronized(lock) {
            val root = directory?.takeIf { it.isAbsolute } ?: return@synchronized fallback
            Files.createDirectories(root)
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return@synchronized fallback
            Files.getFileAttributeView(root, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?.setPermissions(PosixFilePermissions.fromString("rwx------"))
            java.nio.channels.FileChannel.open(root.resolve(".cache.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lease = channel.tryLock() ?: return@synchronized fallback
                lease.use { context.ensureActive(); action(root, context) }
            }
        } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fallback }
    }
    override suspend fun get(key: List<String>): String? = operation(null) { root, context ->
        val path = root.resolve(name(key))
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) !in 32L..32L + EpubTranslationPolicy.OUTPUT_UNITS * 4) return@operation null
        val bytes = Files.newByteChannel(path, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(channel.size().toInt())
            while (buffer.hasRemaining()) { context.ensureActive(); if (channel.read(buffer) <= 0) return@operation null }
            buffer.array()
        }
        val payload = bytes.copyOfRange(32, bytes.size)
        if (!MessageDigest.isEqual(bytes.copyOfRange(0, 32), digest(payload))) { Files.deleteIfExists(path); return@operation null }
        val text = payload.decodeToString(throwOnInvalidSequence = true)
        if (text.isBlank() || text.length > EpubTranslationPolicy.OUTPUT_UNITS) return@operation null
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
        text
    }
    override suspend fun put(key: List<String>, value: String) = operation(Unit) { root, context ->
        require(value.isNotBlank() && value.length <= EpubTranslationPolicy.OUTPUT_UNITS)
        val payload = value.toByteArray(Charsets.UTF_8); val bytes = digest(payload) + payload
        val entries = Files.newDirectoryStream(root).use { stream ->
            val result = mutableListOf<Path>()
            for (path in stream) {
                if (result.size >= EpubTranslationPolicy.CACHE_RECORDS + 1) { clearOwned(root, context); return@operation }
                if (owned.matches(path.fileName.toString()) && Files.isRegularFile(path, NOFOLLOW_LINKS)) result.add(path)
            }
            result.sortedBy { Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis() }.toMutableList()
        }
        var size = entries.sumOf { Files.size(it) }
        while (entries.isNotEmpty() && (entries.size >= EpubTranslationPolicy.CACHE_RECORDS || size + bytes.size > EpubTranslationPolicy.CACHE_BYTES)) {
            context.ensureActive(); val old = entries.removeAt(0); size -= Files.size(old); Files.deleteIfExists(old)
        }
        val temp = root.resolve("translation.part")
        try {
            Files.deleteIfExists(temp)
            java.nio.channels.FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { out ->
                Files.getFileAttributeView(temp, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                    ?.setPermissions(PosixFilePermissions.fromString("rw-------"))
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { context.ensureActive(); check(out.write(buffer) > 0) }
                out.force(true)
            }
            context.ensureActive()
            Files.move(temp, root.resolve(name(key)), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun clearOwned(root: Path, context: kotlin.coroutines.CoroutineContext) {
        Files.newDirectoryStream(root).use { stream -> for (path in stream) {
            context.ensureActive()
            if ((owned.matches(path.fileName.toString()) || path.fileName.toString() == "translation.part") && !Files.isDirectory(path, NOFOLLOW_LINKS)) Files.deleteIfExists(path)
        } }
    }
    override suspend fun clear() {
        val cleared = operation(false) { root, context -> clearOwned(root, context); true }
        if (!cleared) throw EpubTranslationException("Saved translations could not be cleared. Check private storage and retry. Original reading is unchanged.")
    }
}
