// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import java.nio.ByteBuffer
import java.nio.file.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.test.*

/** Real files/new owners on the host provider. This is not Android device verification. */
class FileEpubReaderSettingsStoreTest {
    private lateinit var root: Path
    private val settings = EpubReaderSettings(28, 190, 36, EpubReadingTheme.DARK)
    private val directory get() = root.resolve(EPUB_SETTINGS_DIRECTORY_NAME)
    private val record get() = directory.resolve(EPUB_SETTINGS_RECORD_NAME)
    @BeforeTest fun setup() { root = Files.createTempDirectory("epub-preferences") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    @Test fun missingRecordLoadsDefaults() = runBlocking { assertEquals(EpubReaderPreferences(), FileEpubReaderSettingsStore(directory).load()) }
    @Test fun saveThenEntirelyNewStoreLoadsAllFourSettingsFromCommittedRecord() = runBlocking {
        val value = EpubReaderPreferences(settings, 1)
        assertTrue(FileEpubReaderSettingsStore(directory).save(value))
        assertEquals(EPUB_SETTINGS_RECORD_BYTES.toLong(), Files.size(record))
        assertEquals(value, FileEpubReaderSettingsStore(directory).load())
    }
    @Test fun entireOwnerRecreationRestoresWithoutRamAfterCacheDeletion() = runBlocking {
        val first = EpubSettingsPersistence(FileEpubReaderSettingsStore(directory), clock = ::epubPreferenceTime)
        first.awaitLoaded(); first.submit(first.claimReader(), settings); first.close(); first.awaitClosed()
        assertFalse(first.saveFailed.value); assertTrue(Files.isRegularFile(record))
        Files.createDirectories(root.resolve("cache")).resolve("disposable").toFile().writeText("cache")
        root.resolve("cache").toFile().deleteRecursively()
        val second = EpubSettingsPersistence(FileEpubReaderSettingsStore(directory), clock = ::epubPreferenceTime)
        try { second.awaitLoaded(); assertEquals(settings, second.settings.value) }
        finally { second.close(); second.awaitClosed() }
    }
    @Test fun repeatedFreshOwnersUpdateSameBoundedRecord() = runBlocking {
        repeat(5) { i ->
            val owner = EpubSettingsPersistence(FileEpubReaderSettingsStore(directory), clock = ::epubPreferenceTime)
            owner.awaitLoaded(); if (i > 0) assertEquals(20 + i - 1, owner.settings.value.fontSize)
            owner.submit(owner.claimReader(), settings.copy(fontSize = 20 + i)); owner.close(); owner.awaitClosed()
        }
        assertEquals(24, FileEpubReaderSettingsStore(directory).load().settings.fontSize)
        assertEquals(2L, Files.list(directory).use { it.count() }) // record + lock, no accumulation
    }
    @Test fun corruptionAndTruncationFallBackToDefaults() = runBlocking {
        assertTrue(FileEpubReaderSettingsStore(directory).save(EpubReaderPreferences(settings, 1)))
        val valid = Files.readAllBytes(record)
        for (bytes in listOf(byteArrayOf(), valid.copyOf(20), valid + byteArrayOf(1), valid.copyOf().also { it[17] = 1 })) {
            Files.write(record, bytes); assertEquals(EpubReaderPreferences(), FileEpubReaderSettingsStore(directory).load())
        }
    }
    private fun mutateInt(offset: Int, value: Int) {
        val bytes = Files.readAllBytes(record); ByteBuffer.wrap(bytes).putInt(offset, value)
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, 36)).copyInto(bytes, 36)
        Files.write(record, bytes)
    }
    @Test fun unsupportedFutureSchemaLoadsDefaults() = runBlocking {
        FileEpubReaderSettingsStore(directory).save(EpubReaderPreferences(settings, 1)); mutateInt(8, 2)
        assertEquals(EpubReaderPreferences(), FileEpubReaderSettingsStore(directory).load())
    }
    @Test fun checksumValidOutOfRangeValuesAndInvalidThemeAreRejected() = runBlocking {
        for ((offset, value) in listOf(12 to 100000, 12 to -1, 16 to 0, 16 to 201, 20 to -50, 20 to 100000, 24 to 99)) {
            FileEpubReaderSettingsStore(directory).save(EpubReaderPreferences(settings, 1)); mutateInt(offset, value)
            assertEquals(EpubReaderPreferences(), FileEpubReaderSettingsStore(directory).load())
        }
    }
    @Test fun oversizedPersistedFileIsRejectedBeforeAllocation() = runBlocking {
        Files.createDirectories(directory); Files.write(record, ByteArray(1024 * 1024))
        assertEquals(EpubReaderPreferences(), FileEpubReaderSettingsStore(directory).load())
    }
    @Test fun failedAtomicCommitPreservesPreviousRecordAndReportsFalse() = runBlocking {
        val first = EpubReaderPreferences(settings, 1); FileEpubReaderSettingsStore(directory).save(first)
        val failing = FileEpubReaderSettingsStore(directory, beforeCommit = { throw AtomicMoveNotSupportedException("", "", "") })
        assertFalse(failing.save(EpubReaderPreferences(settings.copy(fontSize = 20), 2)))
        assertEquals(first, FileEpubReaderSettingsStore(directory).load())
        assertFalse(Files.list(directory).use { it.anyMatch { p -> p.toString().endsWith(".part") } })
    }
    @Test fun failedWriteCannotBeRestoredFromRamByCompletelyNewOwner() = runBlocking {
        val failed = EpubSettingsPersistence(FileEpubReaderSettingsStore(directory, beforeCommit = { error("private detail") }))
        failed.awaitLoaded(); failed.submit(failed.claimReader(), settings); failed.close(); failed.awaitClosed()
        assertTrue(failed.saveFailed.value); assertFalse(Files.exists(record))
        val fresh = EpubSettingsPersistence(FileEpubReaderSettingsStore(directory))
        try { fresh.awaitLoaded(); assertEquals(EpubReaderSettings(), fresh.settings.value) }
        finally { fresh.close(); fresh.awaitClosed() }
    }
    @Test fun cancelledCommitLeavesPreviousDurableChoiceAndNoTemp() = runBlocking {
        val first = EpubReaderPreferences(settings, 1); FileEpubReaderSettingsStore(directory).save(first)
        val cancelling = FileEpubReaderSettingsStore(directory, beforeCommit = { throw CancellationException() })
        assertFailsWith<CancellationException> { cancelling.save(EpubReaderPreferences(settings.copy(margin = 8), 2)) }
        assertEquals(first, FileEpubReaderSettingsStore(directory).load())
        assertFalse(Files.list(directory).use { it.anyMatch { p -> p.toString().endsWith(".part") } })
    }
    @Test fun olderDrainingOwnerCannotReplaceNewerCommittedChoice() = runBlocking {
        FileEpubReaderSettingsStore(directory).save(EpubReaderPreferences(settings, 20))
        assertTrue(FileEpubReaderSettingsStore(directory).save(EpubReaderPreferences(settings.copy(fontSize = 14), 10)))
        assertEquals(settings, FileEpubReaderSettingsStore(directory).load().settings)
    }
    @Test fun unavailableAndRelativePathsFailSafely() = runBlocking {
        for (path in listOf(null, Path.of("relative-preferences"))) {
            val store = FileEpubReaderSettingsStore(path)
            assertEquals(EpubReaderPreferences(), store.load()); assertFalse(store.save(EpubReaderPreferences(settings, 1)))
        }
    }
    @Test fun unrelatedFilesSurviveAndOnlyOwnedStaleTempsAreRemoved() = runBlocking {
        Files.createDirectories(directory)
        val unrelated = directory.resolve("unrelated.part"); Files.write(unrelated, byteArrayOf(42))
        val stale = directory.resolve("t-12345678-1234-1234-1234-123456789abc.part"); Files.write(stale, byteArrayOf(1))
        assertTrue(FileEpubReaderSettingsStore(directory).save(EpubReaderPreferences(settings, 1)))
        assertTrue(Files.exists(unrelated)); assertFalse(Files.exists(stale))
    }
    @Test fun recordSymlinkCannotReadOrReplaceOutsidePublication() = runBlocking {
        Files.createDirectories(directory)
        val outside = root.resolve("outside"); Files.write(outside, byteArrayOf(42))
        Files.createSymbolicLink(record, outside)
        val store = FileEpubReaderSettingsStore(directory)
        assertEquals(EpubReaderPreferences(), store.load()); assertFalse(store.save(EpubReaderPreferences(settings, 1)))
        assertContentEquals(byteArrayOf(42), Files.readAllBytes(outside))
    }
}
