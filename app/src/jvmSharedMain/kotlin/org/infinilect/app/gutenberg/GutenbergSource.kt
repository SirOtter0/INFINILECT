// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.infinilect.app.network.platformHttpEngine
import org.infinilect.app.network.PROJECT_USER_AGENT
import org.infinilect.app.search.SearchException
import org.infinilect.core.*
import org.infinilect.app.discovery.*

internal data class GutenbergHttpEvidence(val url: String, val status: Int) {
    val consumedBytes = AtomicLong()
}

/** Development catalog, not production acquisition. Application owns client/engine. */
internal class GutenbergSource(
    private val engine: HttpClientEngine = platformHttpEngine(),
    private val observe: (GutenbergHttpEvidence) -> Unit = {},
) : PublicationSource, DiscoverySource, AutoCloseable {
    override val id = GUTENBERG_ID
    private val requestLock = Mutex()
    private val imageGate = Semaphore(1)
    private val closed = AtomicBoolean()
    private val lifetime = SupervisorJob()
    private val client = HttpClient(engine) {
        followRedirects = false; expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 15_000; socketTimeoutMillis = 15_000 }
    }
    private val catalog = GutenbergCatalog(::bytes)
    private suspend fun <T> operation(serializedCatalog: Boolean = true, block: suspend () -> T): T {
        check(!closed.get()) { "Gutenberg source is closed." }
        // Each caller owns its child; source close also cancels all active requests.
        currentCoroutineContext().ensureActive()
        val request = CoroutineScope(currentCoroutineContext() + lifetime).async {
            if (serializedCatalog) requestLock.withLock { check(!closed.get()); userFacing(block) }
            else imageGate.withPermit { check(!closed.get()); userFacing(block) }
        }
        try { return request.await() } finally { request.cancel() }
    }
    override suspend fun search(query: String, pageToken: String?): SearchPage = operation { catalog.search(query, pageToken) }
    override suspend fun discover(request: DiscoveryRequest, token: String?): DiscoveryPage = operation {
        require(request.genre == null && request.query.isNotBlank())
        val entries = mutableListOf<DiscoveryEntry>()
        val result = catalog.search(request.query, token, entries::add)
        synchronized(thumbnailLock) {
            check(!closed.get())
            entries.forEach { entry -> entry.thumbnail?.let { url ->
                thumbnails.remove(entry.publication.id)
                if (thumbnails.size >= 100) thumbnails.remove(thumbnails.keys.first())
                thumbnails[entry.publication.id] = url
            } }
        }
        DiscoveryPage(entries, result.nextPageToken)
    }
    private val thumbnailLock = Any()
    private val thumbnails = linkedMapOf<PublicationId, String>()
    /** Only JPEG links actually advertised in a validated OPDS response, never guessed by ID.
     * No detail/enrichment request, redirects, cookie/auth flow, publication payload or disk cache. */
    internal suspend fun coverThumbnail(id: PublicationId): org.infinilect.app.covers.CoverArtwork? {
        owns(id)
        val url = synchronized(thumbnailLock) { thumbnails.remove(id)?.also { thumbnails[id] = it } } ?: return null
        // The one bounded cover worker must not hold mutable catalog state hostage
        // during image I/O/decode. Still source-owned, cancellable and single-flight.
        return operation(serializedCatalog = false) {
            val encoded = client.prepareGet(url) {
                header(HttpHeaders.UserAgent, PROJECT_USER_AGENT); header(HttpHeaders.Accept, "image/jpeg")
                header(HttpHeaders.AcceptEncoding, "identity")
                timeout { requestTimeoutMillis = 3_000; socketTimeoutMillis = 3_000 }
            }.execute { response ->
                val evidence = GutenbergHttpEvidence(url, response.status.value).also(observe)
                require(response.status.value == 200)
                require(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase() == "image/jpeg")
                require(response.headers[HttpHeaders.ContentEncoding]?.let { it != "identity" } != true)
                val declared = response.headers[HttpHeaders.ContentLength]?.let { requireNotNull(it.toLongOrNull()).also { size -> require(size in 1..524288) } }
                val channel = response.bodyAsChannel()
                try {
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer, 0, minOf(buffer.size, 524288 - output.size() + 1))
                        if (count == -1) break
                        require(count > 0 && output.size() + count <= 524288)
                        evidence.consumedBytes.addAndGet(count.toLong()); output.write(buffer, 0, count)
                    }
                    require(declared == null || declared == output.size().toLong())
                    output.toByteArray()
                } finally { channel.cancel(null) }
            }
            val dimensions = org.infinilect.app.media.rasterDimensions(encoded, "image/jpeg", currentCoroutineContext().job)
            require(dimensions.first <= 512 && dimensions.second <= 768)
            org.infinilect.app.covers.CoverArtwork(null, org.infinilect.app.covers.decodeCoverThumbnail(encoded, "image/jpeg"))
        }
    }
    private fun owns(id: PublicationId) { require(id.sourceId == this.id); GutenbergUrls.identifier(id.localId) }
    override suspend fun getPublication(publicationId: PublicationId): Publication? {
        owns(publicationId); return operation { catalog.getPublication(publicationId) }
    }
    override suspend fun loadResource(resource: PublicationResource): ResourceContent {
        owns(resource.publicationId)
        check(!closed.get())
        // Even forged/stored TEXT refs cannot enable the removed RDF/files path.
        throw SearchException(GUTENBERG_UNSUPPORTED)
    }
    private suspend fun <T> userFacing(block: suspend () -> T): T = try { block() }
    catch (error: CancellationException) { throw error }
    catch (error: IllegalArgumentException) { throw error }
    catch (error: IllegalStateException) { throw error }
    catch (error: SearchException) { throw error }
    catch (error: HttpRequestTimeoutException) { throw SearchException("Project Gutenberg's experimental service took too long to respond.", error) }
    catch (error: Exception) { throw SearchException("Unable to reach Project Gutenberg's experimental catalog. Please try again later.", error) }

    private suspend fun bytes(url: String, absentAllowed: Boolean): ByteArray? = client.prepareGet(url) {
        header(HttpHeaders.UserAgent, PROJECT_USER_AGENT)
        header(HttpHeaders.Accept, "application/opds+json, application/opds-publication+json, application/json")
        header(HttpHeaders.AcceptEncoding, "identity")
    }.execute { response ->
        val evidence = GutenbergHttpEvidence(url, response.status.value).also(observe)
        val channel = response.bodyAsChannel()
        try {
            if (absentAllowed && response.status.value == 404) return@execute null
            if (response.status.value != 200) throw SearchException("Project Gutenberg's experimental service could not complete the request. Please try again later.")
            val expected = if (url.contains("/publications?")) "application/opds-publication+json" else "application/opds+json"
            val type = try { ContentType.parse(response.headers[HttpHeaders.ContentType] ?: "") } catch (_: Exception) { throw InvalidOpdsException() }
            if (type.withoutParameters().toString() !in setOf(expected, "application/json") ||
                type.parameters.any { it.name.lowercase() != "charset" || it.value.lowercase() != "utf-8" } || type.parameters.size > 1) throw InvalidOpdsException()
            if (response.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()?.let { it != "identity" } == true) throw InvalidOpdsException()
            val length = response.headers[HttpHeaders.ContentLength]?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) throw InvalidOpdsException()
                it.toLongOrNull()?.takeIf { size -> size in 0..MAX_FEED_BYTES } ?: throw InvalidOpdsException()
            }
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val requested = minOf(buffer.size, MAX_FEED_BYTES - output.size() + 1)
                val count = channel.readAvailable(buffer, 0, requested)
                if (count == -1) break
                if (count !in 1..requested || count > MAX_FEED_BYTES - output.size()) throw InvalidOpdsException()
                evidence.consumedBytes.addAndGet(count.toLong()); output.write(buffer, 0, count)
            }
            if (length != null && length != output.size().toLong()) throw InvalidOpdsException()
            output.toByteArray()
        } finally { channel.cancel(null) }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(thumbnailLock) { thumbnails.clear() }
        lifetime.cancel(); client.close(); engine.close()
    }
}
