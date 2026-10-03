// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.search.SearchException
import org.infinilect.app.network.PROJECT_USER_AGENT
import org.infinilect.core.*


/** Application-consumed bytes, excluding transport buffering; never includes book contents. */
internal data class ArchiveHttpEvidence(val url: String, val status: Int, val location: String?) {
    val consumedBytes = AtomicLong()
}

/** Deliberately narrow public-CC0 text adapter, independent of the current Gutenberg UI. */
internal class InternetArchiveSource(
    private val engine: HttpClientEngine = Java.create(),
    private val userAgent: String = PROJECT_USER_AGENT,
    private val observe: (ArchiveHttpEvidence) -> Unit = {},
    streamDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PublicationSource, AutoCloseable {
    override val id = ARCHIVE_ID
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + streamDispatcher)
    private val closed = AtomicBoolean()
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
    }

    override suspend fun search(query: String, pageToken: String?): SearchPage {
        val normalized = ArchiveUrls.query(query)
        val page = if (pageToken == null) 1 else ArchiveUrls.page(pageToken, normalized)
        return userFacing {
            val bytes = metadataBytes(ArchiveUrls.search(normalized, page))
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                ArchiveMetadata.search(bytes, normalized, page) { context.ensureActive() }
            }
        }
    }

    private fun owns(publicationId: PublicationId) {
        require(publicationId.sourceId == id) { "Publication belongs to another source." }
        ArchiveUrls.identifier(publicationId.localId)
    }

    override suspend fun getPublication(publicationId: PublicationId): Publication? {
        owns(publicationId)
        return userFacing { item(publicationId)?.publication }
    }

    private suspend fun item(id: PublicationId): ArchiveItem? {
        val bytes = metadataBytes(ArchiveUrls.metadata(id.localId))
        return parseItem(bytes, id)
    }

    private suspend fun parseItem(bytes: ByteArray, id: PublicationId): ArchiveItem? {
        return withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            ArchiveMetadata.item(bytes, id.localId) { context.ensureActive() }
        }
    }

    private suspend fun <T> userFacing(block: suspend () -> T): T = try { block() }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { throw SearchException("Internet Archive could not complete the request. Please try again later.", error) }

    private fun HttpResponse.evidence(url: String): ArchiveHttpEvidence =
        ArchiveHttpEvidence(url, status.value, headers[HttpHeaders.Location]).also(observe)

    private fun HttpResponse.length(maximum: Long): Long? {
        val raw = headers[HttpHeaders.ContentLength] ?: return null
        val size = raw.toLongOrNull() ?: throw InvalidArchiveData()
        if (size !in 0..maximum) throw InvalidArchiveData()
        return size
    }

    private fun HttpResponse.uncompressed() {
        if (headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()?.let { it != "identity" } == true) throw InvalidArchiveData()
    }

    private suspend fun metadataBytes(url: String): ByteArray = lock.withLock { metadataBytesLocked(url) }

    /** Caller owns lock, allowing refresh/open to remain one serialized acquisition operation. */
    private suspend fun metadataBytesLocked(url: String): ByteArray {
        check(!closed.get()) { "Source is closed." }
        return client.prepareGet(url) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            val evidence = response.evidence(url)
            if (response.status.value != 200 || response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase() != "application/json")
                throw InvalidArchiveData()
            response.uncompressed()
            val declared = response.length(MAX_METADATA_BYTES.toLong())
            val channel = response.bodyAsChannel()
            try {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.readAvailable(buffer, 0, minOf(buffer.size, MAX_METADATA_BYTES - output.size() + 1))
                    if (count == -1) break
                    evidence.consumedBytes.addAndGet(count.toLong())
                    if (count > MAX_METADATA_BYTES - output.size()) throw InvalidArchiveData()
                    output.write(buffer, 0, count)
                }
                if (declared != null && declared != output.size().toLong()) throw InvalidArchiveData()
                output.toByteArray()
            } finally { channel.cancel(null) }
        }
    }

    override suspend fun loadResource(resource: PublicationResource): ResourceContent {
        owns(resource.publicationId)
        ArchiveUrls.filename(resource.key)
        require(resource.revision == null) { "Verified resource revisions are not implemented." }
        return userFacing {
            val ready = CompletableDeferred<ResourceContent>()
            val producer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    lock.withLock {
                        check(!closed.get())
                        // Refresh after queueing, immediately before open; no other request can intervene.
                        val item = parseItem(metadataBytesLocked(ArchiveUrls.metadata(resource.publicationId.localId)), resource.publicationId)
                            ?: throw InvalidArchiveData()
                        val file = item.files.singleOrNull { it.resource == resource } ?: throw InvalidArchiveData()
                        stream(ArchiveUrls.download(resource.publicationId.localId, resource.key), file, item.locations, ready, emptySet())
                    }
                } catch (error: Throwable) { ready.completeExceptionally(error) }
            }
            producer.invokeOnCompletion { error -> if (!ready.isCompleted) ready.completeExceptionally(error ?: InvalidArchiveData()) }
            try { ready.await() } catch (error: Throwable) { producer.cancel(); throw error }
        }
    }

    private suspend fun stream(url: String, file: ArchiveFile, locations: Set<ArchiveLocation>, ready: CompletableDeferred<ResourceContent>, visited: Set<String>) {
        if (url in visited || visited.size > 2) throw InvalidArchiveData()
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, file.resource.mediaType)
            header(HttpHeaders.AcceptEncoding, "identity")
            timeout { requestTimeoutMillis = 60_000 }
        }.execute { response ->
            val evidence = response.evidence(url)
            if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                val location = response.headers[HttpHeaders.Location] ?: throw InvalidArchiveData()
                val target = ArchiveUrls.redirect(url, location, file.resource.publicationId.localId, file.resource.key, locations)
                response.bodyAsChannel().cancel(null)
                stream(target, file, locations, ready, visited + url)
            } else {
                if (response.status.value != 200) throw InvalidArchiveData()
                val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                if (type != file.resource.mediaType) throw InvalidArchiveData()
                response.uncompressed()
                val declared = response.length(MAX_RESOURCE_BYTES)
                if (declared != null && declared != file.size) throw InvalidArchiveData()
                val channel = response.bodyAsChannel()
                val lifetime = CompletableDeferred<Unit>()
                val job = currentCoroutineContext().job
                val content = ArchiveResourceContent(channel, file.size, evidence) { lifetime.complete(Unit); job.cancel() }
                try {
                    if (!ready.complete(content)) content.close()
                    // Ktor's public streaming execute scope must stay alive while the handle is owned.
                    // An idle consumer must not strand the source mutex after transport completion/timeout.
                    withTimeout(60_000) { lifetime.await() }
                } finally { content.close() }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        client.close()
        engine.close()
    }
}

internal class ArchiveResourceContent(
    private val channel: ByteReadChannel,
    override val sizeBytes: Long,
    private val evidence: ArchiveHttpEvidence,
    private val abort: () -> Unit,
) : ResourceContent {
    private val closed = AtomicBoolean()
    private var consumed = 0L

    init { require(sizeBytes in 1..MAX_RESOURCE_BYTES) }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset in 0..buffer.size && length in 0..(buffer.size - offset))
        check(!closed.get()) { "Resource is closed." }
        if (length == 0) return 0
        try {
            currentCoroutineContext().ensureActive()
            val requested = minOf(length.toLong(), sizeBytes - consumed + 1).toInt()
            val count = channel.readAvailable(buffer, offset, requested)
            if (count == -1) {
                if (consumed != sizeBytes) throw InvalidArchiveData()
                return -1
            }
            if (count !in 1..requested) throw InvalidArchiveData()
            consumed += count
            evidence.consumedBytes.addAndGet(count.toLong())
            if (consumed > sizeBytes || consumed > MAX_RESOURCE_BYTES) throw InvalidArchiveData()
            return count
        } catch (error: Throwable) { close(); throw error }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.cancel(null)
        abort()
    }
}
