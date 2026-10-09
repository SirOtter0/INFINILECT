// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

internal const val APPLICATION_APPEARANCE_DIRECTORY = "application-appearance-v1"

/** One 40-byte record, private storage, atomic replacement. No database/schema change.
 * File contents and directory entries are never parsed without a hard bound. */
internal class FileApplicationAppearanceStore(
    private val directory: Path?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApplicationAppearanceStore {
    override suspend fun load(): ApplicationThemeMode = withContext(dispatcher) {
        try {
            val root = directory?.takeIf { it.isAbsolute && Files.isDirectory(it, NOFOLLOW_LINKS) } ?: return@withContext ApplicationThemeMode.SYSTEM
            val path = root.resolve("appearance.preferences")
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return@withContext ApplicationThemeMode.SYSTEM
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                if (channel.size() != 40L) return@withContext ApplicationThemeMode.SYSTEM
                val buffer = ByteBuffer.allocate(40)
                while (buffer.hasRemaining()) { ensureActive(); if (channel.read(buffer) <= 0) return@withContext ApplicationThemeMode.SYSTEM }
                val bytes = buffer.array()
                require(MessageDigest.isEqual(bytes.copyOfRange(8, 40), MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, 8))))
                buffer.flip(); require(buffer.int == 0x494E4631)
                ApplicationThemeMode.entries[buffer.int]
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { ApplicationThemeMode.SYSTEM }
    }
    override suspend fun save(mode: ApplicationThemeMode): Boolean = withContext(dispatcher) {
        var temp: Path? = null
        try {
            val root = directory?.takeIf { it.isAbsolute } ?: return@withContext false
            Files.createDirectories(root)
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return@withContext false
            permissions(root, true)
            val target = root.resolve("appearance.preferences")
            if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS)) return@withContext false
            val record = ByteBuffer.allocate(40).putInt(0x494E4631).putInt(mode.ordinal)
            record.put(MessageDigest.getInstance("SHA-256").digest(record.array().copyOfRange(0, 8)))
            temp = root.resolve("t-${UUID.randomUUID()}.part")
            FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                permissions(temp, false); record.flip()
                while (record.hasRemaining()) { ensureActive(); check(channel.write(record) > 0) }
                channel.force(true)
            }
            ensureActive()
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        finally { temp?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} } }
    }
    private fun permissions(path: Path, directory: Boolean) {
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?.setPermissions(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
    }
}
