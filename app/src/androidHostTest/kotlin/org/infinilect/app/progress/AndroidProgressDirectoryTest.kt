// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import java.io.File
import kotlin.test.*
import org.infinilect.app.androidProgressDirectory
import org.infinilect.app.androidCacheDirectory
import org.infinilect.app.createApplicationSources
import kotlinx.coroutines.test.runTest
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
            assertTrue(Files.exists(root.resolve("files/reading-progress-v1")))
            assertFalse(Files.exists(root.resolve("cache")))
            assertEquals(progress,FileReadingProgressStore(root.resolve("files/reading-progress-v1")).get(id))
        } finally { sources.close(); root.toFile().deleteRecursively() }
    }
}
