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

internal class DiscoveryCatalog(private val sources: List<PublicationSource>,
    scope: CoroutineScope, private val now: () -> Long) {
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
        val key = Key(id, request, token)
        val source = sources.first { it.id == id }
        var hit: DiscoveryPage? = null
        val lease = lock.withLock {
            job.ensureActive()
            pages.remove(key)?.let { cached ->
                if (now() - cached.at in 0..300_000) { pages[key] = cached; hit = cached.page }
            }
            if (hit != null) null else pending[key]?.also { it.borrowers++ } ?: Pending(scope.async(start = CoroutineStart.LAZY) {
                gate.withPermit {
                    try { withTimeout(20_000) {
                        val page = if (source is DiscoverySource) source.discover(request, token)
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
                        DiscoveryPage(filtered, page.nextToken)
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
                if (pages.size >= 8 && key !in pages) pages.remove(pages.keys.first())
                pages[key] = Cached(page, now())
            }
            return page
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (--work.borrowers == 0) { if (pending[key] === work) pending.remove(key); work.task.cancel() }
                }
            }
        }
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
