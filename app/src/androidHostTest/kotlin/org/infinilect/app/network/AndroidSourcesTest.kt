// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import io.ktor.client.engine.android.AndroidEngineConfig
import kotlin.test.*
import org.infinilect.app.createApplicationSources

class AndroidSourcesTest {
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
        val sources = createApplicationSources()
        try {
            assertEquals(listOf("gutenberg","internet-archive"),sources.options.map { it.source.id.value })
            assertEquals(listOf(false,true),sources.options.map { it.textReadingEnabled })
            assertEquals("INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)",PROJECT_USER_AGENT)
        } finally { sources.close(); sources.close() }
    }
}
