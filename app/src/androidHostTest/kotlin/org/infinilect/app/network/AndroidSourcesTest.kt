// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
    @Test fun androidDemosRequireAnExplicitDeveloperRequestAndAreNeverEnabledByDebugAlone() {
        assertFalse(org.infinilect.app.developerSourcesEnabled(true, false))
        assertFalse(org.infinilect.app.developerSourcesEnabled(false, true))
        assertFalse(org.infinilect.app.developerSourcesEnabled(false, false))
        assertTrue(org.infinilect.app.developerSourcesEnabled(true, true))
    }
    @Test fun resultKeysCanBeSerializedForAndroidBundleSaveability() {
        val key = PublicationId(SourceId("internet-archive"), "gmb-2015-93040").resultKey()
        assertIs<Serializable>(key)
        val bytes = ByteArrayOutputStream().use { buffer ->
            ObjectOutputStream(buffer).use { it.writeObject(key) }
            buffer.toByteArray()
        }
        ObjectInputStream(ByteArrayInputStream(bytes)).use { assertEquals(key, it.readObject()) }
    }
    @Test fun usesCancellableAndroidEngineWithBoundedTransportTimeouts() = runBlocking {
        val engine = platformHttpEngine()
        val config = assertIs<OkHttpConfig>(engine.config)
        var native: OkHttpClient? = null
        // Observe the real builder through its public hook; do not mirror constants.
        config.config { native = build() }
        config.addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Original fixture").body("original".toResponseBody()).build()
        }
        val client = HttpClient(engine) { followRedirects = false; install(HttpTimeout) }
        try {
            assertEquals("OkHttpEngine", engine::class.java.simpleName)
            client.prepareGet("https://example.invalid/original-fixture").execute { assertEquals(200, it.status.value) }
            val applied = assertNotNull(native)
            assertEquals(5_000, applied.connectTimeoutMillis)
            assertEquals(15_000, applied.readTimeoutMillis)
            assertEquals(15_000, applied.writeTimeoutMillis)
            assertFalse(applied.followRedirects); assertFalse(applied.followSslRedirects)
        } finally { client.close(); engine.close() }
    }
    @Test fun createsSessionSourcesWithoutNetworkAndCanDisposeTwice() {
        val directory = java.nio.file.Files.createTempDirectory("infinilect-android-sources")
        val sources = createApplicationSources(directory.toFile())
        try {
            assertEquals(listOf("internet-archive","gutenberg","local-imports","wikisource-es","wikisource-en","wikisource-ca"),sources.options.map { it.source.id.value })
            assertEquals(listOf(true,false,true,false,false,false),sources.options.map { it.textReadingEnabled })
            assertEquals(listOf(true,false,true,true,true,true),sources.options.map { it.supportsIntegratedReading })
            assertEquals(listOf("internet-archive","local-imports","wikisource-es","wikisource-en","wikisource-ca"),sources.options.filter { it.visibleInCatalogs(false) }.map { it.source.id.value })
            assertEquals(sources.options,sources.options.filter { it.visibleInCatalogs(true) })
            assertEquals("INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)",PROJECT_USER_AGENT)
        } finally { sources.close(); sources.close(); directory.toFile().deleteRecursively() }
    }
}
