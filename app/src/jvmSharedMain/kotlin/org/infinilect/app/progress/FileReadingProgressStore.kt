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
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import org.infinilect.app.progress.ProgressStorageFailure.Operation
import org.infinilect.app.progress.ProgressStorageFailure.Stage
import org.infinilect.app.progress.ProgressStorageFailure.Reason
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
    private val setPrivatePermissions: (Path, Boolean) -> Unit = ::privateProgressPermissions,
    private val onFailure: (ProgressStorageFailure) -> Unit = {},
) : ReadingProgressStore {
    init { require(maxEntries > 0 && maxBytes > 0) }

    // Filesystem API seams let host tests reproduce Android/provider failures precisely.
    override suspend fun get(id: ReadingProgressId): ReadingProgress? = operation(Operation.GET, null) { root, attempt ->
        val identity = attempt.at(Stage.IDENTITY) { encodeIdentity(id) }
        read(root.resolve(name(identity)), id, attempt)
    }

    override suspend fun save(progress: ReadingProgress): Boolean = operation(Operation.SAVE, false) { root, attempt ->
        val context = attempt.context
        val identity = attempt.at(Stage.IDENTITY) { encodeIdentity(progress.id) }
        val target = root.resolve(name(identity))
        val previous = read(target, progress.id, attempt)
        if (previous != null && previous.updatedAtEpochMillis >= progress.updatedAtEpochMillis)
            return@operation previous == progress || previous.updatedAtEpochMillis > progress.updatedAtEpochMillis
        val bytes = attempt.at(Stage.RECORD_ENCODE) { encodeRecord(progress, identity) }
        var count = 0
        var total = 0L
        var scanned = 0
        val scanValid = attempt.at(Stage.DIRECTORY_SCAN) {
            Files.newDirectoryStream(root).use { entries ->
                for (entry in entries) {
                    context.ensureActive()
                    if (++scanned > 4096) return@use false
                    val filename = entry.fileName.toString()
                    if (tempName.matches(filename)) { Files.deleteIfExists(entry); continue }
                    if (recordName.matches(filename)) {
                        if (!Files.isRegularFile(entry, NOFOLLOW_LINKS)) return@use false
                        count++
                        val size = Files.size(entry)
                        if (size > maxBytes - total) return@use false
                        total += size
                    }
                }
                true
            }
        }
        if (!scanValid) { attempt.reject(Stage.DIRECTORY_SCAN, Reason.LIMIT); return@operation false }
        val withinQuota = attempt.at(Stage.QUOTA) {
            val replacing = Files.exists(target, NOFOLLOW_LINKS)
            (replacing || count < maxEntries) &&
                total - (if (replacing) Files.size(target) else 0) <= maxBytes - bytes.size
        }
        if (!withinQuota) { attempt.reject(Stage.QUOTA, Reason.LIMIT); return@operation false }
        val temp = root.resolve("t-${UUID.randomUUID()}.part")
        try {
            attempt.at(Stage.TEMP_OPEN) { FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS) }.use { channel ->
                attempt.at(Stage.TEMP_PERMISSIONS) { setPrivatePermissions(temp, false) }
                attempt.at(Stage.TEMP_WRITE) {
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) { context.ensureActive(); channel.write(buffer) }
                }
                attempt.at(Stage.TEMP_SYNC) { channel.force(true) }
            }
            attempt.at(Stage.COMMIT) {
                beforeCommit()
                context.ensureActive()
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            }
            true
        } finally {
            // Cleanup cannot hide the diagnosed original failure or a successful commit.
            try { attempt.at(Stage.TEMP_CLEANUP) { Files.deleteIfExists(temp) } }
            catch (_: Exception) { }
        }
    }

    override suspend fun remove(id: ReadingProgressId): Boolean = operation(Operation.REMOVE, false) { root, attempt ->
        val identity = attempt.at(Stage.IDENTITY) { encodeIdentity(id) }
        attempt.at(Stage.REMOVE) { Files.deleteIfExists(root.resolve(name(identity))) }; true
    }

    private suspend fun <T> operation(operation: Operation, fallback: T, action: (Path, Attempt) -> T): T = withContext(dispatcher) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val attempt = Attempt(operation, context)
        try {
            synchronized(processLock) {
                // All operations below are small blocking files; use the captured coroutine context.
                context.ensureActive()
                val root = directory?.takeIf { it.isAbsolute } ?: run {
                    attempt.reject(Stage.PATH, Reason.UNAVAILABLE); return@synchronized fallback
                }
                attempt.at(Stage.DIRECTORY_CREATE) { Files.createDirectories(root) }
                if (!attempt.at(Stage.DIRECTORY_VALIDATE) { Files.isDirectory(root, NOFOLLOW_LINKS) }) {
                    attempt.reject(Stage.DIRECTORY_VALIDATE, Reason.INVALID); return@synchronized fallback
                }
                attempt.at(Stage.DIRECTORY_PERMISSIONS) { setPrivatePermissions(root, true) }
                val lockPath = root.resolve(".progress.lock")
                attempt.at(Stage.LOCK_OPEN) { FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS) }.use { channel ->
                    attempt.at(Stage.LOCK_PERMISSIONS) { setPrivatePermissions(lockPath, false) }
                    val lock = attempt.at(Stage.LOCK_ACQUIRE) { channel.tryLock() } ?: run {
                        attempt.reject(Stage.LOCK_ACQUIRE, Reason.BUSY); return@synchronized fallback
                    }
                    lock.use {
                        // No suspension inside this monitor/OS lock: action only uses synchronous IO.
                        action(root, attempt)
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { fallback }
    }

    private fun read(path: Path, expected: ReadingProgressId, attempt: Attempt): ReadingProgress? { return try {
        val bytes = attempt.at(Stage.RECORD_READ) {
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                if (size !in 48..MAX_PROGRESS_RECORD_BYTES.toLong()) {
                    attempt.reject(Stage.RECORD_READ, Reason.INVALID); return null
                }
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) {
                    attempt.reject(Stage.RECORD_READ, Reason.INVALID); return null
                }
                if (channel.size() != size) { attempt.reject(Stage.RECORD_READ, Reason.INVALID); return null }
                buffer.array()
            }
        }
        attempt.at(Stage.RECORD_DECODE) { decodeRecord(bytes).takeIf { it.id == expected } }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }
    }

    private inner class Attempt(val operation: Operation, val context: kotlin.coroutines.CoroutineContext) {
        inline fun <T> at(stage: Stage, block: () -> T): T = try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val reason = when (error) {
                is SecurityException -> Reason.SECURITY
                is UnsupportedOperationException -> Reason.UNSUPPORTED
                is java.io.IOException -> Reason.IO
                is IllegalArgumentException -> Reason.INVALID
                else -> Reason.OTHER
            }
            reject(stage, reason)
            throw error
        }
        fun reject(stage: Stage, reason: Reason) {
            // Diagnostics are best-effort and must never change storage outcomes.
            try { onFailure(ProgressStorageFailure(operation, stage, reason)) } catch (_: Exception) { }
        }
    }
}

internal fun privateProgressPermissions(path: Path, directory: Boolean) {
    // Android libcore getFileStore() always throws SecurityException. A path attribute
    // view is supported there (API 26+) and applies permissions without filesystem probing.
    // Non-POSIX providers (e.g. Windows) retain the existing inherited private-directory ACL.
    Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        ?.setPermissions(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
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
