// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val OAPEN_SEARCH_URL = "https://library.oapen.org/rest/search"
internal const val OAPEN_PROBE_MAX_BYTES = 2 * 1024 * 1024

/** Transport evidence only: does not claim to parse publications or support acquisition. */
internal data class OapenApiObservation(val httpStatus: Int, val responseBytes: Int)

/** Explicit diagnostic for the documented endpoint; not a PublicationSource implementation. */
internal class OapenApiProbe(private val engine: HttpClientEngine = Java.create()) : AutoCloseable {
    private val lock = Mutex()
    private var closed = false
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
    }

    suspend fun inspect(query: String): OapenApiObservation = lock.withLock {
        check(!closed) { "OAPEN access probe is closed." }
        val normalized = query.trim()
        require(normalized.isNotEmpty() && normalized.length <= 256) { "Use 1–256 query characters." }
        val url = URLBuilder(OAPEN_SEARCH_URL).apply {
            parameters.append("query", normalized)
            parameters.append("expand", "metadata,bitstreams")
            parameters.append("limit", "10")
            parameters.append("offset", "0")
        }.buildString()
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, "INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)")
            header(HttpHeaders.Accept, "application/xml")
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            val channel = response.bodyAsChannel()
            try {
                // Do not consume/log error pages, follow redirects, or guess an alternative API.
                if (response.status.value != 200) return@execute OapenApiObservation(response.status.value, 0)
                check(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase() == "application/xml") {
                    "OAPEN returned an unexpected response type."
                }
                check(response.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()?.let { it == "identity" } != false) {
                    "OAPEN returned unsupported compression."
                }
                response.headers[HttpHeaders.ContentLength]?.let { value ->
                    val length = value.toLongOrNull()
                    check(length != null && length in 0..OAPEN_PROBE_MAX_BYTES.toLong()) {
                        "OAPEN returned an invalid or oversized response length."
                    }
                }
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.readAvailable(buffer, 0, minOf(buffer.size, OAPEN_PROBE_MAX_BYTES - total + 1))
                    if (count == -1) break
                    check(count <= OAPEN_PROBE_MAX_BYTES - total) { "OAPEN response exceeds the diagnostic limit." }
                    total += count
                }
                // Bytes are discarded. HTTP 200 is not evidence of a valid publication mapping.
                OapenApiObservation(response.status.value, total)
            } finally {
                channel.cancel(null)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        client.close()
        engine.close()
    }
}
