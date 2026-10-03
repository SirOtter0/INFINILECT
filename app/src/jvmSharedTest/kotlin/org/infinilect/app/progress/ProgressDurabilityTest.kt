// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.cache.CACHE_DIRECTORY_NAME
import org.infinilect.app.progress.ProgressStorageFailure.Operation
import org.infinilect.app.progress.ProgressStorageFailure.Stage
import org.infinilect.app.progress.ProgressStorageFailure.Reason
import org.infinilect.core.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProgressDurabilityTest {
    private lateinit var root: Path
    private val id = ReadingProgressId(PublicationId(SourceId("internet-archive"), "fixture"), "text", PublicationFormat.TEXT)
    private fun progress(time: Long = 1, offset: Long = 4) = ReadingProgress(id, ReadingLocator.Text(offset, 10), offset / 10.0, time)
    private val directory get() = root.resolve(PROGRESS_DIRECTORY_NAME)
    private fun committedFiles() = Files.list(directory).use { files ->
        files.filter { it.fileName.toString().matches(Regex("p-[a-f0-9]{64}\\.progress")) }.toList()
    }
    private fun TestScope.store(
        permissions: (Path, Boolean) -> Unit = ::privateProgressPermissions,
        diagnostics: (ProgressStorageFailure) -> Unit = {},
        beforeCommit: () -> Unit = {},
    ) = FileReadingProgressStore(directory, dispatcher = StandardTestDispatcher(testScheduler),
        setPrivatePermissions = permissions, onFailure = diagnostics, beforeCommit = beforeCommit)
    private fun TestScope.persistence(store: ReadingProgressStore) =
        ProgressPersistence(store, StandardTestDispatcher(testScheduler))

    @BeforeTest fun setup() { root = Files.createTempDirectory("infinilect-progress-durable") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun androidFileStoreSecurityFailureIsDiagnosedBeforeAnyCommit() = runTest {
        val failures = mutableListOf<ProgressStorageFailure>()
        // Official Android libcore's getFileStore() throws exactly this exception.
        val legacyProbe: (Path, Boolean) -> Unit = { _, _ -> throw SecurityException("getFileStore") }
        val rejected = store(legacyProbe, failures::add)
        assertFalse(rejected.save(progress()))
        assertEquals(listOf(ProgressStorageFailure(Operation.SAVE, Stage.DIRECTORY_PERMISSIONS, Reason.SECURITY)), failures)
        assertTrue(committedFiles().isEmpty())
        assertNull(store().get(id))
    }

    @Test fun pathAttributePermissionsCommitDirectoryLockAndRecordWithoutFileStoreProbe() = runTest {
        val visited = mutableListOf<Pair<Path, Boolean>>()
        val failures = mutableListOf<ProgressStorageFailure>()
        val corrected = store(permissions = { path, isDirectory ->
            visited += path to isDirectory
            privateProgressPermissions(path, isDirectory)
        }, diagnostics = failures::add)
        assertTrue(corrected.save(progress()))
        assertTrue(failures.isEmpty())
        assertEquals(listOf(true, false, false), visited.map { it.second })
        assertEquals(directory, visited.first().first)
        assertEquals(".progress.lock", visited[1].first.fileName.toString())
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(committedFiles().single()))
        assertEquals(progress(), store().get(id))
    }

    @Test fun androidLikeProviderRejectsOldProbeButRealSaveAndRestartUseNoFileStoreQueries() = runTest {
        val filesystem = AndroidLikeFileSystem(directory.fileSystem)
        val androidPath = filesystem.wrap(directory)
        assertFailsWith<SecurityException> { Files.getFileStore(androidPath) }
        val failures = mutableListOf<ProgressStorageFailure>()
        fun freshStore() = FileReadingProgressStore(androidPath, dispatcher = StandardTestDispatcher(testScheduler),
            onFailure = failures::add)
        suspend fun discardOwner() {
            val owner = persistence(freshStore())
            owner.submit(progress()); owner.close(); owner.awaitClosed()
            assertFalse(owner.saveFailed.value)
        }
        discardOwner()
        assertEquals(1, committedFiles().size)
        val restarted = persistence(freshStore())
        try { assertEquals(progress(), restarted.get(id)) }
        finally { restarted.close(); restarted.awaitClosed() }
        assertTrue(failures.isEmpty())
        assertEquals(1, filesystem.fileStoreQueries) // Only the deliberate old-API probe above.
    }

    @Test fun newWriterAndNewStoreRestoreOnlyActualCommittedProgressAfterRestart() = runTest {
        suspend fun saveAndDiscardOwner() {
            val owner = persistence(store())
            owner.submit(progress())
            owner.close(); owner.awaitClosed()
            assertFalse(owner.saveFailed.value)
            assertEquals(1, committedFiles().size)
        }
        saveAndDiscardOwner() // No writer/recent map or store instance escapes this scope.
        val restarted = persistence(store())
        try { assertEquals(progress(), restarted.get(id)) }
        finally { restarted.close(); restarted.awaitClosed() }
    }

    @Test fun recentRamRestoreCannotMasqueradeAsDurableSave() = runTest {
        val failed = persistence(store(permissions = { _, _ -> throw SecurityException("getFileStore") }))
        failed.submit(progress()); runCurrent()
        assertTrue(failed.saveFailed.value)
        assertEquals(progress(), failed.get(id)) // Explicitly RAM-only, not proof of persistence.
        failed.close(); failed.awaitClosed()
        assertTrue(committedFiles().isEmpty())
        val restarted = persistence(store())
        try { assertNull(restarted.get(id)) }
        finally { restarted.close(); restarted.awaitClosed() }
    }

    @Test fun laterDurableSaveClearsFailureAndSurvivesNewWriter() = runTest {
        var failPermissions = true
        val writer = persistence(store(permissions = { path, isDirectory ->
            if (failPermissions) throw SecurityException("getFileStore")
            privateProgressPermissions(path, isDirectory)
        }))
        writer.submit(progress()); runCurrent(); assertTrue(writer.saveFailed.value)
        failPermissions = false
        val newer = progress(time = 2, offset = 8)
        writer.submit(newer); runCurrent(); assertFalse(writer.saveFailed.value)
        writer.close(); writer.awaitClosed(); assertEquals(1, committedFiles().size)
        val restarted = persistence(store())
        try { assertEquals(newer, restarted.get(id)) }
        finally { restarted.close(); restarted.awaitClosed() }
    }

    @Test fun cacheDeletionDoesNotAffectNewWriterDiskRestoration() = runTest {
        val cache = root.resolve(CACHE_DIRECTORY_NAME)
        Files.createDirectories(cache); Files.write(cache.resolve("disposable"), byteArrayOf(1))
        val owner = persistence(store()); owner.submit(progress()); owner.close(); owner.awaitClosed()
        cache.toFile().deleteRecursively()
        val restarted = persistence(store())
        try { assertEquals(progress(), restarted.get(id)); assertEquals(1, committedFiles().size) }
        finally { restarted.close(); restarted.awaitClosed() }
    }

    @Test fun repeatedOwnersCommitAndRestoreWithoutRetainedMemory() = runTest {
        suspend fun recreate(time: Long) {
            val owner = persistence(store())
            if (time > 1) assertEquals(progress(time - 1, time - 1), owner.get(id))
            owner.submit(progress(time, time)); owner.close(); owner.close(); owner.awaitClosed()
            assertFalse(owner.saveFailed.value); assertEquals(1, committedFiles().size)
        }
        for (time in 1L..4L) recreate(time)
        assertEquals(progress(4, 4), store().get(id))
    }

    @Test fun atomicCommitFailureIsDiagnosedAndKeepsPreviousDurableRecord() = runTest {
        assertTrue(store().save(progress()))
        val failures = mutableListOf<ProgressStorageFailure>()
        assertFalse(store(diagnostics = failures::add, beforeCommit = { throw IOException("private path omitted") })
            .save(progress(2, 8)))
        assertEquals(listOf(ProgressStorageFailure(Operation.SAVE, Stage.COMMIT, Reason.IO)), failures)
        assertEquals(progress(), store().get(id))
        assertEquals(1, committedFiles().size)
    }

    @Test fun diagnosticObserverCannotChangeSuccessfulPersistence() = runTest {
        val failingObserver = store(diagnostics = { error("observer") })
        assertTrue(failingObserver.save(progress()))
        Files.write(committedFiles().single(), byteArrayOf(1))
        assertNull(failingObserver.get(id))
        assertTrue(failingObserver.save(progress(2, 8)))
        assertEquals(progress(2, 8), store().get(id))
    }
}
