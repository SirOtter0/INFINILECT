// SPDX-License-Identifier: GPL-3.0-only
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.infinilect.app.search.SearchException
import org.infinilect.core.PublicationFormat
import org.infinilect.core.PublicationId
import org.infinilect.core.PublicationResource
import org.infinilect.core.SourceId

class GutenbergSourceTest {
    @Test fun searchMakesOneIdentifiedRequestAndNextIsExplicit() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            assertEquals("INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)", request.headers[HttpHeaders.UserAgent])
            assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
            assertTrue(request.headers[HttpHeaders.Accept]!!.contains("application/atom+xml"))
            respond(if (urls.size == 1) fixture("search") else fixture("empty"), headers = headersOf(HttpHeaders.ContentType, "application/atom+xml; charset=UTF-8"))
        }
        GutenbergSource(engine).use { source ->
            val first = source.search(" books ")
            assertEquals(1, urls.size)
            assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=books", urls.single())
            source.search("books", assertNotNull(first.nextPageToken))
            assertEquals(2, urls.size)
            assertTrue(urls.last().contains("start_index=26"))
        }
    }

    @Test fun queryEncodingCannotInjectParameters() = runTest {
        val query = "café & start_index=999 / ?"
        GutenbergSource(MockEngine { request ->
            assertEquals(query, request.url.parameters["query"])
            assertEquals(setOf("query"), request.url.parameters.names())
            respond(fixture("empty"), headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"))
        }).use { it.search(query) }
    }

    @Test fun invalidQueryAndForgedOrMismatchedTokensMakeNoRequests() = runTest {
        var requests = 0
        GutenbergSource(MockEngine { requests++; error("Unexpected request") }).use { source ->
            assertFailsWith<IllegalArgumentException> { source.search(" ") }
            assertFailsWith<IllegalArgumentException> { source.search("x".repeat(257)) }
            assertFailsWith<IllegalArgumentException> { source.search("books", "https://evil.example/") }
            val token = GutenbergUrls.nextToken("?query=books&start_index=26", GutenbergUrls.search("books"), "books")
            assertFailsWith<IllegalArgumentException> { source.search("other", token) }
            val forged = "gutenberg-opds-1:" + java.util.Base64.getUrlEncoder().encodeToString("https://evil.example/ebooks/search.opds/?query=books&start_index=26".toByteArray())
            assertFailsWith<IllegalArgumentException> { source.search("books", forged) }
            assertEquals(0, requests)
        }
    }

    @Test fun redirectsAndHttpErrorsAreNotFollowedOrRetried() = runTest {
        for (status in listOf(HttpStatusCode.Found, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
            var requests = 0
            GutenbergSource(MockEngine {
                requests++
                respond("", status, headersOf(HttpHeaders.Location, "https://evil.example/"))
            }).use { source ->
                val error = assertFailsWith<SearchException> { source.search("books") }
                assertTrue(error.message!!.contains(status.value.toString()))
                assertEquals(1, requests)
            }
        }
    }

    @Test fun rejectsWrongContentTypeCompressionAndHugeDeclaredOrActualBodies() = runTest {
        val responses = listOf(
            Pair(fixture("empty"), headersOf(HttpHeaders.ContentType, "text/html")),
            Pair(fixture("empty"), headersOf(HttpHeaders.ContentType to listOf("application/atom+xml"), HttpHeaders.ContentEncoding to listOf("gzip"))),
            Pair(fixture("empty"), headersOf(HttpHeaders.ContentType to listOf("application/atom+xml"), HttpHeaders.ContentLength to listOf((MAX_FEED_BYTES + 1).toString()))),
            Pair(ByteArray(MAX_FEED_BYTES + 1), headersOf(HttpHeaders.ContentType, "application/atom+xml")),
            Pair(ByteArray(MAX_FEED_BYTES + 1), headersOf(HttpHeaders.ContentType to listOf("application/atom+xml"), HttpHeaders.ContentLength to listOf("1"))),
        )
        for ((body, headers) in responses) {
            GutenbergSource(MockEngine { respond(body, headers = headers) }).use { source ->
                assertFailsWith<InvalidOpdsException> { source.search("books") }
            }
        }
    }

    @Test fun foreignIdsAreRejectedAndDeferredCapabilitiesAreExplicit() = runTest {
        var requests = 0
        GutenbergSource(MockEngine { requests++; error("Unexpected request") }).use { source ->
            val foreign = PublicationId(SourceId("other"), "11")
            val own = PublicationId(SourceId("gutenberg"), "11")
            assertFailsWith<IllegalArgumentException> { source.getPublication(foreign) }
            assertFailsWith<IllegalArgumentException> { source.loadResource(PublicationResource(foreign, "text", PublicationFormat.TEXT, "text/plain")) }
            assertFailsWith<UnsupportedOperationException> { source.getPublication(own) }
            assertFailsWith<UnsupportedOperationException> { source.loadResource(PublicationResource(own, "text", PublicationFormat.TEXT, "text/plain")) }
            assertEquals(0, requests)
        }
    }

    @Test fun cancellationReleasesRequestAndSourceCanBeClosed() = runTest {
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val source = GutenbergSource(MockEngine {
            entered.complete(Unit)
            blocked.await()
            respond(fixture("empty"), headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"))
        })
        try {
            val request = async { source.search("books") }
            entered.await()
            assertFalse(request.isCompleted)
            request.cancel()
            assertFailsWith<CancellationException> { request.await() }
        } finally { source.close() }
        source.close()
        assertFailsWith<IllegalStateException> { source.search("books") }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun concurrentCallersNeverMakeParallelRequests() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        GutenbergSource(MockEngine {
            requests++
            if (requests == 1) { entered.complete(Unit); release.await() }
            respond(fixture("empty"), headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"))
        }).use { source ->
            val first = async { source.search("books") }
            entered.await()
            val second = async { source.search("books") }
            runCurrent()
            assertEquals(1, requests)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            first.await(); second.await()
            assertEquals(2, requests)
        }
    }
}
