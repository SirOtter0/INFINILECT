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
import org.infinilect.app.network.platformHttpEngine
import org.infinilect.app.network.PROJECT_USER_AGENT
import org.infinilect.app.reader.MAX_TEXT_DOCUMENT_BYTES
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

/** Diagnostic counts only. Opt-in checks may report public endpoint/status, never bodies. */
internal data class GutenbergHttpEvidence(val url: String, val status: Int, val location: String?) {
    val consumedBytes = AtomicLong()
}

/** Application-owned source. Catalog and fresh RDF acquisition are independent adapters. */
internal class GutenbergSource(
    private val engine: HttpClientEngine = platformHttpEngine(),
    private val openXml: (ByteArray) -> OpdsXmlReader = ::platformOpdsXmlReader,
    private val observe: (GutenbergHttpEvidence) -> Unit = {},
    streamDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PublicationSource, AutoCloseable {
    override val id = GUTENBERG_ID
    private val requestLock = Mutex()
    private val closed = AtomicBoolean()
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<GutenbergResourceContent>()
    private val scope = CoroutineScope(SupervisorJob() + streamDispatcher)
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }
    private val catalog = GutenbergCatalog({ url, type -> bytes(url, type) }, openXml)

    override suspend fun search(query: String, pageToken: String?): SearchPage = requestLock.withLock {
        check(!closed.get()) { "Gutenberg source is closed." }
        userFacing { catalog.search(query, pageToken) }
    }

    private fun owns(publicationId: PublicationId) {
        require(publicationId.sourceId == id) { "Publication belongs to another source." }
        GutenbergAcquisition.identifier(publicationId.localId)
    }

    override suspend fun getPublication(publicationId: PublicationId): Publication? {
        owns(publicationId)
        return requestLock.withLock { userFacing { book(publicationId)?.publication } }
    }

    private suspend fun book(id: PublicationId): GutenbergBook? {
        val bytes = bytes(GutenbergAcquisition.metadata(id.localId), "application/rdf+xml", absentAllowed = true) ?: return null
        return withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            GutenbergRdfParser(openXml).parse(bytes, id) { context.ensureActive() }
        }
    }

    private suspend fun <T> userFacing(block: suspend () -> T): T = try { block() }
    catch (error: CancellationException) { throw error }
    catch (error: IllegalArgumentException) { throw error }
    catch (error: IllegalStateException) { throw error }
    catch (error: SearchException) { throw error }
    catch (error: HttpRequestTimeoutException) { throw SearchException("Project Gutenberg took too long to respond. Please try again.", error) }
    catch (error: Exception) { throw SearchException("Unable to reach Project Gutenberg. Please try again later.", error) }

    private fun HttpResponse.evidence(url: String) = GutenbergHttpEvidence(url, status.value, headers[HttpHeaders.Location]).also(observe)
    private fun HttpResponse.length(max: Long): Long? {
        val raw = headers[HttpHeaders.ContentLength] ?: return null
        if (!Regex("[0-9]{1,20}").matches(raw)) throw InvalidOpdsException()
        return raw.toLongOrNull()?.takeIf { it in 0..max } ?: throw InvalidOpdsException()
    }
    private fun HttpResponse.uncompressed() {
        if (headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()?.let { it != "identity" } == true) throw InvalidOpdsException()
    }

    /** Lock owned by caller. Metadata never follows redirects or accepts HTML. */
    private suspend fun bytes(url: String, type: String, absentAllowed: Boolean = false): ByteArray? {
        check(!closed.get()) { "Gutenberg source is closed." }
        return client.prepareGet(url) {
            header(HttpHeaders.UserAgent, PROJECT_USER_AGENT)
            header(HttpHeaders.Accept, type)
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            val evidence = response.evidence(url)
            if (absentAllowed && response.status.value == 404) return@execute null
            if (response.status.value != 200) throw SearchException("Project Gutenberg could not complete the request (HTTP ${response.status.value}). Please try again later.")
            if (response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase() != type) throw InvalidOpdsException()
            response.uncompressed()
            val declared = response.length(MAX_FEED_BYTES.toLong())
            val channel = response.bodyAsChannel()
            try {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val requested = minOf(buffer.size, MAX_FEED_BYTES - output.size() + 1)
                    val count = channel.readAvailable(buffer, 0, requested)
                    if (count == -1) break
                    if (count !in 1..requested || count > MAX_FEED_BYTES - output.size()) throw InvalidOpdsException()
                    evidence.consumedBytes.addAndGet(count.toLong())
                    output.write(buffer, 0, count)
                }
                if (declared != null && declared != output.size().toLong()) throw InvalidOpdsException()
                output.toByteArray()
            } finally { channel.cancel(null) }
        }
    }

    override suspend fun loadResource(resource: PublicationResource): ResourceContent {
        owns(resource.publicationId)
        require(resource == GutenbergAcquisition.resource(resource.publicationId)) { "Invalid Gutenberg TEXT resource." }
        return userFacing {
            val ready = CompletableDeferred<ResourceContent>()
            val producer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    requestLock.withLock {
                        check(!closed.get()) { "Gutenberg source is closed." }
                        // Refresh under the acquisition lock. Search/library metadata is never authority.
                        val current = book(resource.publicationId) ?: throw InvalidOpdsException()
                        val file = current.text ?: throw SearchException("No compatible UTF-8 text edition is available from Project Gutenberg.")
                        if (file.size > MAX_TEXT_DOCUMENT_BYTES) throw SearchException("This book is larger than the current TEXT reader limit.")
                        stream(file.url, file, resource.publicationId.localId, ready, emptySet())
                    }
                } catch (error: Throwable) { ready.completeExceptionally(error) }
            }
            producer.invokeOnCompletion { error -> if (!ready.isCompleted) ready.completeExceptionally(error ?: InvalidOpdsException()) }
            try { ready.await() } catch (error: Throwable) { producer.cancel(); throw error }
        }
    }

    private suspend fun stream(url: String, file: GutenbergTextFile, id: String,
        ready: CompletableDeferred<ResourceContent>, visited: Set<String>) {
        if (url in visited || visited.size > 2) throw InvalidOpdsException()
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, PROJECT_USER_AGENT)
            header(HttpHeaders.Accept, GUTENBERG_TEXT_MIME)
            header(HttpHeaders.AcceptEncoding, "identity")
            timeout { requestTimeoutMillis = 60_000 }
        }.execute { response ->
            val evidence = response.evidence(url)
            if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                val raw = response.headers[HttpHeaders.Location] ?: throw InvalidOpdsException()
                val target = GutenbergAcquisition.redirect(url, raw, id, file.url)
                response.bodyAsChannel().cancel(null)
                stream(target, file, id, ready, visited + url)
            } else {
                if (response.status.value != 200 || !GutenbergAcquisition.utf8(response.headers[HttpHeaders.ContentType] ?: "")) throw InvalidOpdsException()
                response.uncompressed()
                val length = response.length(MAX_TEXT_DOCUMENT_BYTES.toLong())
                if (length == null || length != file.size) throw InvalidOpdsException()
                val channel = response.bodyAsChannel()
                val lifetime = CompletableDeferred<Unit>()
                val job = currentCoroutineContext().job
                lateinit var content: GutenbergResourceContent
                content = GutenbergResourceContent(channel, length, evidence) {
                    active.remove(content); lifetime.complete(Unit); job.cancel()
                }
                active.add(content)
                if (closed.get()) { content.close(); throw IllegalStateException("Gutenberg source is closed.") }
                try {
                    if (!ready.complete(content)) content.close()
                    withTimeout(60_000) { lifetime.await() }
                } finally { content.close() }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        active.forEach { it.close() }
        client.close()
        engine.close()
    }
}

internal class GutenbergResourceContent(
    private val channel: ByteReadChannel,
    override val sizeBytes: Long,
    private val evidence: GutenbergHttpEvidence,
    private val abort: () -> Unit,
) : ResourceContent {
    private val closed = AtomicBoolean()
    private var consumed = 0L
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset in 0..buffer.size && length in 0..buffer.size - offset)
        check(!closed.get()) { "Resource is closed." }
        if (length == 0) return 0
        try {
            currentCoroutineContext().ensureActive()
            val requested = minOf(length.toLong(), sizeBytes - consumed + 1).toInt()
            val count = channel.readAvailable(buffer, offset, requested)
            if (count == -1) { if (consumed != sizeBytes) throw InvalidOpdsException(); return -1 }
            if (count !in 1..requested) throw InvalidOpdsException()
            consumed += count
            evidence.consumedBytes.addAndGet(count.toLong())
            if (consumed > sizeBytes) throw InvalidOpdsException()
            return count
        } catch (error: Throwable) { close(); throw error }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.cancel(null)
        abort()
    }
}
