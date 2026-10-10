// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import java.io.*
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
import org.infinilect.app.discovery.*

internal const val APPLICATION_APPEARANCE_DIRECTORY = "application-appearance-v1"

/** One private atomic record: legacy 40-byte appearance or ≤512-byte appearance/profile. No database/schema change.
 * File contents and directory entries are never parsed without a hard bound. */
internal class FileApplicationAppearanceStore(
    private val directory: Path?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApplicationAppearanceStore {
    override suspend fun load(): ApplicationThemeMode = loadPreferences().mode
    override suspend fun save(mode: ApplicationThemeMode): Boolean = savePreferences(loadPreferences().copy(mode = mode))

    override suspend fun loadPreferences(): ApplicationPreferences = withContext(dispatcher) {
        try {
            val root = directory?.takeIf { it.isAbsolute && Files.isDirectory(it, NOFOLLOW_LINKS) } ?: return@withContext ApplicationPreferences()
            val path = root.resolve("appearance.preferences")
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return@withContext ApplicationPreferences()
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                if (size !in 40..512) return@withContext ApplicationPreferences()
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) { ensureActive(); if (channel.read(buffer) <= 0) return@withContext ApplicationPreferences() }
                if (channel.size() != size) return@withContext ApplicationPreferences()
                val bytes = buffer.array(); val payload = bytes.copyOfRange(0, bytes.size - 32)
                require(MessageDigest.isEqual(bytes.takeLast(32).toByteArray(), MessageDigest.getInstance("SHA-256").digest(payload)))
                DataInputStream(ByteArrayInputStream(payload)).use { input ->
                    val magic = input.readInt(); require(magic in setOf(0x494E4631, 0x494E4632, 0x494E4633))
                    val mode = ApplicationThemeMode.entries[input.readInt()]
                    val profile = if (magic == 0x494E4631) LocalProfile() else {
                        val handled = input.readBoolean()
                        val name = input.readUTF().ifEmpty { null }
                        val country = input.readUTF().ifEmpty { null }
                        val language = input.readUTF().ifEmpty { null }
                        LocalProfile(name, country, language, handled)
                    }
                    val discovery = if (magic == 0x494E4633) {
                        val enabled = input.readBoolean(); val personalized = input.readBoolean(); val after = input.readLong()
                        val count = input.readUnsignedByte(); require(count <= Genre.entries.size)
                        val interests = List(count) { Genre.entries[input.readUnsignedByte()] }.toSet(); require(interests.size == count)
                        DiscoveryPreferences(enabled, personalized, interests, after)
                    } else DiscoveryPreferences()
                    require(input.available() == 0)
                    ApplicationPreferences(mode, profile, discovery)
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { ApplicationPreferences() }
    }
    override suspend fun savePreferences(value: ApplicationPreferences): Boolean = withContext(dispatcher) {
        var temp: Path? = null
        try {
            val root = directory?.takeIf { it.isAbsolute } ?: return@withContext false
            Files.createDirectories(root)
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return@withContext false
            permissions(root, true)
            val target = root.resolve("appearance.preferences")
            if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS)) return@withContext false
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                val legacy = value.profile == LocalProfile() && value.discovery == DiscoveryPreferences()
                data.writeInt(if (legacy) 0x494E4631 else if (value.discovery == DiscoveryPreferences()) 0x494E4632 else 0x494E4633); data.writeInt(value.mode.ordinal)
                if (!legacy) {
                    data.writeBoolean(value.profile.setupHandled)
                    data.writeUTF(value.profile.displayName.orEmpty()); data.writeUTF(value.profile.countryCode.orEmpty())
                    data.writeUTF(value.profile.interfaceLanguage.orEmpty())
                }
                if (value.discovery != DiscoveryPreferences()) {
                    data.writeBoolean(value.discovery.homeEnabled); data.writeBoolean(value.discovery.personalized)
                    data.writeLong(value.discovery.inferenceAfter); data.writeByte(value.discovery.interests.size)
                    value.discovery.interests.sortedBy { it.ordinal }.forEach { data.writeByte(it.ordinal) }
                }
            }
            val payload = output.toByteArray()
            val bytes = payload + MessageDigest.getInstance("SHA-256").digest(payload)
            check(bytes.size <= 512)
            temp = root.resolve("t-${UUID.randomUUID()}.part")
            FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                permissions(temp, false); val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { ensureActive(); check(channel.write(buffer) > 0) }
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
