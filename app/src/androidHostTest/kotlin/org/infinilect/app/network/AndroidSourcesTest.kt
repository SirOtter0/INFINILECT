// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import io.ktor.client.engine.android.AndroidEngineConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import kotlin.test.*
import org.infinilect.app.createApplicationSources
import org.infinilect.app.resultKey
import org.infinilect.core.PublicationId
import org.infinilect.core.SourceId

class AndroidSourcesTest {
    @Test fun resultKeysCanBeSerializedForAndroidBundleSaveability() {
        val key = PublicationId(SourceId("internet-archive"), "gmb-2015-93040").resultKey()
        assertIs<Serializable>(key)
        val bytes = ByteArrayOutputStream().use { buffer ->
            ObjectOutputStream(buffer).use { it.writeObject(key) }
            buffer.toByteArray()
        }
        ObjectInputStream(ByteArrayInputStream(bytes)).use { assertEquals(key, it.readObject()) }
    }
    @Test fun usesAndroidEngineWithBoundedTransportTimeouts() {
        val engine = platformHttpEngine()
        try {
            assertEquals("AndroidClientEngine",engine::class.java.simpleName)
            val config = assertIs<AndroidEngineConfig>(engine.config)
            assertEquals(5_000,config.connectTimeout)
            assertEquals(15_000,config.socketTimeout)
        } finally { engine.close() }
    }
    @Test fun createsSessionSourcesWithoutNetworkAndCanDisposeTwice() {
        val directory = java.nio.file.Files.createTempDirectory("infinilect-android-sources")
        val sources = createApplicationSources(directory.toFile())
        try {
            assertEquals(listOf("gutenberg","internet-archive"),sources.options.map { it.source.id.value })
            assertEquals(listOf(false,true),sources.options.map { it.textReadingEnabled })
            assertEquals("INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)",PROJECT_USER_AGENT)
        } finally { sources.close(); sources.close(); directory.toFile().deleteRecursively() }
    }
}
