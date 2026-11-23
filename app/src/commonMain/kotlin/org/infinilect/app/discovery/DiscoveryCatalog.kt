// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.infinilect.core.*

/** Shared session catalog, not an acquisition/cache of publication bytes.
 * Two active metadata requests, eight 25-entry LRU pages, five-minute TTL.
 * Same-key callers borrow one task; the last cancelling borrower retires it. */
internal class DiscoveryTimeout : Exception("Catalog request timed out")
internal class DiscoveryInterrupted : Exception("Catalog transport interrupted")
internal enum class DiscoveryFailure { TIMEOUT, TRANSPORT_CANCELLED, INVALID_RESPONSE, REQUEST_FAILED }
internal data class DiscoveryDiagnostic(val source: SourceId, val failure: DiscoveryFailure)

internal class DiscoveryCatalog(private val sources: List<PublicationSource>,
    scope: CoroutineScope, private val diagnostics: (DiscoveryDiagnostic) -> Unit = {}, private val now: () -> Long) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job)
    private val gate = Semaphore(2)
    private val lock = Mutex()
    private data class Key(val source: SourceId, val request: DiscoveryRequest, val token: String?)
    private data class Cached(val page: DiscoveryPage, val at: Long)
    private data class Pending(val task: Deferred<DiscoveryPage>, var borrowers: Int)
    private val pages = linkedMapOf<Key, Cached>()
    private val pending = mutableMapOf<Key, Pending>()
    suspend fun load(id: SourceId, request: DiscoveryRequest, token: String? = null): DiscoveryPage {
        val source = sources.first { it.id == id }
        val effective = if (source is ReadabilityCatalogSource) request else request.copy(readabilityMode = ReadabilityMode.READABLE, assessCandidates = true)
        val key = Key(id, effective, token)
        var hit: DiscoveryPage? = null
        val lease = lock.withLock {
            job.ensureActive()
            pages.remove(key)?.let { cached ->
                if (now() - cached.at in 0..300_000) { pages[key] = cached; hit = cached.page }
            }
            if (hit != null) null else pending[key]?.also { it.borrowers++ } ?: Pending(scope.async(start = CoroutineStart.LAZY) {
                gate.withPermit {
                    try { withTimeout(20_000) {
                        val page = if (source is ReadabilityCatalogSource) source.discoverReadable(request, token)
                            else if (source is DiscoverySource) source.discover(effective, token)
                            else {
                                val offset = token?.removePrefix("local1.")?.toIntOrNull() ?: if (token == null) 0 else error("Invalid local page")
                                require(offset in 0..75 && offset % 25 == 0 && (token == null || token == "local1.$offset"))
                                source.search(request.query, null).let { result -> DiscoveryPage(result.publications.drop(offset).take(25).map(::DiscoveryEntry),
                                    if (result.publications.size > offset + 25) "local1.${offset + 25}" else null) }
                            }
                        require(page.entries.size <= 25 && page.entries.all { it.publication.id.sourceId == id })
                        require(page.nextToken == null || page.nextToken.length <= 2048)
                        val filtered = page.entries.map(::boundedEntry).distinctBy { it.publication.id }
                            .filter { entry -> request.language == null || entry.publication.languages.any { languageMatches(it, request.language) } }
                        require(page.candidates.size <= 25 && page.candidates.all { it.publication.id.sourceId == id &&
                            it.readability in setOf(CatalogReadability.READABLE_CANDIDATE, CatalogReadability.TEMPORARY_ERROR) })
                        page.copy(entries = filtered, candidates = page.candidates.distinctBy { it.publication.id }.map(::boundedEntry)
                            .filter { request.language == null || it.publication.languages.any { tag -> languageMatches(tag, request.language) } })
                    } } catch (_: TimeoutCancellationException) { throw DiscoveryTimeout() }
                }
            }, 1).also { pending[key] = it }
        }
        hit?.let { return it }
        val work = checkNotNull(lease)
        try {
            val page = work.task.await()
            currentCoroutineContext().ensureActive()
            lock.withLock {
                job.ensureActive()
                if (source !is ReadabilityCatalogSource) {
                    if (pages.size >= 8 && key !in pages) pages.remove(pages.keys.first())
                    pages[key] = Cached(page, now())
                }
            }
            return page
        } catch (cancelled: CancellationException) {
            // A transport may cancel its own task while the borrowing screen is still alive.
            // Only caller/owner cancellation is lifecycle cancellation; otherwise surface a retryable failure.
            currentCoroutineContext().ensureActive(); job.ensureActive()
            report(id, DiscoveryFailure.TRANSPORT_CANCELLED)
            throw DiscoveryInterrupted()
        } catch (error: Exception) {
            report(id, when (error) {
                is DiscoveryTimeout -> DiscoveryFailure.TIMEOUT
                is IllegalArgumentException -> DiscoveryFailure.INVALID_RESPONSE
                else -> DiscoveryFailure.REQUEST_FAILED
            })
            throw error
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (--work.borrowers == 0) { if (pending[key] === work) pending.remove(key); work.task.cancel() }
                }
            }
        }
    }
    // Shared across all editions and any search UI using this session.
    private val verificationGate = Mutex()
    private val verificationStarts = ArrayDeque<Long>()

    suspend fun verifyReadable(publication: Publication): CatalogReadability = verificationGate.withLock {
        job.ensureActive()
        val source = sources.first { it.id == publication.id.sourceId } as ReadabilityCatalogSource
        val known = source.cachedReadability(publication)
        if (known != CatalogReadability.READABLE_CANDIDATE) return@withLock known
        val startedAt = now()
        while (verificationStarts.isNotEmpty() && startedAt - verificationStarts.first() !in 0..59_999)
            verificationStarts.removeFirst()
        if (verificationStarts.size >= 4) return@withLock known
        verificationStarts.addLast(startedAt)
        withTimeoutOrNull(8_000) { source.verifyReadable(publication) } ?: CatalogReadability.READABLE_CANDIDATE
    }

    private fun report(id: SourceId, failure: DiscoveryFailure) {
        // Diagnostic code receives no query, metadata, profile, path or exception message.
        runCatching { diagnostics(DiscoveryDiagnostic(id, failure)) }
    }
    internal suspend fun retainedPages() = lock.withLock { pages.size }
    private val cleanup = this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { withContext(NonCancellable) { lock.withLock { pages.clear(); pending.clear() } } }
    }
    fun close() { job.cancel() }
    suspend fun awaitClosed() { cleanup.join(); job.join() }
}

/** Same owning source+ID only. Work titles cannot prove edition/translation equivalence. */
internal fun distinctEntries(entries: List<DiscoveryEntry>) = entries.distinctBy { it.publication.id }

internal fun rankRecommendations(entries: List<DiscoveryEntry>, interests: Set<Genre>,
    library: Set<PublicationId>, recentGenres: Map<Genre, Int> = emptyMap()): List<DiscoveryEntry> {
    val ranked = distinctEntries(entries).filter { it.publication.id !in library }.sortedWith(
        compareByDescending<DiscoveryEntry> { entry -> entry.genres.sumOf { (if (it in interests) 4 else 0) + (recentGenres[it] ?: 0).coerceAtMost(2) } }
            .thenBy { it.publication.id.sourceId.value }.thenBy { it.publication.id.localId })
    // Stable round-robin sources limits one provider's dominance without conflating editions.
    val groups = ranked.groupBy { it.publication.id.sourceId }.values.map { it.toMutableList() }
    return buildList { while (groups.any { it.isNotEmpty() } && size < 12) groups.forEach { if (it.isNotEmpty() && size < 12) add(it.removeAt(0)) } }
}
