// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*
import org.infinilect.app.androidEpubSettingsDirectory
import org.infinilect.app.createApplicationSources

class AndroidEpubSettingsDirectoryTest {
    @Test fun freshAndroidApplicationOwnersRestorePersistentSettingsAfterCacheDeletion() = runBlocking {
        val root = Files.createTempDirectory("android-preferences-owner")
        val cache = root.resolve("cache").toFile(); val files = root.resolve("files").toFile()
        val expected = EpubReaderSettings(30, 200, 40, EpubReadingTheme.LIGHT)
        try {
            val first = createApplicationSources(cache, files)
            try {
                val preferences = assertNotNull(first.epubSettings)
                preferences.awaitLoaded(); preferences.submit(preferences.claimReader(), expected)
            } finally { first.close(); first.awaitProgressClosed() }
            val record = androidEpubSettingsDirectory(files).resolve(EPUB_SETTINGS_RECORD_NAME)
            assertTrue(Files.isRegularFile(record)); assertEquals(files.toPath().resolve(EPUB_SETTINGS_DIRECTORY_NAME), record.parent)
            cache.mkdirs(); cache.resolve("disposable").writeText("cache"); cache.deleteRecursively()
            repeat(2) {
                val next = createApplicationSources(cache, files)
                try { val preferences = assertNotNull(next.epubSettings); preferences.awaitLoaded(); assertEquals(expected, preferences.settings.value) }
                finally { next.close(); next.awaitProgressClosed() }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
