// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

/** Real configured transport; interceptors and loopback sockets are synthetic.
 * No live provider, publication payload, custom trust manager or TLS exception. */
class AndroidHttpEngineTest {
    @Test fun policiesAndTimeoutVariantsKeepResourcesBoundedAndOwned() = runBlocking {
        val engine = platformHttpEngine()
        val config = assertIs<OkHttpConfig>(engine.config)
        assertEquals(2, config.clientCacheSize)
        val observed = ConcurrentLinkedQueue<OkHttpClient>()
        config.config { observed.add(build()) }
        config.addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Original fixture").body("original".toResponseBody()).build()
        }
        val client = HttpClient(engine) { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 15_000 } }
        try {
            for (socketTimeout in listOf(15_000L, 3_000L, 2_000L)) {
                client.prepareGet("https://example.invalid/original-fixture") {
                    timeout { socketTimeoutMillis = socketTimeout }
                }.execute { assertEquals("original", it.bodyAsText()) }
            }
            assertEquals(3, observed.size)
            val first = observed.first()
            val defaultHostnameVerifier = OkHttpClient().hostnameVerifier
            for (native in observed) {
                assertFalse(native.followRedirects); assertFalse(native.followSslRedirects)
                assertFalse(native.retryOnConnectionFailure)
                assertNull(native.cache); assertSame(CookieJar.NO_COOKIES, native.cookieJar)
                assertSame(defaultHostnameVerifier, native.hostnameVerifier)
                assertEquals(5_000, native.connectTimeoutMillis)
                assertEquals(2, native.dispatcher.maxRequests); assertEquals(2, native.dispatcher.maxRequestsPerHost)
                assertSame(first.dispatcher, native.dispatcher); assertSame(first.connectionPool, native.connectionPool)
            }
        } finally {
            client.close(); engine.close()
            observed.firstOrNull()?.let { native ->
                assertTrue(withContext(Dispatchers.IO) { native.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS) })
                assertEquals(0, native.dispatcher.runningCallsCount())
                assertEquals(0, native.dispatcher.queuedCallsCount())
                assertEquals(0, native.connectionPool.connectionCount())
            }
        }
    }

    private class LocalServer : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); soTimeout = 5_000 }
        private val accepted = AtomicReference<Socket>()
        val url = "http://127.0.0.1:${server.localPort}"
        private val worker = Executors.newSingleThreadExecutor()
        fun serve(block: (Socket) -> Unit) = worker.submit {
            server.accept().use { socket ->
                accepted.set(socket); socket.soTimeout = 5_000
                val input = socket.getInputStream()
                var previous = 0; var blankLines = 0; var total = 0
                while (blankLines < 2) {
                    val value = input.read(); check(value != -1 && ++total <= 8192)
                    if (value == 10) { blankLines = if (previous == 13) blankLines + 1 else 0 }
                    else if (value != 13) blankLines = 0
                    previous = value
                }
                block(socket)
            }
        }
        override fun close() {
            server.close(); accepted.get()?.close(); worker.shutdownNow()
            check(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun redirectsRemainBlockedByTheRealEngineForHttpAndHttpsTargets() = runBlocking {
        for (scheme in listOf("http", "https")) LocalServer().use { server ->
            val response = server.serve { socket ->
                socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: ${server.url.replaceFirst("http:", "$scheme:")}/target\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
            }
            val engine = platformHttpEngine()
            val client = HttpClient(engine) { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 3_000 } }
            try {
                client.prepareGet(server.url + "/original-fixture").execute { assertEquals(302, it.status.value) }
                withContext(Dispatchers.IO) { response.get(5, TimeUnit.SECONDS) }
            } finally { client.close(); engine.close() }
        }
    }

    @Test fun cancellingAnActualStalledBodyUnblocksItsReadBeforeTheServerFinishes() = runBlocking {
        LocalServer().use { server ->
            val finishServer = CountDownLatch(1)
            val headersSent = CompletableDeferred<Unit>()
            val serverJob = server.serve { socket ->
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray()); flush() }
                headersSent.complete(Unit)
                check(finishServer.await(10, TimeUnit.SECONDS))
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
                val errors = ConcurrentLinkedQueue<Throwable>()
                val scope = CoroutineScope(SupervisorJob() + ui + CoroutineExceptionHandler { _, error -> errors.add(error) })
                val engine = platformHttpEngine()
                val client = HttpClient(engine) { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 15_000 } }
                val consuming = CompletableDeferred<Unit>()
                try {
                    val request = scope.launch {
                        client.prepareGet(server.url + "/original-fixture").execute {
                            consuming.complete(Unit)
                            it.bodyAsChannel().readAvailable(ByteArray(1))
                        }
                    }
                    withTimeout(5_000) { headersSent.await(); consuming.await() }
                    withContext(ui) { request.cancel() }
                    withTimeout(5_000) { request.join() }
                    assertTrue(request.isCancelled); assertTrue(errors.isEmpty())
                    assertEquals(1L, finishServer.count, "Cancellation must finish without waiting for a server response")
                    assertEquals(42, withTimeout(5_000) { withContext(ui) { 42 } })
                } finally {
                    finishServer.countDown(); client.close(); engine.close(); scope.cancel()
                    withContext(Dispatchers.IO) { serverJob.get(5, TimeUnit.SECONDS) }
                }
            }
        }
    }
}
