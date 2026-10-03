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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*
import org.infinilect.app.acquisition.*
import org.infinilect.app.search.SearchException
import org.infinilect.app.network.PROJECT_USER_AGENT
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
            assertEquals(PROJECT_USER_AGENT, request.headers[HttpHeaders.UserAgent])
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
            assertEquals(PROJECT_USER_AGENT, request.headers[HttpHeaders.UserAgent])
            when {
                request.url.encodedPath.startsWith("/metadata/") -> respond(itemFixture(), headers = jsonHeaders)
                request.url.host == "archive.org" -> respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://$FIXTURE_STORAGE_HOST/0/items/${testId.localId}/${testResource.key}"))
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
        for (target in listOf("https://evil.example/file.txt", ArchiveUrls.download(testId.localId, testResource.key), "https://ARCHIVE.ORG:443/download/${testId.localId}/${testResource.key}")) {
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

    @Test fun differentItemsUseTheirOwnFreshAnnouncedDeliveryNodes() = runTest {
        for ((identifier, host) in listOf(testId.localId to FIXTURE_STORAGE_HOST, "public-other" to "dn760106.eu.archive.org")) {
            val metadata = itemFixture { it.replace(testId.localId, identifier).replace(FIXTURE_STORAGE_HOST, host) }
            InternetArchiveSource(MockEngine { request ->
                when {
                    request.url.encodedPath.startsWith("/metadata/") -> respond(metadata, headers = jsonHeaders)
                    request.url.host == "archive.org" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location,
                        "https://$host/0/items/$identifier/${identifier}_djvu.txt"))
                    else -> { assertEquals(host, request.url.host); respond(ByteArray(2566), headers = textHeaders()) }
                }
            }).use { source ->
                val publication = assertNotNull(source.getPublication(PublicationId(ARCHIVE_ID, identifier)))
                val handle = source.loadResource(publication.resources.first { it.format == PublicationFormat.TEXT })
                try { assertEquals(1, handle.read(ByteArray(1))) } finally { handle.close() }
            }
        }
    }

    @Test fun aRedirectToAnArchiveHostMissingFromFreshMetadataIsRejected() = runTest {
        var calls = 0
        InternetArchiveSource(MockEngine { request ->
            calls++
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location,
                "https://dn760106.eu.archive.org/0/items/${testId.localId}/${testResource.key}"))
        }).use { source ->
            assertFailsWith<SearchException> { source.loadResource(testResource) }
            assertEquals(2, calls)
        }
    }

    @Test fun exactlyTwoValidRedirectsSucceedButAThirdIsNeverRequested() = runTest {
        for (excessive in listOf(false, true)) {
            val paths = mutableListOf<String>()
            InternetArchiveSource(MockEngine { request ->
                paths += request.url.host + request.url.encodedPath
                when {
                    request.url.encodedPath.startsWith("/metadata/") -> respond(itemFixture(), headers = jsonHeaders)
                    request.url.host == "archive.org" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location,
                        "https://ia903102.us.archive.org/35/items/${testId.localId}/${testResource.key}"))
                    request.url.host == "ia903102.us.archive.org" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location,
                        "https://ia803102.us.archive.org/35/items/${testId.localId}/${testResource.key}"))
                    excessive -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location,
                        "https://$FIXTURE_STORAGE_HOST/0/items/${testId.localId}/${testResource.key}"))
                    else -> respond(ByteArray(2566), headers = textHeaders())
                }
            }).use { source ->
                if (excessive) assertFailsWith<SearchException> { source.loadResource(testResource) }
                else source.loadResource(testResource).close()
                assertEquals(4, paths.size) // Metadata + initial download + two targets.
                assertTrue(paths.none { it.startsWith(FIXTURE_STORAGE_HOST) })
            }
        }
    }

    @Test fun permissionsAndLocationsAreRefreshedInsideTheSerializedOpenOperation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val paths = mutableListOf<String>()
        InternetArchiveSource(MockEngine { request ->
            paths += request.url.encodedPath
            if (paths.size == 1) { entered.complete(Unit); release.await() }
            if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
            else respond(ByteChannel(), headers = textHeaders())
        }).use { source ->
            val opening = async { source.loadResource(testResource) }
            entered.await()
            val details = async(start = CoroutineStart.UNDISPATCHED) { source.getPublication(testId) }
            release.complete(Unit)
            val handle = opening.await()
            try {
                assertEquals(listOf("/metadata/${testId.localId}", "/download/${testId.localId}/${testResource.key}"), paths)
                assertFalse(details.isCompleted)
            } finally { handle.close() }
            assertNotNull(details.await())
        }
    }

    @Test fun oversizedResourceConsumesOnlyOneOverflowByteBeyondItsKnownSize() = runTest {
        val evidence = ArchiveHttpEvidence("https://archive.org/download/item/file.txt", 200, null)
        val content = ArchiveResourceContent(ByteReadChannel(ByteArray(4096)), 10, evidence) {}
        assertFailsWith<InvalidArchiveData> { content.read(ByteArray(1024)) }
        assertEquals(11L, evidence.consumedBytes.get())
        assertFailsWith<IllegalStateException> { content.read(ByteArray(1)) }
    }

    @Test fun concurrentInternalAndConsumerCloseRunsCleanupOnce() = runTest {
        val cleaned = java.util.concurrent.atomic.AtomicInteger()
        val content = ArchiveResourceContent(ByteChannel(), 10, ArchiveHttpEvidence("test", 200, null)) { cleaned.incrementAndGet() }
        coroutineScope { repeat(100) { launch(Dispatchers.Default) { content.close() } } }
        content.close()
        assertEquals(1, cleaned.get())
        assertFailsWith<IllegalStateException> { content.read(ByteArray(1)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun anIdleHandleDeadlineReleasesTheSourceWithoutWaitingForConsumerClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                if (request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(), headers = jsonHeaders)
                else respond(ByteChannel(), headers = textHeaders())
            }
        })
        InternetArchiveSource(engine, streamDispatcher = dispatcher).use { source ->
            val handle = source.loadResource(testResource)
            advanceTimeBy(60_001); runCurrent()
            assertFailsWith<IllegalStateException> { handle.read(ByteArray(1)) }
            assertNotNull(source.getPublication(testId))
            handle.close()
        }
    }
}
