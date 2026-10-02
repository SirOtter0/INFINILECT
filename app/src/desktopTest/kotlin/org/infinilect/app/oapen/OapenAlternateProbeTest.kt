// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.infinilect.app.archive.archiveFixture

class OapenAlternateProbeTest {
    @Test fun extractsAnnouncedSizeAndVerbatimRightsFromDocumentedRecord() {
        val file = parseOapenRecord(archiveFixture("oapen-record.xml"))
        assertEquals(9593943L, file.size)
        assertEquals("CC-BY-NC", file.rights)
        assertEquals("http://creativecommons.org/licenses/by-nc/3.0/", file.licenseUrl)
    }

    @Test fun metadataThenHeadOnlyDoesNotFollowRedirectOrConsumeFile() = runTest {
        val methods = mutableListOf<HttpMethod>()
        OapenAlternateProbe(MockEngine { request ->
            methods += request.method
            assertTrue(request.headers[HttpHeaders.UserAgent]!!.contains("INFINILECT"))
            if (request.method == HttpMethod.Get) respond(archiveFixture("oapen-record.xml"), headers = headersOf(HttpHeaders.ContentType, "text/xml"))
            else respond("", HttpStatusCode.Forbidden)
        }).use { probe ->
            val result = probe.inspect()
            assertEquals(200, result.metadataStatus)
            assertEquals(403, result.headStatus)
            assertTrue(result.metadataBytes > 0)
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Head), methods)
            assertEquals(2, probe.attemptedRequests)
            assertEquals(result.metadataBytes, probe.consumedMetadataBytes)
        }
    }

    @Test fun metadataErrorDoesNotAttemptAcquisition() = runTest {
        var calls = 0
        OapenAlternateProbe(MockEngine { calls++; respond("", HttpStatusCode.TooManyRequests) }).use {
            assertEquals(429, it.inspect().metadataStatus)
            assertEquals(1, calls)
        }
    }

    @Test fun rejectsDtdExternalEntitiesMalformedXmlAndUnsafeResourceUrl() {
        val fixture = archiveFixture("oapen-record.xml").toString(Charsets.UTF_8)
        for (xml in listOf(
            "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>", "<broken>",
            fixture.replace("https://library.oapen.org/bitstream", "https://evil.example/bitstream"),
            fixture.replace("/1/9789085551201", "/1/../9789085551201"),
            fixture.replace("9593943", "999999999999"),
            "<x>".repeat(33) + "</x>".repeat(33),
        )) assertFails { parseOapenRecord(xml.toByteArray()) }
    }

    @Test fun rejectsOversizedResponsesAndUnexpectedContentType() = runTest {
        for ((body, type) in listOf(ByteArray(OAPEN_PROBE_MAX_BYTES + 1) to "text/xml", archiveFixture("oapen-record.xml") to "text/html"))
            OapenAlternateProbe(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, type)) }).use {
                assertFails { it.inspect() }
            }
    }

    @Test fun propagatesCancellationInNetworkAndParsing() = runTest {
        val entered = CompletableDeferred<Unit>()
        OapenAlternateProbe(MockEngine { entered.complete(Unit); awaitCancellation() }).use { probe ->
            val job = async { probe.inspect() }
            entered.await(); job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        }
        assertFailsWith<CancellationException> { parseOapenRecord(archiveFixture("oapen-record.xml")) { throw CancellationException() } }
    }
}
