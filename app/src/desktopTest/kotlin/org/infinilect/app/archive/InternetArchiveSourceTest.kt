// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.infinilect.app.acquisition.*
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

private val testId = PublicationId(ARCHIVE_ID, "gmb-2015-93040")
private val testResource = PublicationResource(testId, "gmb-2015-93040_djvu.txt", PublicationFormat.TEXT, "text/plain")
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private fun textHeaders(size: String? = null) = if (size == null) headersOf(HttpHeaders.ContentType, "text/plain; charset=utf-8") else
    headersOf(HttpHeaders.ContentType to listOf("text/plain; charset=utf-8"), HttpHeaders.ContentLength to listOf(size))

class InternetArchiveSourceTest {
    @Test fun searchIsOneIdentifiedRequestWithExplicitOpaquePaginationAndEncodedQuery() = runTest {
        var calls = 0
        val query = "café & page=999"
        InternetArchiveSource(MockEngine { request ->
            calls++
            assertEquals(ARCHIVE_USER_AGENT, request.headers[HttpHeaders.UserAgent])
            assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
            assertEquals("10", request.url.parameters["rows"])
            assertTrue(request.url.parameters["q"]!!.contains("($query)"))
            assertEquals(calls.toString(), request.url.parameters["page"])
            respond(archiveFixture(if (calls == 1) "search.json" else "empty.json"), headers = jsonHeaders)
        }).use { source ->
            val first = source.search(query)
            assertEquals(1, calls)
            source.search(query, assertNotNull(first.nextPageToken))
            assertEquals(2, calls)
        }
    }

    @Test fun invalidQueriesTokensAndForeignIdentitiesMakeNoRequests() = runTest {
        InternetArchiveSource(MockEngine { error("Unexpected network request") }).use { source ->
            assertFailsWith<IllegalArgumentException> { source.search(" ") }
            assertFailsWith<IllegalArgumentException> { source.search("x".repeat(257)) }
            assertFailsWith<IllegalArgumentException> { source.search("x", ArchiveUrls.token("other", 2)) }
            val foreign = testId.copy(sourceId = SourceId("gutenberg"))
            assertFailsWith<IllegalArgumentException> { source.getPublication(foreign) }
            assertFailsWith<IllegalArgumentException> { source.loadResource(testResource.copy(publicationId = foreign)) }
            assertFailsWith<IllegalArgumentException> { source.loadResource(testResource.copy(key = "../bad.txt")) }
            assertFailsWith<IllegalArgumentException> { source.loadResource(testResource.copy(revision = "invented")) }
        }
    }

    @Test fun metadataMapsPublicationAndMissingItemReturnsNull() = runTest {
        InternetArchiveSource(MockEngine { request ->
            respond(if (request.url.encodedPath.endsWith("absent")) "[]".toByteArray() else itemFixture(), headers = jsonHeaders)
        }).use { source ->
            assertEquals(testId, assertNotNull(source.getPublication(testId)).id)
            assertNull(source.getPublication(PublicationId(ARCHIVE_ID, "absent")))
        }
    }

    @Test fun resourceMustMatchRefreshedPublicMetadataExactly() = runTest {
        var calls = 0
        InternetArchiveSource(MockEngine { calls++; respond(itemFixture(), headers = jsonHeaders) }).use { source ->
            assertFailsWith<SearchException> { source.loadResource(testResource.copy(key = "unlisted.txt")) }
            assertFailsWith<SearchException> { source.loadResource(testResource.copy(mediaType = "text/html")) }
            assertFailsWith<SearchException> { source.loadResource(testResource.copy(format = PublicationFormat.PDF)) }
            assertEquals(3, calls)
        }
    }

    @Test fun restrictedCc0ItemCannotAcquireEvenAnExistingFile() = runTest {
        var calls = 0
        InternetArchiveSource(MockEngine {
            calls++
            respond(itemFixture { it.replace("\"mediatype\":", "\"access-restricted-item\":\"true\",\"mediatype\":") }, headers = jsonHeaders)
        }).use { source ->
            assertFailsWith<SearchException> { source.loadResource(testResource) }
            assertEquals(1, calls)
        }
    }

