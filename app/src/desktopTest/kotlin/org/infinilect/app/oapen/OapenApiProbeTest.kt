// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OapenApiProbeTest {
    @Test fun oneIdentifiedFixedHostRequestEncodesQueryAndUsesDocumentedParameters() = runTest {
        var calls = 0
        val query = "water & offset=999 / ?"
        OapenApiProbe(MockEngine { request ->
            calls++
            assertEquals("https", request.url.protocol.name)
            assertEquals("library.oapen.org", request.url.host)
            assertEquals("/rest/search", request.url.encodedPath)
            assertEquals(setOf("query", "expand", "limit", "offset"), request.url.parameters.names())
            assertEquals(query, request.url.parameters["query"])
            assertEquals("metadata,bitstreams", request.url.parameters["expand"])
            assertEquals("10", request.url.parameters["limit"])
            assertEquals("0", request.url.parameters["offset"])
            assertEquals("application/xml", request.headers[HttpHeaders.Accept])
            assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
            assertTrue(request.headers[HttpHeaders.UserAgent]!!.contains("https://github.com/SirOtter0/INFINILECT/issues"))
            respond("<items/>", headers = headersOf(HttpHeaders.ContentType, "application/xml; charset=UTF-8"))
        }).use { probe ->
            assertEquals(OapenApiObservation(200, 8), probe.inspect(" $query "))
            assertEquals(1, calls)
        }
    }

    @Test fun blankAndOverlongQueriesMakeNoRequests() = runTest {
        OapenApiProbe(MockEngine { error("Unexpected request") }).use { probe ->
            assertFailsWith<IllegalArgumentException> { probe.inspect(" ") }
            assertFailsWith<IllegalArgumentException> { probe.inspect("x".repeat(257)) }
        }
    }

    @Test fun rejectedAccessRedirectsAndServerErrorsAreNotRetriedOrFollowed() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.Found, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
            var calls = 0
            OapenApiProbe(MockEngine {
                calls++
                respond("Untrusted error body", status, headersOf(HttpHeaders.Location, "https://other.example/book.pdf"))
            }).use { probe ->
                assertEquals(OapenApiObservation(status.value, 0), probe.inspect("water"))
                assertEquals(1, calls)
            }
        }
    }

    @Test fun rejectsUnexpectedMediaCompressionAndInvalidDeclaredLengths() = runTest {
        val responses = listOf(
            headersOf(HttpHeaders.ContentType, "text/html"),
            headersOf(HttpHeaders.ContentType to listOf("application/xml"), HttpHeaders.ContentEncoding to listOf("gzip")),
            headersOf(HttpHeaders.ContentType to listOf("application/xml"), HttpHeaders.ContentLength to listOf((OAPEN_PROBE_MAX_BYTES + 1).toString())),
        )
        for (headers in responses) {
            OapenApiProbe(MockEngine { respond("<items/>", headers = headers) }).use { probe ->
                assertFailsWith<IllegalStateException> { probe.inspect("water") }
            }
        }
    }

    @Test fun actualBodyLimitAppliesWithoutOrWithMisleadingLength() = runTest {
        for (headers in listOf(
            headersOf(HttpHeaders.ContentType, "application/xml"),
            headersOf(HttpHeaders.ContentType to listOf("application/xml"), HttpHeaders.ContentLength to listOf("1")),
        )) {
            OapenApiProbe(MockEngine { respond(ByteArray(OAPEN_PROBE_MAX_BYTES + 1), headers = headers) }).use { probe ->
                assertFailsWith<IllegalStateException> { probe.inspect("water") }
            }
        }
    }

    @Test fun cancellationPropagatesAndCloseIsIdempotent() = runTest {
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val probe = OapenApiProbe(MockEngine {
            entered.complete(Unit)
            blocked.await()
            respond("<items/>", headers = headersOf(HttpHeaders.ContentType, "application/xml"))
        })
        try {
            val request = async { probe.inspect("water") }
            entered.await()
            request.cancel()
            assertFailsWith<CancellationException> { request.await() }
        } finally { probe.close() }
        probe.close()
        assertFailsWith<IllegalStateException> { probe.inspect("water") }
    }

    @Test fun transportTimeoutIsNotRetried() = runTest {
        var calls = 0
        OapenApiProbe(MockEngine { request ->
            calls++
            throw HttpRequestTimeoutException(request)
        }).use { probe ->
            assertFailsWith<HttpRequestTimeoutException> { probe.inspect("water") }
            assertEquals(1, calls)
        }
    }
}
