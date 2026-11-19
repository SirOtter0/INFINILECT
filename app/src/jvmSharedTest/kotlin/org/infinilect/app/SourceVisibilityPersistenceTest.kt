// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import java.io.*
import java.nio.file.*
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.infinilect.app.discovery.*
import org.infinilect.app.ui.*
import kotlin.test.*

class SourceVisibilityPersistenceTest {
    /** Independent historical layouts, not records written by the new serializer. */
    @Test fun allFourHistoricalLayoutsDefaultToHiddenAndRetainExistingPreferences()=runTest {
        val root=Files.createTempDirectory("source-visibility-legacy")
        try {
            val profile=LocalProfile("Àlex 李","ES","ca",true)
            val discovery=DiscoveryPreferences(true,false,setOf(Genre.HISTORY),123)
            for(version in 1..4) {
                val output=ByteArrayOutputStream()
                DataOutputStream(output).use { data ->
                    data.writeInt(0x494E4630+version);data.writeInt(ApplicationThemeMode.DARK.ordinal)
                    if(version>=2) {data.writeBoolean(true);data.writeUTF(profile.displayName!!);data.writeUTF("ES");data.writeUTF("ca")}
                    if(version>=3) {data.writeBoolean(true);data.writeBoolean(false);data.writeLong(123);data.writeByte(1);data.writeByte(Genre.HISTORY.ordinal)}
                    if(version>=4) data.writeUTF("es")
                }
                val payload=output.toByteArray()
                Files.write(root.resolve("appearance.preferences"),payload+MessageDigest.getInstance("SHA-256").digest(payload))
                val expected=ApplicationPreferences(ApplicationThemeMode.DARK,if(version>=2)profile else LocalProfile(),
                    if(version>=3)discovery else DiscoveryPreferences(),if(version>=4)"es" else null)
                assertEquals(expected,FileApplicationAppearanceStore(root).loadPreferences(),"INF$version")
                assertFalse(expected.discovery.showCatalogOnlySources)
            }
        } finally {root.toFile().deleteRecursively()}
    }

    @Test fun toggleSurvivesRestartAndPreservesProfileReadingLanguageAndUnrelatedFiles()=runTest {
        val root=Files.createTempDirectory("source-visibility-roundtrip")
        try {
            val directory=root.resolve("preferences")
            val unrelated=root.resolve("library-history-progress.fixture");val bytes=byteArrayOf(1,3,8,21);Files.write(unrelated,bytes)
            val original=ApplicationPreferences(ApplicationThemeMode.LIGHT,LocalProfile("Álex","ES","ca",true),
                DiscoveryPreferences(true,false,setOf(Genre.FANTASY,Genre.HISTORY),123),"es")
            val store=FileApplicationAppearanceStore(directory)
            assertTrue(store.savePreferences(original))
            for(show in listOf(true,false,true)) {
                val expected=original.copy(discovery=original.discovery.copy(showCatalogOnlySources=show))
                assertTrue(store.savePreferences(expected))
                assertEquals(expected,FileApplicationAppearanceStore(directory).loadPreferences())
                val file=directory.resolve("appearance.preferences")
                assertTrue(Files.size(file)<=512)
                assertEquals(if(show)0x494E4635 else 0x494E4634,DataInputStream(Files.newInputStream(file)).use {it.readInt()})
                Files.list(directory).use {assertEquals(1L,it.count())}
                assertContentEquals(bytes,Files.readAllBytes(unrelated))
            }
        } finally {root.toFile().deleteRecursively()}
    }

    @Test fun corruptRecordAndStorageFailureNeverEnableCatalogsOrModifyAnotherFile()=runTest {
        val root=Files.createTempDirectory("source-visibility-errors")
        try {
            val store=FileApplicationAppearanceStore(root)
            assertFalse(store.loadPreferences().discovery.showCatalogOnlySources)
            assertTrue(store.savePreferences(ApplicationPreferences(discovery=DiscoveryPreferences(showCatalogOnlySources=true))))
            val file=root.resolve("appearance.preferences");val record=Files.readAllBytes(file)
            record[record.lastIndex]=(record.last()+1).toByte();Files.write(file,record)
            assertFalse(store.loadPreferences().discovery.showCatalogOnlySources)
            Files.delete(file)
            val outside=root.resolve("unrelated");Files.write(outside,byteArrayOf(4))
            Files.createSymbolicLink(file,outside)
            assertFalse(store.savePreferences(ApplicationPreferences(discovery=DiscoveryPreferences(showCatalogOnlySources=true))))
            assertContentEquals(byteArrayOf(4),Files.readAllBytes(outside))
            assertFalse(store.loadPreferences().discovery.showCatalogOnlySources)
        } finally {root.toFile().deleteRecursively()}
    }
}