    @Test fun followsOneVerifiedRedirectAndReturnsFreshSequentialHandles() = runTest {
        var resourceCalls = 0
        InternetArchiveSource(MockEngine { request ->
            when {
                request.url.encodedPath.startsWith("/metadata/") -> respond(itemFixture(), headers = jsonHeaders)
                request.url.host == "archive.org" -> respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://${ArchiveUrls.DELIVERY_HOST}/0/items/${testId.localId}/${testResource.key}"))
                else -> { resourceCalls++; respond(ByteArray(2566) { 'A'.code.toByte() }, headers = textHeaders("2566")) }
            }
        }).use { source ->
            repeat(2) {
                val content = source.loadResource(testResource)
                try {
                    assertEquals(2566L, content.sizeBytes)
                    val buffer = ByteArray(17)
                    assertEquals(0, content.read(buffer, 17, 0))
                    assertEquals(7, content.read(buffer, 2, 7))
                    assertEquals('A'.code.toByte(), buffer[2])
                    assertFailsWith<IllegalArgumentException> { content.read(buffer, -1, 1) }
                } finally { content.close() }
                content.close()
                assertFailsWith<IllegalStateException> { content.read(ByteArray(1)) }
            }
            assertEquals(2, resourceCalls)
        }
    }

    @Test fun largeUnconsumedChannelIsNotMaterializedAndCloseAbortsIt() = runTest {
        val channel = ByteChannel()
        InternetArchiveSource(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond(channel, headers = textHeaders())
        }).use { source ->
            val content = source.loadResource(testResource) // Succeeds before any body bytes are available.
            assertFalse(channel.isClosedForRead)
            content.close()
            // close is non-blocking. A following serialized request waits for producer cleanup.
            assertNotNull(source.getPublication(testId))
            assertTrue(channel.isClosedForRead)
        }
    }

    @Test fun metadataHttpErrorsAndRedirectsAreNeverRetried() = runTest {
        for (status in listOf(HttpStatusCode.Found, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
            var calls = 0
            InternetArchiveSource(MockEngine { calls++; respond("", status, headersOf(HttpHeaders.Location, "https://evil.example")) }).use { source ->
                val failure = assertFailsWith<SearchException> { source.search("q") }
                assertFalse(failure.message!!.contains("evil.example"))
                assertEquals(1, calls)
            }
        }
    }

    @Test fun metadataMustBeBoundedJsonWithValidLengthAndNoCompression() = runTest {
        val cases = listOf(
            ByteArray(MAX_METADATA_BYTES + 1) to jsonHeaders,
            "{}".toByteArray() to headersOf(HttpHeaders.ContentType, "text/html"),
            "{}".toByteArray() to headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ContentLength to listOf("bad")),
            "{}".toByteArray() to headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ContentLength to listOf((MAX_METADATA_BYTES + 1).toString())),
            "{}".toByteArray() to headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ContentEncoding to listOf("gzip")),
            "{broken".toByteArray() to jsonHeaders,
        )
        cases.forEach { (body, headers) -> InternetArchiveSource(MockEngine { respond(body, headers = headers) }).use { source ->
            assertFailsWith<SearchException> { source.search("q") }
        } }
    }

    @Test fun unsafeResourceRedirectsAndLoopsAreRejected() = runTest {
        for (target in listOf("https://evil.example/file.txt", ArchiveUrls.download(testId.localId, testResource.key))) {
            var calls = 0
            InternetArchiveSource(MockEngine { request ->
                calls++
                if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
                else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, target))
            }).use { source ->
                assertFailsWith<SearchException> { source.loadResource(testResource) }
                assertEquals(2, calls)
            }
        }
    }

    @Test fun rejectsUnexpectedResourceTypeStatusSizeAndCompression() = runTest {
        val cases = listOf(
            HttpStatusCode.OK to headersOf(HttpHeaders.ContentType, "application/pdf"),
            HttpStatusCode.Forbidden to textHeaders(),
            HttpStatusCode.PartialContent to textHeaders(),
            HttpStatusCode.OK to textHeaders((MAX_RESOURCE_BYTES + 1).toString()),
            HttpStatusCode.OK to textHeaders("2565"),
            HttpStatusCode.OK to headersOf(HttpHeaders.ContentType to listOf("text/plain"), HttpHeaders.ContentEncoding to listOf("gzip")),
        )
        cases.forEach { (status, headers) -> InternetArchiveSource(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond("x", status, headers)
        }).use { source -> assertFailsWith<SearchException> { source.loadResource(testResource) } } }
    }

    @Test fun actualOverlongOrTruncatedResourceFailsDuringConsumption() = runTest {
        for (actual in listOf(2565, 2567)) InternetArchiveSource(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond(ByteArray(actual), headers = textHeaders())
        }).use { source ->
            val content = source.loadResource(testResource)
            assertFailsWith<InvalidArchiveData> { content.readBytes(3000) }
            assertFailsWith<IllegalStateException> { content.read(ByteArray(1)) }
        }
    }

    @Test fun cancelledOpeningReleasesRequestsAndTimeoutIsUserFacing() = runTest {
        val entered = CompletableDeferred<Unit>()
        InternetArchiveSource(MockEngine { entered.complete(Unit); awaitCancellation() }).use { source ->
            val request = async { source.getPublication(testId) }
            entered.await(); request.cancel()
            assertFailsWith<CancellationException> { request.await() }
        }
        InternetArchiveSource(MockEngine { throw SocketTimeoutException("internal transport detail") }).use { source ->
            val error = assertFailsWith<SearchException> { source.search("q") }
            assertFalse(error.message!!.contains("internal transport detail"))
        }
    }

    @Test fun cancelledReadAbortsSingleConsumerStream() = runTest {
        val channel = ByteChannel()
        InternetArchiveSource(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond(channel, headers = textHeaders())
        }).use { source ->
            val content = source.loadResource(testResource)
            val reading = async(start = CoroutineStart.UNDISPATCHED) { content.read(ByteArray(32)) }
            reading.cancel()
            assertFailsWith<CancellationException> { reading.await() }
            assertNotNull(source.getPublication(testId))
            assertTrue(channel.isClosedForRead)
            assertFailsWith<IllegalStateException> { content.read(ByteArray(1)) }
        }
    }

    @Test fun cancelledResourceOpeningReleasesTheStreamingProducer() = runTest {
        val entered = CompletableDeferred<Unit>()
        InternetArchiveSource(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else { entered.complete(Unit); awaitCancellation() }
        }).use { source ->
            val opening = async { source.loadResource(testResource) }
            entered.await(); opening.cancel()
            assertFailsWith<CancellationException> { opening.await() }
            assertNotNull(source.getPublication(testId))
        }
    }

    @Test fun fullSmallResourceHasShortReadsAndEofWithoutWholeFileAllocation() = runTest {
        InternetArchiveSource(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond(ByteArray(2566) { 42 }, headers = textHeaders())
        }).use { source ->
            val content = source.loadResource(testResource)
            try {
                val buffer = ByteArray(128)
                var total = 0
                while (true) {
                    val count = content.read(buffer)
                    if (count == -1) break
                    assertTrue(count in 1..128); total += count
                }
                assertEquals(2566, total)
                assertEquals(-1, content.read(buffer))
            } finally { content.close() }
        }
    }

    @Test fun anOpenHandleSerializesOtherSourceRequestsUntilClosed() = runTest {
        val secondEntered = CompletableDeferred<Unit>()
        var calls = 0
        InternetArchiveSource(MockEngine { request ->
            calls++
            if (calls == 3) secondEntered.complete(Unit)
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond(ByteChannel(), headers = textHeaders())
        }).use { source ->
            val content = source.loadResource(testResource)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { source.getPublication(testId) }
            assertFalse(secondEntered.isCompleted)
            assertFalse(waiting.isCompleted)
            content.close()
            assertNotNull(waiting.await())
            assertEquals(3, calls)
        }
    }

    @Test fun sourceShutdownAbortsOutstandingHandleAndPreventsNewCalls() = runTest {
        val channel = ByteChannel()
        val source = InternetArchiveSource(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond(channel, headers = textHeaders())
        })
        val content = source.loadResource(testResource)
        source.close(); source.close()
        assertFails { content.read(ByteArray(1)) }
        content.close()
        assertFailsWith<SearchException> { source.search("q") }
    }

    @Test fun neutralDemoConsumesOnlyAPrefixAndClosesTheHandle() = runTest {
        val evidence = mutableListOf<ArchiveHttpEvidence>()
        InternetArchiveSource(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond(ByteArray(2566) { 'A'.code.toByte() }, headers = textHeaders())
        }, observe = { evidence += it }).use { source ->
            val publication = assertNotNull(source.getPublication(testId))
            assertEquals(testResource, selectDemoResource(publication, PublicationFormat.TEXT))
            assertNull(selectDemoResource(publication, PublicationFormat.EPUB))
            val acquired = demonstrateAcquisition(DirectResourceLoader(source), testResource)
            assertEquals(512, acquired.sampledBytes)
            assertEquals(2566L, acquired.sizeBytes)
            assertEquals(512L, evidence.last().consumedBytes.get())
            val fresh = source.loadResource(testResource)
            try { assertEquals(1, fresh.read(ByteArray(1))) } finally { fresh.close() }
        }
    }
}
