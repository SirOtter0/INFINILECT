// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import java.nio.ByteBuffer
import java.nio.file.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.test.*

/** Real files/new owners on the host provider. This is not Android device verification. */
class FilePageReaderSettingsStoreTest {
    private lateinit var root: Path
    private val settings = PageReaderSettings(PageReadingMode.WEBTOON)
    private val directory get() = root.resolve(PAGE_SETTINGS_DIRECTORY_NAME)
    private val record get() = directory.resolve(PAGE_SETTINGS_RECORD_NAME)
    @BeforeTest fun setup() { root = Files.createTempDirectory("page-preferences") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    @Test fun missingRecordLoadsDefaults() = runBlocking { assertEquals(PageReaderPreferences(), FilePageReaderSettingsStore(directory).load()) }
    @Test fun saveThenEntirelyNewStoreLoadsModeFromCommittedRecord() = runBlocking {
        val value = PageReaderPreferences(settings, 1)
        assertTrue(FilePageReaderSettingsStore(directory).save(value))
        assertEquals(PAGE_SETTINGS_RECORD_BYTES.toLong(), Files.size(record))
        assertEquals(value, FilePageReaderSettingsStore(directory).load())
    }
    @Test fun entireOwnerRecreationRestoresWithoutRamAfterCacheDeletion() = runBlocking {
        val first = PageSettingsPersistence(FilePageReaderSettingsStore(directory), clock = ::pagePreferenceTime)
        first.awaitLoaded(); first.submit(first.claimReader(), settings); first.close(); first.awaitClosed()
        assertFalse(first.saveFailed.value); assertTrue(Files.isRegularFile(record))
        Files.createDirectories(root.resolve("cache")).resolve("disposable").toFile().writeText("cache")
        root.resolve("cache").toFile().deleteRecursively()
        val second = PageSettingsPersistence(FilePageReaderSettingsStore(directory), clock = ::pagePreferenceTime)
        try { second.awaitLoaded(); assertEquals(settings, second.settings.value) }
        finally { second.close(); second.awaitClosed() }
    }
    @Test fun repeatedFreshOwnersUpdateSameBoundedRecord() = runBlocking {
        repeat(5) { i ->
            val owner=PageSettingsPersistence(FilePageReaderSettingsStore(directory),clock=::pagePreferenceTime)
            owner.awaitLoaded()
            if(i>0) assertEquals(PageReadingMode.entries[(i-1)%4],owner.settings.value.mode)
            owner.submit(owner.claimReader(),PageReaderSettings(PageReadingMode.entries[i%4]));owner.close();owner.awaitClosed()
        }
        assertEquals(PageReadingMode.PAGED_RTL,FilePageReaderSettingsStore(directory).load().settings.mode)
        assertEquals(2L,Files.list(directory).use{it.count()})
    }
    @Test fun corruptionAndTruncationFallBackToDefaults() = runBlocking {
        assertTrue(FilePageReaderSettingsStore(directory).save(PageReaderPreferences(settings, 1)))
        val valid = Files.readAllBytes(record)
        for (bytes in listOf(byteArrayOf(), valid.copyOf(20), valid + byteArrayOf(1), valid.copyOf().also { it[17] = 1 })) {
            Files.write(record, bytes); assertEquals(PageReaderPreferences(), FilePageReaderSettingsStore(directory).load())
        }
    }
    private fun mutateInt(offset: Int, value: Int) {
        val bytes = Files.readAllBytes(record); ByteBuffer.wrap(bytes).putInt(offset, value)
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, 24)).copyInto(bytes, 24)
        Files.write(record, bytes)
    }
    @Test fun unsupportedFutureSchemaLoadsDefaults() = runBlocking {
        FilePageReaderSettingsStore(directory).save(PageReaderPreferences(settings, 1)); mutateInt(8, 2)
        assertEquals(PageReaderPreferences(), FilePageReaderSettingsStore(directory).load())
    }
    @Test fun checksumValidInvalidModesAreRejected() = runBlocking {
        for ((offset, value) in listOf(12 to 100000, 12 to -1, 12 to 4)) {
            FilePageReaderSettingsStore(directory).save(PageReaderPreferences(settings, 1)); mutateInt(offset, value)
            assertEquals(PageReaderPreferences(), FilePageReaderSettingsStore(directory).load())
        }
    }
    @Test fun oversizedPersistedFileIsRejectedBeforeAllocation() = runBlocking {
        Files.createDirectories(directory); Files.write(record, ByteArray(1024 * 1024))
        assertEquals(PageReaderPreferences(), FilePageReaderSettingsStore(directory).load())
    }
    @Test fun failedAtomicCommitPreservesPreviousRecordAndReportsFalse() = runBlocking {
        val first = PageReaderPreferences(settings, 1); FilePageReaderSettingsStore(directory).save(first)
        val failing = FilePageReaderSettingsStore(directory, beforeCommit = { throw AtomicMoveNotSupportedException("", "", "") })
        assertFalse(failing.save(PageReaderPreferences(settings.copy(mode = PageReadingMode.PAGED_LTR), 2)))
        assertEquals(first, FilePageReaderSettingsStore(directory).load())
        assertFalse(Files.list(directory).use { it.anyMatch { p -> p.toString().endsWith(".part") } })
    }
    @Test fun failedWriteCannotBeRestoredFromRamByCompletelyNewOwner() = runBlocking {
        val failed = PageSettingsPersistence(FilePageReaderSettingsStore(directory, beforeCommit = { error("private detail") }))
        failed.awaitLoaded(); failed.submit(failed.claimReader(), settings); failed.close(); failed.awaitClosed()
        assertTrue(failed.saveFailed.value); assertFalse(Files.exists(record))
        val fresh = PageSettingsPersistence(FilePageReaderSettingsStore(directory))
        try { fresh.awaitLoaded(); assertEquals(PageReaderSettings(), fresh.settings.value) }
        finally { fresh.close(); fresh.awaitClosed() }
    }
    @Test fun cancelledCommitLeavesPreviousDurableChoiceAndNoTemp() = runBlocking {
        val first = PageReaderPreferences(settings, 1); FilePageReaderSettingsStore(directory).save(first)
        val cancelling = FilePageReaderSettingsStore(directory, beforeCommit = { throw CancellationException() })
        assertFailsWith<CancellationException> { cancelling.save(PageReaderPreferences(settings.copy(mode = PageReadingMode.VERTICAL), 2)) }
        assertEquals(first, FilePageReaderSettingsStore(directory).load())
        assertFalse(Files.list(directory).use { it.anyMatch { p -> p.toString().endsWith(".part") } })
    }
    @Test fun olderDrainingOwnerCannotReplaceNewerCommittedChoice() = runBlocking {
        FilePageReaderSettingsStore(directory).save(PageReaderPreferences(settings, 20))
        assertTrue(FilePageReaderSettingsStore(directory).save(PageReaderPreferences(settings.copy(mode = PageReadingMode.PAGED_RTL), 10)))
        assertEquals(settings, FilePageReaderSettingsStore(directory).load().settings)
    }
    @Test fun unavailableAndRelativePathsFailSafely() = runBlocking {
        for (path in listOf(null, Path.of("relative-preferences"))) {
            val store = FilePageReaderSettingsStore(path)
            assertEquals(PageReaderPreferences(), store.load()); assertFalse(store.save(PageReaderPreferences(settings, 1)))
        }
    }
    @Test fun unrelatedFilesSurviveAndOnlyOwnedStaleTempsAreRemoved() = runBlocking {
        Files.createDirectories(directory)
        val unrelated = directory.resolve("unrelated.part"); Files.write(unrelated, byteArrayOf(42))
        val stale = directory.resolve("t-12345678-1234-1234-1234-123456789abc.part"); Files.write(stale, byteArrayOf(1))
        assertTrue(FilePageReaderSettingsStore(directory).save(PageReaderPreferences(settings, 1)))
        assertTrue(Files.exists(unrelated)); assertFalse(Files.exists(stale))
    }
    @Test fun recordSymlinkCannotReadOrReplaceOutsidePublication() = runBlocking {
        Files.createDirectories(directory)
        val outside = root.resolve("outside"); Files.write(outside, byteArrayOf(42))
        Files.createSymbolicLink(record, outside)
        val store = FilePageReaderSettingsStore(directory)
        assertEquals(PageReaderPreferences(), store.load()); assertFalse(store.save(PageReaderPreferences(settings, 1)))
        assertContentEquals(byteArrayOf(42), Files.readAllBytes(outside))
    }
}
