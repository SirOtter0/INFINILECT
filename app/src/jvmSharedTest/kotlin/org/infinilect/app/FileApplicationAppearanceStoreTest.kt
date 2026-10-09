// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import java.nio.file.*
import kotlinx.coroutines.test.runTest
import org.infinilect.app.ui.*
import kotlin.test.*

class FileApplicationAppearanceStoreTest {
    @Test fun appearanceRoundTripsWithoutChangingOtherPreferences() = runTest {
        val root = Files.createTempDirectory("appearance-test")
        try {
            val directory = root.resolve(APPLICATION_APPEARANCE_DIRECTORY)
            val other = root.resolve("existing-reader.preferences"); Files.write(other, byteArrayOf(1,2,3))
            val store = FileApplicationAppearanceStore(directory)
            assertEquals(ApplicationThemeMode.SYSTEM, store.load())
            for (choice in ApplicationThemeMode.entries) {
                assertTrue(store.save(choice)); assertEquals(choice, FileApplicationAppearanceStore(directory).load())
                assertEquals(40L, Files.size(directory.resolve("appearance.preferences")))
                Files.list(directory).use { assertEquals(1L, it.count()) }
            }
            assertContentEquals(byteArrayOf(1,2,3), Files.readAllBytes(other))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun malformedOversizedAndSymlinkRecordsAreRejectedSafely() = runTest {
        val root = Files.createTempDirectory("appearance-invalid")
        try {
            val store = FileApplicationAppearanceStore(root)
            val record = root.resolve("appearance.preferences")
            Files.write(record, ByteArray(4096)); assertEquals(ApplicationThemeMode.SYSTEM, store.load())
            assertTrue(store.save(ApplicationThemeMode.DARK))
            val bytes = Files.readAllBytes(record); bytes[4] = 50; Files.write(record, bytes)
            assertEquals(ApplicationThemeMode.SYSTEM, store.load())
            Files.delete(record); val outside = root.resolve("other"); Files.write(outside, byteArrayOf(7))
            Files.createSymbolicLink(record, outside)
            assertEquals(ApplicationThemeMode.SYSTEM, store.load()); assertFalse(store.save(ApplicationThemeMode.DARK))
            assertContentEquals(byteArrayOf(7), Files.readAllBytes(outside))
            assertFalse(FileApplicationAppearanceStore(null).save(ApplicationThemeMode.DARK))
        } finally { root.toFile().deleteRecursively() }
    }
}
