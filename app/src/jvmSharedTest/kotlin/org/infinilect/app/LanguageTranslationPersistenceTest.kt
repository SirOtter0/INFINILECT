// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import java.nio.file.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.infinilect.app.ui.*
import org.infinilect.app.reader.epub.*

class LanguageTranslationPersistenceTest {
    @Test fun interfaceAndReadingLanguagesPersistIndependentlyWithoutLosingProfileOrDiscovery() = runBlocking {
        val root = Files.createTempDirectory("language-preferences")
        try {
            for (language in interfaceLanguages) {
                val value = ApplicationPreferences(ApplicationThemeMode.DARK, LocalProfile("Ana", "ES", language, true),
                    org.infinilect.app.discovery.DiscoveryPreferences(homeEnabled = true), if (language == "es") "en" else "es")
                assertTrue(FileApplicationAppearanceStore(root).savePreferences(value))
                assertEquals(value, FileApplicationAppearanceStore(root).loadPreferences())
            }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun legacyProfileLanguageIsNotOverwrittenAndDefaultReadingFollowsInterface() = runBlocking {
        val root = Files.createTempDirectory("legacy-language")
        try {
            val old = ApplicationPreferences(profile = LocalProfile("Ana", "ES", "en", true))
            assertTrue(FileApplicationAppearanceStore(root).savePreferences(old))
            val loaded = FileApplicationAppearanceStore(root).loadPreferences()
            assertEquals(old, loaded); assertNull(loaded.readingLanguage)
            assertEquals("en", resolveInterfaceLanguage(loaded.profile.interfaceLanguage, "es"))
            assertEquals("ca", resolveInterfaceLanguage(null, "ca-ES")); assertEquals("en", resolveInterfaceLanguage(null, "xx"))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun previousEpubTargetSurvivesMigrationWhileNewPreferencesUseTheApplicationDefault() = runBlocking {
        val root = Files.createTempDirectory("legacy-epub-target")
        try {
            val store = FileEpubReaderSettingsStore(root)
            assertTrue(store.save(EpubReaderPreferences(EpubReaderSettings(translationTarget = "es"), 123)))
            val file = root.resolve(EPUB_SETTINGS_RECORD_NAME); val bytes = Files.readAllBytes(file)
            ByteBuffer.wrap(bytes).putInt(8, 2)
            MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, 48)).copyInto(bytes, 48); Files.write(file, bytes)
            assertEquals("es", FileEpubReaderSettingsStore(root).load().settings.translationTarget)
            assertEquals(123L, FileEpubReaderSettingsStore(root).load().updatedAtEpochMillis)
            assertEquals("default", EpubReaderSettings().translationTarget)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun retentionRecordsRecoverCorruptionAndDoNotDeleteOtherPreferences() = runBlocking {
        val root = Files.createTempDirectory("model-retention")
        try {
            val unrelated = root.resolve("other.preferences"); Files.writeString(unrelated, "preserve")
            val records = mapOf(TranslationPair("en", "es") to ModelRetention(false, 1), TranslationPair("es", "en") to ModelRetention(true, 2))
            assertTrue(FileTranslationModelRecords(root).save(records))
            assertEquals(records, FileTranslationModelRecords(root).load())
            Files.writeString(root.resolve("model-retention.preferences"), "corrupt")
            assertTrue(FileTranslationModelRecords(root).load().isEmpty())
            assertEquals("preserve", Files.readString(unrelated))
            assertFalse(FileTranslationModelRecords(null).save(records))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun allThreeResourceSetsHaveMatchingKeysAndFormatArguments() {
        val base = Path.of("src/commonMain/composeResources")
        fun read(folder: String): Map<String, String> {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val doc = factory.newDocumentBuilder().parse(base.resolve(folder).resolve("strings.xml").toFile())
            val nodes = doc.getElementsByTagName("string")
            val map = mutableMapOf<String, String>()
            for (i in 0 until nodes.length) {
                val n = nodes.item(i) as org.w3c.dom.Element
                assertNull(map.put(n.getAttribute("name"), n.textContent)); assertTrue(n.textContent.isNotBlank())
            }
            return map
        }
        val english = read("values")
        assertEquals(UiText.entries.map { it.name.lowercase() }.toSet(), english.keys)
        for (folder in listOf("values-es", "values-ca")) {
            val translated = read(folder); assertEquals(english.keys, translated.keys)
            val placeholders = Regex("%[0-9]+\\\$s")
            for (key in english.keys) assertEquals(placeholders.findAll(english.getValue(key)).map { it.value }.toList(),
                placeholders.findAll(translated.getValue(key)).map { it.value }.toList(), key)
        }
    }
}
