// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.app.SourceOption
import org.infinilect.core.*

internal data class CatalogResults(val id: SourceId, val entries: List<DiscoveryEntry> = emptyList(),
    val nextToken: String? = null, val loading: Boolean = false, val failed: Boolean = false, val pages: Int = 0, val pendingToken: String? = null,
    val interrupted: Boolean = false, val failure: CatalogErrorKind? = null)
internal data class DiscoveryState(val query: String = "", val request: DiscoveryRequest? = null,
    val enabled: Set<SourceId> = emptySet(), val genre: Genre? = null, val language: String? = null,
    val catalogs: List<CatalogResults> = emptyList(), val generation: Long = 0, val invalidQuery: Boolean = false) {
    // Immutable snapshot: scrolling/readers of the same state borrow one list.
    val entries = distinctEntries(catalogs.flatMap { it.entries })
    val loading get() = catalogs.any { it.loading }
}
/** One latest intent and at most one active page per source; old callbacks cannot mutate new intent. */
internal class DiscoveryController(private val options: List<SourceOption>, private val catalog: DiscoveryCatalog,
    parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(DiscoveryState(enabled = options.filter { it.source is DiscoverySource || it.source.id.value == "local-imports" }.map { it.source.id }.toSet()))
    val state = mutable.asStateFlow()
    private var debounce: Job? = null
    private val requests = mutableMapOf<SourceId, Job>()
    private var sequence = 0L
    private var interruptedEdit = false
    fun edit(raw: String) {
        if (!job.isActive) return
        retire()
        mutable.value = mutable.value.copy(query = raw.take(1024), request = null, catalogs = emptyList(), generation = sequence, invalidQuery = false)
        debounce = scope.launch { delay(350); submit() }
    }
    fun filter(id: SourceId) {
        if (options.none { it.source.id == id }) return
        val enabled = state.value.enabled.toMutableSet().apply { if (!add(id)) remove(id) }
        mutable.value = state.value.copy(enabled = enabled); submit()
    }
    fun genre(value: Genre?) { mutable.value = state.value.copy(genre = value); submit() }
    fun language(value: String?) { mutable.value = state.value.copy(language = value); submit() }
    fun browse(value: Genre) { mutable.value = state.value.copy(query = "", genre = value); submit() }
    fun resetFilters() {
        mutable.value = state.value.copy(enabled = options.filter { it.source is DiscoverySource || it.source.id.value == "local-imports" }.map { it.source.id }.toSet(), genre = null, language = null)
        submit()
    }
    private fun retire() { sequence++; debounce?.cancel(); debounce = null; requests.values.forEach { it.cancel() }; requests.clear() }
    fun submit() {
        if (!job.isActive) return
        interruptedEdit = false
        val current = state.value
        val query = try { normalizedQuery(current.query) } catch (_: IllegalArgumentException) {
            retire()
            mutable.value = current.copy(invalidQuery = true, request = null, catalogs = emptyList(), generation = sequence); return
        }
        val request = DiscoveryRequest(query, current.genre, current.language)
        val eligible = options.filter { it.source.id in current.enabled &&
            (current.genre == null || (it.source as? DiscoverySource)?.canBrowseGenres == true) }
        if (current.loading && current.request == request && current.catalogs.map { it.id } == eligible.map { it.source.id }) return
        retire()
        if (query.isEmpty() && current.genre == null) { mutable.value = current.copy(request = null, catalogs = emptyList(), generation = sequence); return }
        mutable.value = current.copy(request = request, catalogs = eligible.map { CatalogResults(it.source.id, loading = true) }, generation = sequence, invalidQuery = false)
        eligible.forEach { fetch(it.source.id) }
    }
    fun retry(id: SourceId) {
        val result = state.value.catalogs.firstOrNull { it.id == id && it.failed && !it.loading } ?: return
        fetch(id, result.pendingToken)
    }
    fun more(id: SourceId) { val result = state.value.catalogs.firstOrNull { it.id == id } ?: return
        if (!result.loading && !result.failed && result.nextToken != null && result.pages < 4) fetch(id, result.nextToken) }
    private fun fetch(id: SourceId, token: String? = null) {
        val generation = state.value.generation
        val request = state.value.request ?: return
        val previous = state.value.catalogs.firstOrNull { it.id == id } ?: return
        mutable.update { s -> s.copy(catalogs = s.catalogs.map { if (it.id == id) it.copy(loading = true, failed = false, pendingToken = token, interrupted = false, failure = null) else it }) }
        requests[id] = scope.launch {
            try {
                val page = catalog.load(id, request, token)
                ensureActive()
                val entries = distinctEntries((if (token == null) emptyList() else previous.entries) + page.entries).take(100)
                mutable.update { s -> if (s.generation != generation) s else s.copy(catalogs = s.catalogs.map { if (it.id == id) CatalogResults(id, entries, page.nextToken, pages = if (token == null) 1 else previous.pages + 1) else it }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { s -> if (s.generation != generation) s else s.copy(catalogs = s.catalogs.map { if (it.id == id) it.copy(loading = false, failed = true, failure = when (error) { is CatalogSourceException -> error.kind; is DiscoveryTimeout -> CatalogErrorKind.TIMEOUT; is DiscoveryInterrupted -> CatalogErrorKind.CANCELLED; else -> null }) else it }) } }
        }
    }
    fun pause() {
        interruptedEdit = interruptedEdit || debounce?.isActive == true
        retire()
        mutable.value = state.value.copy(generation = sequence, catalogs = state.value.catalogs.map { it.copy(loading = false, interrupted = it.interrupted || it.loading) })
    }
    /** Screen return resumes only interrupted work, never refreshes a completed search. */
    fun resume() {
        if (!job.isActive) return
        if (interruptedEdit) { submit(); return }
        state.value.catalogs.filter { it.interrupted && !it.loading }.forEach { fetch(it.id, it.pendingToken) }
    }
    fun close() { retire(); job.cancel(); mutable.value = DiscoveryState() }
}
