// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.discovery.*
import org.infinilect.app.network.*
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

internal const val OAPEN_LOOKUP_HELP = "OAPEN currently supports lookup by handle (for example 20.500.12657/25287). Use its official catalog for title, author or subject searches."
internal const val OAPEN_METADATA_ERROR = "OAPEN metadata is unavailable or invalid. Open the official catalog or retry later."
private class OapenCatalogException(kind: CatalogErrorKind) : CatalogSourceException(kind) {
    override val message: String get() = OAPEN_METADATA_ERROR
}

/** Verified identifier-only metadata adapter. Deliberately NOT an OfflineDownloadProvider:
 * REST search and file transfer are denied in the current integration environment.
 * Never converts a license string or announced URL into permission to fetch bytes. */
internal class OapenSource(private val engine: HttpClientEngine = platformHttpEngine(),
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default) :
    PublicationSource, PublicationBibliographySource, AutoCloseable {
    override val id = OAPEN_SOURCE_ID
    private val closed = AtomicBoolean()
    private val gate = Mutex()
    private val client = HttpClient(engine) {
        followRedirects = false; expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 5_000; socketTimeoutMillis = 10_000; requestTimeoutMillis = 15_000 }
    }
    override suspend fun search(query: String, pageToken: String?): SearchPage {
        require(pageToken == null) { "Identifier lookup has no pagination" }
        val handle = oapenHandle(query) ?: throw SearchException(OAPEN_LOOKUP_HELP)
        return SearchPage(listOfNotNull(getPublication(PublicationId(id, handle))))
    }
    override suspend fun getPublication(publicationId: PublicationId): Publication? = bibliography(publicationId)?.publication
    override suspend fun bibliography(id: PublicationId): PublicationBibliography? {
        require(id.sourceId == this.id && oapenHandle(id.localId) == id.localId)
        try {
            val bytes = gate.withLock {
                check(!closed.get())
                val url = URLBuilder("https://library.oapen.org/oai/request").apply {
                    parameters.append("verb", "GetRecord")
                    parameters.append("identifier", "oai:library.oapen.org:${id.localId}")
                    parameters.append("metadataPrefix", "xoai")
                }.buildString()
                client.prepareGet(url) {
                    header(HttpHeaders.UserAgent, PROJECT_USER_AGENT)
                    header(HttpHeaders.Accept, "application/xml, text/xml")
                    header(HttpHeaders.AcceptEncoding, "identity")
                }.execute { response ->
                    // No redirects, challenge solving, retries or parsing of HTTP error bodies.
                    if (response.status.value != 200) throw OapenCatalogException(when (response.status.value) {
                        401, 403 -> CatalogErrorKind.HTTP_FORBIDDEN
                        429 -> CatalogErrorKind.HTTP_RATE_LIMITED
                        in 300..399 -> CatalogErrorKind.REDIRECT
                        else -> CatalogErrorKind.HTTP_ERROR
                    })
                    if (response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase() !in setOf("application/xml", "text/xml")) throw InvalidOapenMetadata()
                    if (response.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()?.let { it != "identity" } == true) throw InvalidOapenMetadata()
                    val length = response.headers[HttpHeaders.ContentLength]?.let { it.toLongOrNull()?.takeIf { n -> n in 0..OAPEN_METADATA_LIMIT } ?: throw InvalidOapenMetadata() }
                    val channel = response.bodyAsChannel()
                    try {
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = channel.readAvailable(buffer, 0, minOf(buffer.size, OAPEN_METADATA_LIMIT - out.size() + 1))
                            if (count == -1) break
                            if (count !in 1..(OAPEN_METADATA_LIMIT - out.size())) throw InvalidOapenMetadata()
                            out.write(buffer, 0, count)
                        }
                        if (length != null && length != out.size().toLong()) throw InvalidOapenMetadata()
                        out.toByteArray()
                    } finally { channel.cancel(null) }
                }
            }
            return withContext(parsingDispatcher) {
                val context = currentCoroutineContext()
                oapenMetadata(bytes, id) { context.ensureActive(); if (closed.get()) throw CancellationException() }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: SearchException) { throw e }
        catch (_: InvalidOapenMetadata) { throw OapenCatalogException(CatalogErrorKind.RESPONSE_FORMAT) }
        catch (_: io.ktor.client.plugins.HttpRequestTimeoutException) { throw OapenCatalogException(CatalogErrorKind.TIMEOUT) }
        catch (_: Exception) { throw OapenCatalogException(CatalogErrorKind.CONNECTION) }
    }
    override suspend fun loadResource(resource: PublicationResource): ResourceContent {
        require(resource.publicationId.sourceId == id)
        throw SearchException("This source does not provide authorized retained downloads in INFINILECT.")
    }
    override fun close() { if (closed.compareAndSet(false, true)) { client.close(); engine.close() } }
}
