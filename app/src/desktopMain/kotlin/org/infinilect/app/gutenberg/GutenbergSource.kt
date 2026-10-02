// SPDX-License-Identifier: GPL-3.0-only
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.infinilect.app.search.SearchException
import org.infinilect.core.Publication
import org.infinilect.core.PublicationId
import org.infinilect.core.PublicationResource
import org.infinilect.core.PublicationSource
import org.infinilect.core.ResourceContent
import org.infinilect.core.SearchPage
import org.infinilect.core.SourceId

private const val USER_AGENT = "INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)"

/** Search-only trusted desktop adapter. Owns its client/engine; close at application shutdown. */
class GutenbergSource(private val engine: HttpClientEngine = Java.create()) : PublicationSource, AutoCloseable {
    override val id = SourceId("gutenberg")
    private val requestLock = Mutex()
    @Volatile private var closed = false
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
    }

    override suspend fun search(query: String, pageToken: String?): SearchPage = requestLock.withLock {
        check(!closed) { "Gutenberg source is closed." }
        val normalizedQuery = query.trim()
        val firstUrl = GutenbergUrls.search(normalizedQuery)
        val url = if (pageToken == null) firstUrl else GutenbergUrls.pageUrl(pageToken, normalizedQuery)
        try {
            val bytes = client.prepareGet(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/atom+xml;profile=opds-catalog")
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                if (response.status.value != 200) throw SearchException(
                    "Project Gutenberg could not complete the request (HTTP ${response.status.value}). Please try again later."
                )
                if (response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase() != "application/atom+xml") {
                    throw InvalidOpdsException()
                }
                if (response.headers[HttpHeaders.ContentEncoding]?.lowercase()?.let { it != "identity" } == true) throw InvalidOpdsException()
                val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && (length < 0 || length > MAX_FEED_BYTES)) throw InvalidOpdsException()
                val channel = response.bodyAsChannel()
                try {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer, 0, minOf(buffer.size, MAX_FEED_BYTES - output.size() + 1))
                        if (count == -1) break
                        if (count > MAX_FEED_BYTES - output.size()) throw InvalidOpdsException()
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } finally {
                    channel.cancel(null)
                }
            }
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                GutenbergOpdsParser().parse(bytes, url, normalizedQuery) { context.ensureActive() }
            }
        } catch (error: HttpRequestTimeoutException) {
            throw SearchException("Project Gutenberg took too long to respond. Please try again.", error)
        } catch (error: IOException) {
            throw SearchException("Unable to reach Project Gutenberg. Check your connection and try again.", error)
        }
    }

    override suspend fun getPublication(publicationId: PublicationId): Publication? {
        require(publicationId.sourceId == id) { "Publication belongs to another source." }
        throw UnsupportedOperationException("Publication details are deferred to the acquisition/reading slice.")
    }

    override suspend fun loadResource(resource: PublicationResource): ResourceContent {
        require(resource.publicationId.sourceId == id) { "Resource belongs to another source." }
        throw UnsupportedOperationException("Resource acquisition is deferred to the acquisition/reading slice.")
    }

    override fun close() {
        if (closed) return
        closed = true
        client.close()
        engine.close()
    }
}
