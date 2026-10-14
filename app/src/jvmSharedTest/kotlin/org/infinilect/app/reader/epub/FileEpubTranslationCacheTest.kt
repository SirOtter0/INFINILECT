// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import java.nio.ByteBuffer
import java.nio.file.*
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FileEpubTranslationCacheTest {
    private lateinit var root: Path
    @BeforeTest fun setup() { root = Files.createTempDirectory("translation-cache-test") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    @Test fun reopeningReusesVerifiedPrivateResultsAndCorruptionIsAMiss() = runBlocking {
        val key = listOf("book", "revision", "en", "es", "Hello 🌍")
        FileEpubTranslationCache(root).put(key, "Hola 🌍")
        assertEquals("Hola 🌍", FileEpubTranslationCache(root).get(key))
        assertNull(FileEpubTranslationCache(root).get(key + "other"))
        val file = Files.list(root).use { it.filter { path -> path.fileName.toString().endsWith(".translation") }.findFirst().get() }
        assertFalse(file.fileName.toString().contains("book"))
        Files.write(file, ByteArray(35))
        assertNull(FileEpubTranslationCache(root).get(key))
    }
    @Test fun evictionBoundsRecordsBytesAndTemporaryWrites() = runBlocking {
        val cache = FileEpubTranslationCache(root)
        repeat(300) { cache.put(listOf("book", it.toString()), "漢".repeat(8192)) }
        val files = Files.list(root).use { it.toList() }
        assertTrue(files.count { it.fileName.toString().endsWith(".translation") } <= EpubTranslationPolicy.CACHE_RECORDS)
        assertTrue(files.sumOf { Files.size(it) } <= EpubTranslationPolicy.CACHE_BYTES)
        assertFalse(files.any { it.fileName.toString().endsWith(".part") })
        assertEquals("漢".repeat(8192), cache.get(listOf("book", "299")))
        assertNull(cache.get(listOf("book", "0")))
    }
    @Test fun clearOnlyRemovesOwnedTranslationRecords() = runBlocking {
        val cache = FileEpubTranslationCache(root)
        cache.put(listOf("book"), "translated")
        val unrelated = root.resolve("progress.locator"); Files.writeString(unrelated, "saved")
        cache.clear(); assertEquals("saved", Files.readString(unrelated)); assertNull(cache.get(listOf("book")))
    }
    @Test fun symlinkRecordsAndUnavailableStorageCannotExposeExternalContent() = runBlocking {
        val cache = FileEpubTranslationCache(root); cache.put(listOf("book"), "translated")
        val record = Files.list(root).use { it.filter { path -> path.fileName.toString().endsWith(".translation") }.findFirst().get() }; Files.delete(record)
        val outside = Files.createTempFile("external-translation", ".txt")
        try {
            Files.writeString(outside, "private outside content"); Files.createSymbolicLink(record, outside)
            assertNull(cache.get(listOf("book"))); cache.clear(); assertTrue(Files.exists(outside))
        } finally { Files.deleteIfExists(outside) }
        val unavailable = FileEpubTranslationCache(root.resolve("blocked").also { Files.writeString(it, "file") })
        unavailable.put(listOf("key"), "value"); assertNull(unavailable.get(listOf("key")))
    }
    @Test fun settingsV1MigrationPreservesTypographyAndDoesNotEnableTranslation() = runBlocking {
        val directory = root.resolve(EPUB_SETTINGS_DIRECTORY_NAME); Files.createDirectory(directory)
        val bytes = ByteBuffer.allocate(68)
        bytes.putLong(0x494e464550554253L).putInt(1).putInt(26).putInt(180).putInt(32).putInt(2).putLong(42)
        bytes.put(MessageDigest.getInstance("SHA-256").digest(bytes.array().copyOfRange(0, 36)))
        Files.write(directory.resolve(EPUB_SETTINGS_RECORD_NAME), bytes.array())
        val store = FileEpubReaderSettingsStore(directory)
        val restored = store.load()
        assertEquals(EpubReaderSettings(26, 180, 32, EpubReadingTheme.DARK), restored.settings)
        assertEquals(42L, restored.updatedAtEpochMillis); assertFalse(restored.settings.translated)
        val next = restored.copy(settings = restored.settings.copy(translationSource = "en", translationTarget = "es", translated = true), updatedAtEpochMillis = 43)
        assertTrue(store.save(next)); assertEquals(next, FileEpubReaderSettingsStore(directory).load())
    }
    @Test fun modelBudgetBoundsBytesFilesReservationsAndRejectsSymlinks() {
        val model = root.resolve("model.bin")
        java.io.RandomAccessFile(model.toFile(), "rw").use { it.setLength(70L * 1024 * 1024) }
        assertTrue(translationModelStorageFits(root, 36L * 1024 * 1024))
        assertFalse(translationModelStorageFits(root, 60L * 1024 * 1024))
        Files.createSymbolicLink(root.resolve("external"), model)
        assertFalse(translationModelStorageFits(root)); Files.delete(root.resolve("external")); Files.delete(model)
        repeat(TRANSLATION_MODEL_FILES) { Files.createFile(root.resolve("file-$it")) }
        assertFalse(translationModelStorageFits(root))
    }

    @Test fun explicitCacheClearReportsStorageFailureInsteadOfClaimingSuccess() = runBlocking {
        val unavailable = root.resolve("blocked"); Files.writeString(unavailable, "file")
        assertFailsWith<EpubTranslationException> { FileEpubTranslationCache(unavailable).clear() }
        assertEquals("file", Files.readString(unavailable))
    }

}
