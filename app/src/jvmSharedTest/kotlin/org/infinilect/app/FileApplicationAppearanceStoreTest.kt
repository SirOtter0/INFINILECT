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
    @Test fun legacyAppearanceMigratesInPlaceWithoutChangingReaderOrCollectionFiles()=runTest {
        val root=Files.createTempDirectory("profile-migration")
        try {
            val directory=root.resolve(APPLICATION_APPEARANCE_DIRECTORY)
            val store=FileApplicationAppearanceStore(directory)
            val original=root.resolve("existing-reader-and-library.fixture");Files.write(original,byteArrayOf(2,5,8))
            assertTrue(store.save(ApplicationThemeMode.DARK));assertEquals(40L,Files.size(directory.resolve("appearance.preferences")))
            val legacy=store.loadPreferences();assertEquals(ApplicationThemeMode.DARK,legacy.mode)
            assertEquals(LocalProfile(),legacy.profile)
            val profile=LocalProfile("Álex","ES","en",true)
            assertTrue(store.savePreferences(legacy.copy(profile=profile)))
            assertEquals(ApplicationPreferences(ApplicationThemeMode.DARK,profile),FileApplicationAppearanceStore(directory).loadPreferences())
            assertTrue(Files.size(directory.resolve("appearance.preferences"))<=512)
            assertTrue(store.save(ApplicationThemeMode.LIGHT));assertEquals(profile,store.loadPreferences().profile)
            assertContentEquals(byteArrayOf(2,5,8),Files.readAllBytes(original))
            Files.list(directory).use{assertEquals(1L,it.count())}
        }finally{root.toFile().deleteRecursively()}
    }
    @Test fun deferredSetupAndMaximumUnicodeNameSurviveRestartWithinRecordBudget()=runTest {
        val root=Files.createTempDirectory("profile-bounds")
        try {
            val store=FileApplicationAppearanceStore(root)
            val skipped=ApplicationPreferences(profile=LocalProfile(setupHandled=true))
            assertTrue(store.savePreferences(skipped));assertEquals(skipped,store.loadPreferences())
            assertNull(store.loadPreferences().profile.countryCode)
            val maximum=skipped.copy(profile=LocalProfile("漢".repeat(80),"JP","en",true))
            assertTrue(store.savePreferences(maximum));assertEquals(maximum,store.loadPreferences())
            val path=root.resolve("appearance.preferences");assertTrue(Files.size(path)<=512)
            val bytes=Files.readAllBytes(path);bytes[bytes.lastIndex]=(bytes.last()+1).toByte();Files.write(path,bytes)
            assertEquals(ApplicationPreferences(),store.loadPreferences())
            Files.write(path,ByteArray(513));assertEquals(ApplicationPreferences(),store.loadPreferences())
        }finally{root.toFile().deleteRecursively()}
    }

}
