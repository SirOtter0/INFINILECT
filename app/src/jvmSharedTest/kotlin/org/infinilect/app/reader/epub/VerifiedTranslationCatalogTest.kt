// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.serialization.json.*
import java.net.URI
import kotlin.test.*

/** Independent pinned upstream metadata, not a live-provider or native-inference test. */
class VerifiedTranslationCatalogTest {
    private fun registry() = javaClass.getResourceAsStream("/translation/foxlet-registry-supported.json")!!.use {
        Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
    }
    @Test fun allFourDirectionalDescriptorsMatchThePinnedProviderRegistryExactly() {
        val registry=registry();val models=registry.getValue("models").jsonArray
        assertEquals(4,foxletVerifiedModels.size)
        assertEquals(4,foxletVerifiedModels.map{it.pair}.toSet().size)
        assertEquals(setOf(TranslationPair("en","es"),TranslationPair("es","en"),TranslationPair("en","ca"),TranslationPair("ca","en")),foxletVerifiedModels.map{it.pair}.toSet())
        for(model in foxletVerifiedModels) {
            val entry=models.single {it.jsonObject.getValue("from").jsonPrimitive.content==model.pair.source&&it.jsonObject.getValue("to").jsonPrimitive.content==model.pair.target}.jsonObject
            val files=entry.getValue("files").jsonArray.map{it.jsonObject}
            assertEquals(model.bytes,files.sumOf{it.getValue("size").jsonPrimitive.long})
            val assets=files.associateBy{it.getValue("type").jsonPrimitive.content}
            assertEquals(setOf("model","lex","vocab"),assets.keys)
            val revision=entry.getValue("version").jsonPrimitive.content+":"+files.sortedBy{it.getValue("name").jsonPrimitive.content}.joinToString(","){it.getValue("sha256").jsonPrimitive.content}
            assertEquals(revision,model.revision)
            for(file in files) {
                assertTrue(Regex("[a-f0-9]{64}").matches(file.getValue("sha256").jsonPrimitive.content))
                val uri=URI(file.getValue("url").jsonPrimitive.content)
                assertEquals("https",uri.scheme);assertEquals("firefox-settings-attachments.cdn.mozilla.net",uri.host)
                assertNull(uri.rawUserInfo);assertNull(uri.rawQuery)
            }
        }
    }
    @Test fun spanishCatalanDirectModelsDoNotExistInThePinnedRegistryAndAreNeverInvented() {
        val pairs=registry().getValue("availableDirections").jsonArray.map{it.jsonPrimitive.content}
        assertEquals(pairs.size,pairs.toSet().size)
        assertFalse("es-ca" in pairs);assertFalse("ca-es" in pairs)
        assertTrue(foxletVerifiedModels.none {it.pair==TranslationPair("es","ca")||it.pair==TranslationPair("ca","es")})
    }
}
