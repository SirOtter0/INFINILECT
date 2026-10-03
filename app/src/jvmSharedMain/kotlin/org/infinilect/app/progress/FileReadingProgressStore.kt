// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.infinilect.core.*

internal const val PROGRESS_DIRECTORY_NAME = "reading-progress-v1"
internal const val MAX_PROGRESS_RECORD_BYTES = 16 * 1024
private const val MAX_IDENTITY_BYTES = 8 * 1024
private val recordName = Regex("p-[0-9a-f]{64}\\.progress")
private val tempName = Regex("t-[0-9a-f-]{36}\\.part")
// Short record operations across recreated owners serialize on IO, never the UI thread.
private val processLock = Any()

/** One bounded checksummed record per identity; no open resources retained between operations.
 * Quota refuses new records, never evicts user state. Atomic replacement is required:
 * unsupported moves fail safely and preserve the previous committed record.
 */
internal class FileReadingProgressStore(
    private val directory: Path?,
    private val maxEntries: Int = 1024,
    private val maxBytes: Long = 16L * 1024 * 1024,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeCommit: () -> Unit = {}, // Failure/cancellation seam for real-files tests.
) : ReadingProgressStore {
    init { require(maxEntries > 0 && maxBytes > 0) }

    override suspend fun get(id: ReadingProgressId): ReadingProgress? = operation(null) { root, _ ->
        val identity = encodeIdentity(id)
        read(root.resolve(name(identity)), id)
    }

    override suspend fun save(progress: ReadingProgress): Boolean = operation(false) { root, context ->
        val identity = encodeIdentity(progress.id)
        val target = root.resolve(name(identity))
        val previous = read(target, progress.id)
        if (previous != null && previous.updatedAtEpochMillis >= progress.updatedAtEpochMillis)
            return@operation previous == progress || previous.updatedAtEpochMillis > progress.updatedAtEpochMillis
        val bytes = encodeRecord(progress, identity)
        var count = 0
        var total = 0L
        var scanned = 0
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                context.ensureActive()
                if (++scanned > 4096) return@operation false
                val filename = entry.fileName.toString()
                if (tempName.matches(filename)) { Files.deleteIfExists(entry); continue }
                if (recordName.matches(filename)) {
                    if (!Files.isRegularFile(entry, NOFOLLOW_LINKS)) return@operation false
                    count++
                    val size = Files.size(entry)
                    if (size > maxBytes - total) return@operation false
                    total += size
                }
            }
        }
        val replacing = Files.exists(target, NOFOLLOW_LINKS)
        if ((!replacing && count >= maxEntries) || total - (if (replacing) Files.size(target) else 0) > maxBytes - bytes.size)
            return@operation false
        val temp = root.resolve("t-${UUID.randomUUID()}.part")
        try {
            FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                privatePermissions(temp, false)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { context.ensureActive(); channel.write(buffer) }
                channel.force(true)
            }
            beforeCommit()
            context.ensureActive()
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            true
        } finally { Files.deleteIfExists(temp) }
    }

    override suspend fun remove(id: ReadingProgressId): Boolean = operation(false) { root, _ ->
        Files.deleteIfExists(root.resolve(name(encodeIdentity(id)))); true
    }

    private suspend fun <T> operation(fallback: T, action: (Path, kotlin.coroutines.CoroutineContext) -> T): T = withContext(dispatcher) {
        val context = currentCoroutineContext()
        context.ensureActive()
        try {
            synchronized(processLock) {
                // All operations below are small blocking files; use the captured coroutine context.
                context.ensureActive()
                val root = directory?.takeIf { it.isAbsolute } ?: return@synchronized fallback
                Files.createDirectories(root)
                if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return@synchronized fallback
                privatePermissions(root, true)
                FileChannel.open(root.resolve(".progress.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                    privatePermissions(root.resolve(".progress.lock"), false)
                    val lock = channel.tryLock() ?: return@synchronized fallback
                    lock.use {
                        // No suspension inside this monitor/OS lock: action only uses synchronous IO.
                        action(root, context)
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { fallback }
    }

    private fun read(path: Path, expected: ReadingProgressId): ReadingProgress? { return try {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size !in 48..MAX_PROGRESS_RECORD_BYTES.toLong()) return null
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) return null
            if (channel.size() != size) return null
            decodeRecord(buffer.array()).takeIf { it.id == expected }
        }
    } catch (_: Exception) { null }
    }
}

private fun privatePermissions(path: Path, directory: Boolean) {
    if (Files.getFileStore(path).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
}

private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun name(identity: ByteArray): String = "p-" + digest(identity).joinToString("") {
    (it.toInt() and 255).toString(16).padStart(2, '0')
} + ".progress"

private fun encodeIdentity(id: ReadingProgressId): ByteArray = ByteArrayOutputStream().also { output ->
    DataOutputStream(output).use { stream ->
        for (field in listOf(id.publicationId.sourceId.value, id.publicationId.localId, id.resourceKey, id.format.name)) {
            require(field.length <= MAX_IDENTITY_BYTES)
            val bytes = field.encodeToByteArray()
            require(bytes.size <= MAX_IDENTITY_BYTES - output.size() - 4)
            stream.writeInt(bytes.size); stream.write(bytes)
        }
    }
}.toByteArray()

private fun encodeRecord(progress: ReadingProgress, identity: ByteArray): ByteArray {
    val locator = progress.locator as? ReadingLocator.Text ?: error("Unsupported locator")
    val body = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use {
            it.write(identity)
            it.writeInt(1) // Typed TEXT locator tag; future formats get their own schema/tag.
            it.writeLong(locator.codePointOffset); it.writeLong(locator.documentCodePoints)
            it.writeDouble(progress.progression); it.writeLong(progress.updatedAtEpochMillis)
        }
    }.toByteArray()
    return ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use {
            it.writeLong(0x494e4650524f4752L) // INFPROGR
            it.writeInt(1); it.writeInt(body.size); it.write(body); it.write(digest(body))
        }
    }.toByteArray().also { require(it.size <= MAX_PROGRESS_RECORD_BYTES) }
}

private fun decodeRecord(bytes: ByteArray): ReadingProgress {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    require(input.readLong() == 0x494e4650524f4752L && input.readInt() == 1)
    val length = input.readInt()
    require(length > 0 && length == bytes.size - 48)
    val body = ByteArray(length).also(input::readFully)
    val hash = ByteArray(32).also(input::readFully)
    require(MessageDigest.isEqual(hash, digest(body)))
    val fields = DataInputStream(ByteArrayInputStream(body))
    fun field(): String {
        val size = fields.readInt()
        require(size in 1..MAX_IDENTITY_BYTES && size <= fields.available())
        return ByteArray(size).also(fields::readFully).decodeToString(throwOnInvalidSequence = true)
    }
    val id = ReadingProgressId(PublicationId(SourceId(field()), field()), field(), PublicationFormat.valueOf(field()))
    require(fields.readInt() == 1)
    val locator = ReadingLocator.Text(fields.readLong(), fields.readLong())
    val result = ReadingProgress(id, locator, fields.readDouble(), fields.readLong())
    require(fields.available() == 0)
    return result
}
