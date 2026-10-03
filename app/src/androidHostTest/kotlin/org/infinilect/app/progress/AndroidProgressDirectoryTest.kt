// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import java.io.File
import kotlin.test.*
import org.infinilect.app.androidProgressDirectory
import org.infinilect.app.androidCacheDirectory
import org.infinilect.app.createApplicationSources
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import org.infinilect.core.*

class AndroidProgressDirectoryTest {
    @Test fun usesPrivateFilesPathAndNeverPrivateCachePath() {
        val files=File("/data/user/0/org.infinilect.app/files")
        val cache=File("/data/user/0/org.infinilect.app/cache")
        assertEquals(files.toPath().resolve("reading-progress-v1"),androidProgressDirectory(files))
        assertNotEquals(androidCacheDirectory(cache),androidProgressDirectory(files))
        assertFalse(androidProgressDirectory(files).startsWith(cache.toPath()))
    }
    @Test fun platformFactoryWritesProgressUnderFilesAndDoesNotCreateCache() = runTest {
        val root=Files.createTempDirectory("infinilect-android-progress")
        val sources=createApplicationSources(root.resolve("cache").toFile(),root.resolve("files").toFile())
        try {
            val id=ReadingProgressId(PublicationId(SourceId("internet-archive"),"fixture"),"text",PublicationFormat.TEXT)
            val progress=ReadingProgress(id,ReadingLocator.Text(3,10),0.3,1)
            assertNotNull(sources.progress).submit(progress)
            sources.close(); sources.awaitProgressClosed()
            assertFalse(assertNotNull(sources.progress).saveFailed.value)
            assertTrue(Files.exists(root.resolve("files/reading-progress-v1")))
            assertEquals(1L, Files.list(root.resolve("files/reading-progress-v1")).use { entries ->
                entries.filter { it.fileName.toString().endsWith(".progress") }.count()
            })
            assertFalse(Files.exists(root.resolve("cache")))
            assertEquals(progress,FileReadingProgressStore(root.resolve("files/reading-progress-v1")).get(id))
        } finally { sources.close(); root.toFile().deleteRecursively() }
    }

    @Test fun completelyNewAndroidApplicationOwnersRestoreCommittedFilesAfterCacheDeletion() = runTest {
        val root=Files.createTempDirectory("infinilect-android-owner-restart")
        val cache=root.resolve("cache").toFile(); val files=root.resolve("files").toFile()
        val id=ReadingProgressId(PublicationId(SourceId("internet-archive"),"fixture"),"text",PublicationFormat.TEXT)
        try {
            suspend fun owner(time: Long) {
                val sources=createApplicationSources(cache, files)
                try {
                    val persistence=assertNotNull(sources.progress)
                    if (time > 1) assertEquals(time-1, assertNotNull(withContext(Dispatchers.Default) { persistence.get(id) }).updatedAtEpochMillis)
                    persistence.submit(ReadingProgress(id,ReadingLocator.Text(time,10),time/10.0,time))
                    sources.close(); sources.awaitProgressClosed()
                    assertFalse(persistence.saveFailed.value)
                } finally { sources.close() }
            }
            owner(1); cache.mkdirs(); cache.resolve("disposable").writeText("cache only")
            assertTrue(cache.deleteRecursively())
            owner(2); owner(3)
            assertEquals(3L, assertNotNull(FileReadingProgressStore(androidProgressDirectory(files)).get(id)).updatedAtEpochMillis)
        } finally { root.toFile().deleteRecursively() }
    }
}
