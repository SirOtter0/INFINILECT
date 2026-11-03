// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.client.engine.*
import io.ktor.client.engine.android.Android
import io.ktor.client.engine.android.AndroidEngineConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.InternalAPI
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import org.infinilect.app.*
import org.infinilect.app.discovery.*
import org.infinilect.core.PublicationId
import org.infinilect.app.network.platformHttpEngine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source

/** Executes the production Ktor Android 3.6.0 engine and its real cleanup chain on a JVM.
 * Only the URL connection is synthetic. Its strict-thread guard is not a physical Android trace. */
@OptIn(InternalAPI::class)
class GutenbergAndroidCancellationTest {
    private class MainThreadDisconnect : IllegalStateException("Synthetic strict-thread connection guard")
    private class Connection(url: URL, val ui: Thread, holdDisconnect: Boolean = false) : HttpsURLConnection(url) {
        val entered = CompletableDeferred<Unit>()
        val readGate = CountDownLatch(1)
        val disconnected = CompletableDeferred<Thread>()
        val disconnectGate = CountDownLatch(if (holdDisconnect) 1 else 0)
        @Volatile var response = previewRoot
        @Volatile var responseMediaType = "application/opds+json"
        @Volatile var hold = true
        var stream: InputStream? = null
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() {
            val thread = Thread.currentThread()
            disconnected.complete(thread); readGate.countDown()
            if (thread === ui) throw MainThreadDisconnect()
            check(disconnectGate.await(10, TimeUnit.SECONDS))
        }
        override fun getResponseCode(): Int {
            entered.complete(Unit)
            if (hold) check(readGate.await(10, TimeUnit.SECONDS))
            return 200
        }
        override fun getResponseMessage() = "OK"
        override fun getHeaderFields(): Map<String, List<String>> = mapOf("Content-Type" to listOf(responseMediaType))
        override fun getInputStream(): InputStream = stream ?: ByteArrayInputStream(response.toByteArray())
        override fun getCipherSuite() = "TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
    private object TestUrls {
        val next = AtomicReference<Connection>()
        init {
            // Public JVM API, unique non-network scheme; HTTP/HTTPS handlers remain untouched.
            URL.setURLStreamHandlerFactory { protocol -> if (protocol == "infinilect-cancellation-fixture") object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = checkNotNull(next.get())
            } else null }
        }
    }
    private class Engine(private val delegate: HttpClientEngine = Android.create()) : HttpClientEngineBase("original-cancellation-fixture") {
        override val config get() = delegate.config
        override val dispatcher get() = delegate.dispatcher
        override val supportedCapabilities get() = delegate.supportedCapabilities
        override fun close() { super.close(); delegate.close() }
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            TestUrls.next.get().apply {
                response = if (data.url.encodedPath.endsWith("/search")) {
                    val book = previewPublication().dropLast(1) + ",\"images\":[{\"href\":\"https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg\",\"type\":\"image/jpeg\"}]}"
                    previewPage(data.url.parameters["query"].orEmpty(), publications = book)
                } else previewRoot
                responseMediaType = if (data.url.encodedPath.endsWith(".jpg")) "image/jpeg" else "application/opds+json"
            }
            val url = Url(data.url.toString().replaceFirst("https:", "infinilect-cancellation-fixture:"))
            return delegate.execute(HttpRequestData(url, data.method, data.headers, data.body, data.executionContext, data.attributes))
        }
    }
    @Test fun cancellingAnActiveGutenbergRequestFromUiMustNotDisconnectOnUi() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val connection = Connection(URL("https://example.invalid/original-fixture"), withContext(ui) { Thread.currentThread() })
            TestUrls.next.set(connection)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + ui + CoroutineExceptionHandler { _, error -> errors.add(error) })
            val source = GutenbergSource(Engine())
            try {
                val request = scope.async { source.search("original fixture") }
                withTimeout(5_000) { kotlinx.coroutines.selects.select<Unit> {
                    connection.entered.onAwait {}
                    request.onAwait { error("Fixture request completed before connection barrier") }
                } }
                withContext(ui) { request.cancel() }
                withTimeout(5_000) { request.join() }
                assertNotSame(connection.ui, withTimeout(5_000) { connection.disconnected.await() }, "Ktor cancellation handlers run inline: UI cancellation must hand disconnect to IO")
                assertTrue(errors.isEmpty(), "No uncaught completion-handler failure: ${errors.map { it.javaClass.simpleName }}")
                assertTrue(request.isCancelled)
                val retry = Connection(URL("https://example.invalid/original-fixture"), connection.ui).apply { hold = false }
                TestUrls.next.set(retry)
                assertEquals(1, withTimeout(5_000) { source.search("original fixture") }.publications.size)
            } finally { connection.readGate.countDown(); source.close(); source.awaitClosed(); scope.cancel() }
        }
    }
    @Test fun closingGutenbergWithAnActiveRequestMustAlsoDisconnectAwayFromUi() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val connection = Connection(URL("https://example.invalid/original-fixture"), withContext(ui) { Thread.currentThread() })
            TestUrls.next.set(connection)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + ui + CoroutineExceptionHandler { _, error -> errors.add(error) })
            val source = GutenbergSource(Engine())
            try {
                val request = scope.async { source.search("original fixture") }
                withTimeout(5_000) { kotlinx.coroutines.selects.select<Unit> {
                    connection.entered.onAwait {}
                    request.onAwait { error("Fixture request completed before connection barrier") }
                } }
                val closeFailure = runCatching { withContext(ui) { source.close() } }.exceptionOrNull()
                assertNotSame(connection.ui, withTimeout(5_000) { connection.disconnected.await() })
                assertNull(closeFailure)
                withTimeout(5_000) { request.join() }
                assertTrue(errors.isEmpty())
                assertTrue(request.isCancelled)
            } finally { connection.readGate.countDown(); source.close(); source.awaitClosed(); scope.cancel() }
        }
    }

    @Test fun slowDisconnectDoesNotBlockUiAndFinalCleanupIsJoined() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val connection = Connection(URL("https://example.invalid/original-fixture"), withContext(ui) { Thread.currentThread() }, holdDisconnect = true)
            TestUrls.next.set(connection)
            val scope = CoroutineScope(SupervisorJob() + ui)
            val source = GutenbergSource(Engine())
            try {
                val request = scope.async { source.search("original fixture") }
                withTimeout(5_000) { connection.entered.await() }
                withTimeout(5_000) { withContext(ui) { request.cancel() } }
                assertNotSame(connection.ui, withTimeout(5_000) { connection.disconnected.await() })
                assertEquals(42, withTimeout(5_000) { withContext(ui) { 42 } }, "The UI must remain available before IO disconnect is released")
                assertFalse(request.isCompleted, "Cancellation waits for owned request cleanup, not for navigation")
                connection.disconnectGate.countDown()
                withTimeout(5_000) { request.join() }
            } finally {
                connection.readGate.countDown(); connection.disconnectGate.countDown()
                source.close(); source.awaitClosed(); scope.cancel()
            }
        }
    }
    @Test fun everyHomeDestinationRejectsCancelledGutenbergResultsAndCanReturnForRetry() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            for (target in listOf(Destination.LIBRARY, Destination.HISTORY, Destination.SEARCH, Destination.SETTINGS)) {
                val connection = Connection(URL("https://example.invalid/original-fixture"), withContext(ui) { Thread.currentThread() })
                TestUrls.next.set(connection)
                val errors = ConcurrentLinkedQueue<Throwable>()
                val scope = CoroutineScope(SupervisorJob() + ui + CoroutineExceptionHandler { _, error -> errors.add(error) })
                val source = GutenbergSource(Engine())
                val owner = ApplicationSources(listOf(SourceOption("Original fixture", source)), sessionDispatcher = ui) { source.close() }
                val app = ApplicationSession(owner, scope); owner.attach(app)
                try {
                    withContext(ui) { app.homeDiscovery.refresh(DiscoveryPreferences(homeEnabled = true, showCatalogOnlySources = true)) }
                    withTimeout(5_000) { connection.entered.await() }
                    withContext(ui) { app.navigate(target) }
                    assertEquals(target, app.destination.value); assertFalse(app.homeDiscovery.state.value.loading)
                    assertNotSame(connection.ui, withTimeout(5_000) { connection.disconnected.await() })
                    withTimeout(5_000) {
                        // Source cleanup joins the abandoned operation before the serialized retry.
                        val retry = Connection(URL("https://example.invalid/original-fixture"), connection.ui).apply { hold = false }
                        TestUrls.next.set(retry)
                        withContext(ui) { app.navigate(Destination.HOME); app.homeDiscovery.refresh(DiscoveryPreferences(homeEnabled = true, showCatalogOnlySources = true)) }
                        app.homeDiscovery.state.first { !it.loading }
                    }
                    assertEquals(Destination.HOME, app.destination.value)
                    assertTrue(app.homeDiscovery.state.value.rows.isNotEmpty())
                    assertTrue(errors.isEmpty())
                } finally {
                    connection.readGate.countDown(); owner.close(); source.awaitClosed(); scope.cancel(); owner.awaitProgressClosed()
                }
            }
        }
    }
    @Test fun cancellingAnAdvertisedThumbnailAlsoUsesTheIoCleanupBoundary() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val uiThread = withContext(ui) { Thread.currentThread() }
            val ready = Connection(URL("https://example.invalid/original-fixture"), uiThread).apply { hold = false }
            TestUrls.next.set(ready)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + ui + CoroutineExceptionHandler { _, error -> errors.add(error) })
            val source = GutenbergSource(Engine())
            var image: Connection? = null
            try {
                source.discover(DiscoveryRequest("original fixture"))
                image = Connection(URL("https://example.invalid/original-fixture"), uiThread)
                TestUrls.next.set(image)
                val request = scope.async { source.coverThumbnail(PublicationId(source.id, "84")) }
                withTimeout(5_000) { image.entered.await() }
                withContext(ui) { request.cancel() }
                withTimeout(5_000) { request.join() }
                assertNotSame(uiThread, withTimeout(5_000) { image.disconnected.await() })
                assertTrue(errors.isEmpty())
            } finally {
                image?.readGate?.countDown(); source.close(); source.awaitClosed(); scope.cancel()
            }
        }
    }

    /** Models the C55 stream contract: close may read/discard the same stream and
     * throw if a blocking read is already active. No device framework or messages are faked. */
    private class HeldBody : InputStream() {
        val reading = CompletableDeferred<Unit>()
        val closeAttempted = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val safelyClosed = CompletableDeferred<Unit>()
        val releaseRead = CountDownLatch(1)
        val inRead = AtomicBoolean()
        val overlappingCloses = AtomicInteger()
        override fun read(): Int = read(ByteArray(1), 0, 1)
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            inRead.set(true)
            try {
                reading.complete(Unit)
                check(releaseRead.await(10, TimeUnit.SECONDS))
                return -1
            } finally { inRead.set(false) }
        }
        override fun close() {
            closeAttempted.complete(Unit)
            cancellationObserved.complete(Unit)
            if (inRead.get()) {
                overlappingCloses.incrementAndGet()
                throw IllegalStateException("Synthetic non-concurrent stream guard")
            }
            safelyClosed.complete(Unit)
        }
    }

    private fun productionStreamingEngine(connection: Connection, body: HeldBody): HttpClientEngine {
        val engine = platformHttpEngine()
        when (val config = engine.config) {
            is AndroidEngineConfig -> {
                connection.hold = false; connection.stream = body
                TestUrls.next.set(connection)
                return Engine(engine)
            }
            is OkHttpConfig -> {
                config.config { eventListener(object : EventListener() {
                    override fun canceled(call: Call) { body.cancellationObserved.complete(Unit) }
                }) }
                val first = AtomicBoolean(true)
                config.addInterceptor { chain ->
                    val responseBody = if (first.compareAndSet(true, false)) object : ResponseBody() {
                        private val buffered = body.source().buffer()
                        override fun contentType() = "application/opds+json".toMediaType()
                        override fun contentLength() = -1L
                        override fun source(): BufferedSource = buffered
                    } else (if (chain.request().url.encodedPath.endsWith("/search"))
                        previewPage(chain.request().url.queryParameter("query").orEmpty()) else previewRoot)
                        .toResponseBody("application/opds+json".toMediaType())
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Original fixture").header("Content-Type", "application/opds+json").body(responseBody).build()
                }
            }
            else -> error("Unsupported Android test transport")
        }
        return engine
    }

    @Test fun cancellingDuringAResponseReadMustNotPropagateConcurrentCloseFailures() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val body = HeldBody()
            val connection = Connection(URL("https://example.invalid/original-fixture"), withContext(ui) { Thread.currentThread() })
            val errors = ConcurrentLinkedQueue<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + ui + CoroutineExceptionHandler { _, error -> errors.add(error) })
            val source = GutenbergSource(productionStreamingEngine(connection, body))
            try {
                val request = scope.launch { source.search("original fixture") }
                withTimeout(5_000) { body.reading.await() }
                withContext(ui) { request.cancel() }
                withTimeout(5_000) { body.cancellationObserved.await() }
                assertEquals(42, withTimeout(5_000) { withContext(ui) { 42 } })
                body.releaseRead.countDown()
                withTimeout(5_000) { request.join() }
                assertTrue(errors.isEmpty(), "Concurrent body close must not escape through completion handlers: ${errors.map { it.javaClass.simpleName }}")
                assertEquals(0, body.overlappingCloses.get(), "The body must not close while its read is active")
                withTimeout(5_000) { body.safelyClosed.await() }
                assertTrue(request.isCancelled)
            } finally {
                body.releaseRead.countDown(); source.close(); source.awaitClosed(); scope.cancel()
            }
        }
    }

    @Test fun leavingHomeDuringBodyReadIsSafeForEveryDestinationAndReturn() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            repeat(3) {
                for (target in listOf(Destination.LIBRARY, Destination.HISTORY, Destination.SEARCH, Destination.SETTINGS)) {
                    val body = HeldBody()
                    val connection = Connection(URL("https://example.invalid/original-fixture"), withContext(ui) { Thread.currentThread() })
                    val errors = ConcurrentLinkedQueue<Throwable>()
                    val scope = CoroutineScope(SupervisorJob() + ui + CoroutineExceptionHandler { _, error -> errors.add(error) })
                    val source = GutenbergSource(productionStreamingEngine(connection, body))
                    val owner = ApplicationSources(listOf(SourceOption("Original fixture", source)), sessionDispatcher = ui) { source.close() }
                    val app = ApplicationSession(owner, scope); owner.attach(app)
                    try {
                        withContext(ui) { app.homeDiscovery.refresh(DiscoveryPreferences(homeEnabled = true, showCatalogOnlySources = true)) }
                        withTimeout(5_000) { body.reading.await() }
                        withContext(ui) { app.navigate(target) }
                        withTimeout(5_000) { body.cancellationObserved.await() }
                        assertEquals(target, app.destination.value); assertFalse(app.homeDiscovery.state.value.loading)
                        assertEquals(42, withTimeout(5_000) { withContext(ui) { 42 } })
                        body.releaseRead.countDown()
                        withTimeout(5_000) { body.safelyClosed.await() }
                        withContext(ui) { app.navigate(Destination.HOME); app.homeDiscovery.refresh(DiscoveryPreferences(homeEnabled = true, showCatalogOnlySources = true)) }
                        withTimeout(5_000) { app.homeDiscovery.state.first { !it.loading } }
                        assertTrue(app.homeDiscovery.state.value.rows.isNotEmpty())
                        assertTrue(errors.isEmpty())
                        assertEquals(0, body.overlappingCloses.get())
                    } finally {
                        body.releaseRead.countDown(); owner.close(); source.awaitClosed(); scope.cancel(); owner.awaitProgressClosed()
                    }
                }
            }
        }
    }

}
