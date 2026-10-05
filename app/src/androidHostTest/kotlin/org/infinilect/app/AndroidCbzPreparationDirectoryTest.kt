// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AndroidCbzPreparationDirectoryTest {
    @Test fun preparedArchivesUseThePrivateEvictableCacheNotPersistentFiles() {
        val cache = File("/private/app/cache")
        val files = File("/private/app/files")
        assertEquals(File(cache, "cbz-preparation-v1").toPath(), androidCbzPreparationDirectory(cache))
        assertNotEquals(File(files, "cbz-preparation-v1").toPath(), androidCbzPreparationDirectory(cache))
    }
}
