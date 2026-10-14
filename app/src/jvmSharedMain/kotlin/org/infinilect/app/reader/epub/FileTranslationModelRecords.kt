// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import java.io.*
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlinx.coroutines.*

internal actual fun translationEpochMillis() = System.currentTimeMillis()

/** Bounded private ownership metadata; corrupt/missing data defaults to protecting installed models. */
internal class FileTranslationModelRecords(private val directory: Path?) : TranslationModelRecords {
    private val filename = "model-retention.preferences"
    override suspend fun load(): Map<TranslationPair, ModelRetention> = withContext(Dispatchers.IO) {
        try {
            val root = directory ?: return@withContext emptyMap()
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return@withContext emptyMap()
            val path = root.resolve(filename)
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return@withContext emptyMap()
            val bytes = Files.newByteChannel(path, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
                if (channel.size() !in 36..512) return@withContext emptyMap()
                val data = java.nio.ByteBuffer.allocate(channel.size().toInt())
                while (data.hasRemaining()) { ensureActive(); check(channel.read(data) > 0) }; data.array()
            }
            val payload = bytes.copyOfRange(0, bytes.size - 32)
            require(MessageDigest.isEqual(bytes.takeLast(32).toByteArray(), MessageDigest.getInstance("SHA-256").digest(payload)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == 0x494E4D31)
                val count = input.readUnsignedByte(); require(count <= 4)
                val records = mutableMapOf<TranslationPair, ModelRetention>()
                repeat(count) {
                    val pair = TranslationPair(input.readUTF(), input.readUTF())
                    require(pair !in records)
                    records[pair] = ModelRetention(input.readBoolean(), input.readLong().also { require(it >= 0) })
                }
                require(input.available() == 0); records
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
    }
    override suspend fun save(records: Map<TranslationPair, ModelRetention>): Boolean = withContext(Dispatchers.IO) {
        var temp: Path? = null
        try {
            require(records.size <= 4)
            val root = directory?.takeIf { it.isAbsolute } ?: return@withContext false
            Files.createDirectories(root); require(Files.isDirectory(root, NOFOLLOW_LINKS))
            val payload = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use { out ->
                out.writeInt(0x494E4D31); out.writeByte(records.size)
                records.entries.sortedBy { it.key.source }.forEach { (pair, record) ->
                    out.writeUTF(pair.source); out.writeUTF(pair.target); out.writeBoolean(record.pinned); out.writeLong(record.lastUsed)
                }
            } }.toByteArray()
            val bytes = payload + MessageDigest.getInstance("SHA-256").digest(payload)
            temp = Files.createTempFile(root, "retention-", ".part")
            java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { ensureActive(); check(channel.write(buffer) > 0) }; channel.force(true)
            }
            ensureActive(); Files.move(temp, root.resolve(filename), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        finally { temp?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} } }
    }
}
