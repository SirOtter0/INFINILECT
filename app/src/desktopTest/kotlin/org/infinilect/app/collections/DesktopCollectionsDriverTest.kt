// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import java.nio.file.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.infinilect.app.progress.desktopProgressDirectory
import org.infinilect.core.*

class DesktopCollectionsDriverTest {
    @Test fun usesPersistentUserBaseButNeverProgressOrCacheDirectory() {
        val progress=desktopProgressDirectory("Linux","/users/reader",mapOf("XDG_DATA_HOME" to "/data"))
        assertEquals(Path.of("/data/org.infinilect.app/infinilect-collections.sqlite"),desktopCollectionsFile(progress))
        assertFalse(assertNotNull(desktopCollectionsFile(progress)).startsWith(assertNotNull(progress)))
        assertNull(desktopCollectionsFile(Path.of("relative")));assertNull(desktopCollectionsFile(null))
    }
    @Test fun relativeEnvironmentPathUsesSafeFallbackOrDisablesPersistence() {
        assertEquals(Path.of("/user/.local/share/org.infinilect.app/infinilect-collections.sqlite"),
            desktopCollectionsFile(desktopProgressDirectory("Linux","/user",mapOf("XDG_DATA_HOME" to "relative"))))
        assertNull(desktopCollectionsFile(desktopProgressDirectory("Linux","relative",emptyMap())))
    }
    @Test fun productionDriverAndNewStoreRestoreDurableLibraryAndHistory()=runTest {
        val root=Files.createTempDirectory("infinilect-desktop-driver");val file=root.resolve(COLLECTIONS_DATABASE_NAME)
        val id=PublicationId(SourceId("fixture"),"1");val snapshot=PublicationSnapshot(id,"Title",PublicationType.BOOK,listOf("A, B","作者"))
        try {
            suspend fun discardOwner() {
                val store=SqlCollectionsStore({desktopCollectionsDriver(file)})
                assertIs<LocalStoreResult.Success<*>>(store.library.put(snapshot,1));assertIs<LocalStoreResult.Success<*>>(store.history.recordOpened(snapshot,2));store.close()
            }
            discardOwner();assertTrue(Files.size(file)>0)
            val fresh=SqlCollectionsStore({desktopCollectionsDriver(file)})
            assertEquals(snapshot,assertIs<LocalStoreResult.Success<LibraryEntry?>>(fresh.library.get(id)).value?.publication)
            assertEquals(snapshot,assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(fresh.history.listRecent()).value.single().publication);fresh.close()
        } finally {root.toFile().deleteRecursively()}
    }
    @Test fun symlinkAndRelativeDatabasePathsAreRejected() {
        val root=Files.createTempDirectory("infinilect-driver-path")
        try {
            val outside=root.resolve("outside");Files.write(outside,byteArrayOf(1));val link=root.resolve(COLLECTIONS_DATABASE_NAME);Files.createSymbolicLink(link,outside)
            assertFailsWith<IllegalArgumentException>{desktopCollectionsDriver(link)}
            assertFailsWith<IllegalArgumentException>{desktopCollectionsDriver(Path.of("relative.sqlite"))}
            assertContentEquals(byteArrayOf(1),Files.readAllBytes(outside))
        } finally {root.toFile().deleteRecursively()}
    }
    @Test fun specialCharactersInUserDirectoryDoNotBecomeJdbcOptions()=runTest {
        val root=Files.createTempDirectory("infinilect-jdbc-encoding")
        try {
            val file=root.resolve("folder?mode=memory#作者").resolve(COLLECTIONS_DATABASE_NAME)
            val id=PublicationId(SourceId("fixture"),"1")
            val snapshot=PublicationSnapshot(id,"Title",PublicationType.BOOK)
            val first=SqlCollectionsStore({desktopCollectionsDriver(file)})
            assertIs<LocalStoreResult.Success<*>>(first.library.put(snapshot,1));first.close()
            assertTrue(Files.exists(file))
            val reopened=SqlCollectionsStore({desktopCollectionsDriver(file)})
            assertEquals(snapshot,assertIs<LocalStoreResult.Success<LibraryEntry?>>(reopened.library.get(id)).value?.publication);reopened.close()
        } finally { root.toFile().deleteRecursively() }
    }
}
