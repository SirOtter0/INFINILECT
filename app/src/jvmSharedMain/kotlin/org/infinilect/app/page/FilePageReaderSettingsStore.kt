// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

internal const val PAGE_SETTINGS_DIRECTORY_NAME = "page-reader-preferences-v1"
internal const val PAGE_SETTINGS_RECORD_NAME = "settings.preferences"
internal const val PAGE_SETTINGS_RECORD_BYTES = 56
private const val magic = 0x494e465041474553L // INFPAGES
private val processLock = Any()
private val tempName = Regex("t-[0-9a-f-]{36}\\.part")
private val lastPreferenceTime = AtomicLong(0)
internal fun pagePreferenceTime(): Long = lastPreferenceTime.updateAndGet {
    maxOf(System.currentTimeMillis().coerceAtLeast(0), if (it == Long.MAX_VALUE) it else it + 1)
}

/** One fixed-size checksummed record in private persistent storage, NOT cache.
 * Same-directory temp + force + atomic replacement. Unsupported/failed commit preserves
 * the previous record; no destructive fallback. Short operations serialize across owners.
 * No filesystem API or retained path escapes into the reader/settings contract.
 */
internal class FilePageReaderSettingsStore(
    private val directory: Path?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeCommit: () -> Unit = {},
) : PageReaderSettingsStore {
    override suspend fun load(): PageReaderPreferences = operation(PageReaderPreferences()) { root, _ -> read(root) ?: PageReaderPreferences() }
    override suspend fun save(preferences: PageReaderPreferences): Boolean = operation(false) { root, context ->
        val previous = read(root)
        if (previous != null && previous.updatedAtEpochMillis >= preferences.updatedAtEpochMillis)
            return@operation previous.updatedAtEpochMillis > preferences.updatedAtEpochMillis || previous == preferences
        // Only stale files in our exact namespace are cleaned under the owner/OS lock.
        var scanned = 0
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                context.ensureActive()
                if (++scanned > 4096) return@operation false
                if (tempName.matches(entry.fileName.toString()) && !Files.isDirectory(entry, NOFOLLOW_LINKS))
                    Files.deleteIfExists(entry)
            }
        }
        val target = root.resolve(PAGE_SETTINGS_RECORD_NAME)
        if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS)) return@operation false
        val bytes = encode(preferences)
        val temp = root.resolve("t-${UUID.randomUUID()}.part")
        try {
            FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                permissions(temp, false)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { context.ensureActive(); check(channel.write(buffer) > 0) }
                channel.force(true)
            }
            beforeCommit(); context.ensureActive()
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } finally { try { Files.deleteIfExists(temp) } catch (_: Exception) { } }
    }

    private suspend fun <T> operation(fallback: T, action: (Path, kotlin.coroutines.CoroutineContext) -> T): T = withContext(dispatcher) {
        val context = currentCoroutineContext()
        context.ensureActive()
        try { synchronized(processLock) {
            context.ensureActive()
            val root = directory?.takeIf { it.isAbsolute } ?: return@synchronized fallback
            Files.createDirectories(root)
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return@synchronized fallback
            permissions(root, true)
            FileChannel.open(root.resolve(".settings.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                permissions(root.resolve(".settings.lock"), false)
                val lock = channel.tryLock() ?: return@synchronized fallback
                lock.use { action(root, context) }
            }
        } } catch (error: CancellationException) { throw error }
        catch (_: Exception) { fallback }
    }
    private fun read(root: Path): PageReaderPreferences? = try {
        val path = root.resolve(PAGE_SETTINGS_RECORD_NAME)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) null else FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != PAGE_SETTINGS_RECORD_BYTES.toLong()) return@use null
            val bytes = ByteBuffer.allocate(PAGE_SETTINGS_RECORD_BYTES)
            while (bytes.hasRemaining()) if (channel.read(bytes) <= 0) return@use null
            if (channel.size() != PAGE_SETTINGS_RECORD_BYTES.toLong()) return@use null
            decode(bytes.array())
        }
    } catch (_: Exception) { null }

    companion object {
        private fun permissions(path: Path, directory: Boolean) {
            // API 26+ Android supports this attribute view; do NOT call getFileStore().
            Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?.setPermissions(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        }
        private fun encode(record: PageReaderPreferences): ByteArray {
            val buffer = ByteBuffer.allocate(PAGE_SETTINGS_RECORD_BYTES)
            val settings = record.settings
            val choice = (if (settings.layout == PageLayout.DOUBLE) 4 else 0) + when (settings.mode) { PageReadingMode.PAGED_RTL -> 0; PageReadingMode.PAGED_LTR -> 1; PageReadingMode.VERTICAL -> 2; PageReadingMode.WEBTOON -> 3 }
            buffer.putLong(magic).putInt(if (settings.fit != PageFit.SCREEN) 3 else if (settings.layout == PageLayout.DOUBLE) 2 else 1)
            buffer.putInt(choice + when (settings.fit) { PageFit.SCREEN -> 0; PageFit.WIDTH -> 8; PageFit.HEIGHT -> 16 })
            buffer.putLong(record.updatedAtEpochMillis)
            buffer.put(MessageDigest.getInstance("SHA-256").digest(buffer.array().copyOfRange(0, 24)))
            return buffer.array()
        }
        private fun decode(bytes: ByteArray): PageReaderPreferences {
            require(MessageDigest.isEqual(bytes.copyOfRange(24, 56), MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, 24))))
            val input = ByteBuffer.wrap(bytes)
            require(input.long == magic)
            val version = input.int
            val choice = input.int
            require(version == 1 && choice in 0..3 || version == 2 && choice in 4..7 || version == 3 && choice in 8..23)
            val layout = if (choice % 8 >= 4) PageLayout.DOUBLE else PageLayout.SINGLE
            val fit = when { version != 3 -> PageFit.SCREEN; choice < 16 -> PageFit.WIDTH; else -> PageFit.HEIGHT }
            val mode = when (choice % 4) { 0 -> PageReadingMode.PAGED_RTL; 1 -> PageReadingMode.PAGED_LTR; 2 -> PageReadingMode.VERTICAL; 3 -> PageReadingMode.WEBTOON; else -> error("Unsupported preference") }
            return PageReaderPreferences(PageReaderSettings(mode, layout, fit), input.long)
        }
    }
}
