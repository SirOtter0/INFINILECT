// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import java.io.File
import kotlin.test.*

class AndroidCollectionsPathTest {
    @Test fun usesFixedNameInPrivateDatabasesStorageNotCacheOrProgress() {
        val privateDirectory=File("/data/user/0/org.infinilect.app/databases")
        val file=androidCollectionsFile(privateDirectory)
        assertEquals(File(privateDirectory,"infinilect-collections.sqlite"),file)
        assertTrue(file.isAbsolute);assertFalse(file.toPath().startsWith(File("/data/user/0/org.infinilect.app/cache").toPath()))
        assertFalse(file.toPath().startsWith(File("/data/user/0/org.infinilect.app/files/reading-progress-v1").toPath()))
    }
    @Test fun cacheProgressAndRelativeLocationsAreRejected() {
        for (path in listOf("/data/user/0/org.infinilect.app/cache", "/data/user/0/org.infinilect.app/files/reading-progress-v1", "databases"))
            assertFailsWith<IllegalArgumentException> { androidCollectionsFile(File(path)) }
    }
}
