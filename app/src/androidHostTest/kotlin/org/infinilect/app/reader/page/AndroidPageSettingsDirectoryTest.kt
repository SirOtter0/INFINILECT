// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*
import org.infinilect.app.androidPageSettingsDirectory
import org.infinilect.app.createApplicationSources

class AndroidPageSettingsDirectoryTest {
    @Test fun freshAndroidApplicationOwnersRestorePersistentSettingsAfterCacheDeletion() = runBlocking {
        val root = Files.createTempDirectory("android-preferences-owner")
        val cache = root.resolve("cache").toFile(); val files = root.resolve("files").toFile()
        val expected = PageReaderSettings(PageReadingMode.WEBTOON)
        try {
            val first = createApplicationSources(cache, files)
            try {
                val preferences = assertNotNull(first.pageSettings)
                preferences.awaitLoaded(); preferences.submit(preferences.claimReader(), expected)
            } finally { first.close(); first.awaitProgressClosed() }
            val record = androidPageSettingsDirectory(files).resolve(PAGE_SETTINGS_RECORD_NAME)
            assertTrue(Files.isRegularFile(record)); assertEquals(files.toPath().resolve(PAGE_SETTINGS_DIRECTORY_NAME), record.parent)
            cache.mkdirs(); cache.resolve("disposable").writeText("cache"); cache.deleteRecursively()
            repeat(2) {
                val next = createApplicationSources(cache, files)
                try { val preferences = assertNotNull(next.pageSettings); preferences.awaitLoaded(); assertEquals(expected, preferences.settings.value) }
                finally { next.close(); next.awaitProgressClosed() }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
