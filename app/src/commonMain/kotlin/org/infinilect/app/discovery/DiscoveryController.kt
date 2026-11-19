// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.app.SourceOption
import org.infinilect.core.*

internal data class CatalogResults(val id: SourceId, val entries: List<DiscoveryEntry> = emptyList(),
    val nextToken: String? = null, val loading: Boolean = false, val failed: Boolean = false, val pages: Int = 0, val pendingToken: String? = null,
    val interrupted: Boolean = false, val failure: CatalogErrorKind? = null, val candidates: List<DiscoveryEntry> = emptyList(), val assessmentLimited: Boolean = false)
internal data class DiscoveryState(val query: String = "", val request: DiscoveryRequest? = null,
    val enabled: Set<SourceId> = emptySet(), val genre: Genre? = null, val language: String? = null,
    val catalogs: List<CatalogResults> = emptyList(), val generation: Long = 0, val invalidQuery: Boolean = false,
    val showCatalogOnlySources: Boolean = false, val readabilityMode: ReadabilityMode = ReadabilityMode.READABLE) {
    // Immutable snapshot: scrolling/readers of the same state borrow one list.
    val entries = distinctEntries(catalogs.flatMap { it.entries })
    val loading get() = catalogs.any { it.loading }
}
/** One latest intent and at most one active page per source; old callbacks cannot mutate new intent. */
internal class DiscoveryController(private val options: List<SourceOption>, private val catalog: DiscoveryCatalog,
    parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private fun defaults() = options.filter { it.source is DiscoverySource || it.source.id.value == "local-imports" }.map { it.source.id }.toSet()
    // Retain user intent when a source is hidden. Only the visible intersection may request data.
    private var selectedSources = defaults()
    private val mutable = MutableStateFlow(DiscoveryState(enabled = options.filter { it.supportsIntegratedReading && it.source.id in selectedSources }.map { it.source.id }.toSet()))
    val state = mutable.asStateFlow()
    val visibleOptions get() = options.filter { it.visibleInCatalogs(state.value.showCatalogOnlySources) }
    private val readableIds = options.filter { it.source is ReadabilityCatalogSource }.map { it.source.id }.toSet()
    val hasReadableSources get() = state.value.enabled.any { it in readableIds }
    fun isReadableSource(id: SourceId) = id in readableIds

    fun readabilityMode(mode: ReadabilityMode) {
        val current = state.value
        if (!job.isActive || current.readabilityMode == mode) return
        readableIds.forEach { requests.remove(it)?.cancel() }
        mutable.value = current.copy(readabilityMode = mode, request = current.request?.copy(readabilityMode = mode),
            catalogs = current.catalogs.map { if (it.id in readableIds) CatalogResults(it.id, interrupted = paused) else it })
        if (!paused) state.value.catalogs.filter { it.id in readableIds }.forEach { fetch(it.id) }
    }

    /** A completed check in details can promote the existing candidate without a new search. */
    suspend fun refreshReadability(publication: Publication) {
        val snapshot = state.value
        val source = options.firstOrNull { it.source.id == publication.id.sourceId }?.source as? ReadabilityCatalogSource ?: return
        val result = source.cachedReadability(publication)
        currentCoroutineContext().ensureActive()
        mutable.update { current ->
            if (current.generation != snapshot.generation || current.readabilityMode != snapshot.readabilityMode) return@update current
            current.copy(catalogs = current.catalogs.map { row ->
                if (row.id != publication.id.sourceId) return@map row
                val entry = (row.entries + row.candidates).firstOrNull { it.publication.id == publication.id } ?: return@map row
                val updated = entry.copy(readability = result)
                if (current.readabilityMode == ReadabilityMode.ALL)
                    row.copy(entries = row.entries.map { if (it.publication.id == publication.id) updated else it })
                else row.copy(
                    entries = if (result == CatalogReadability.READABLE_CONFIRMED) distinctEntries(row.entries + updated).take(100)
                        else row.entries.filter { it.publication.id != publication.id },
                    candidates = if (result == CatalogReadability.READABLE_CANDIDATE) distinctEntries(row.candidates + updated).take(32)
                        else row.candidates.filter { it.publication.id != publication.id })
            })
        }
    }
    private var paused = false
    private var debounce: Job? = null
    private val requests = mutableMapOf<SourceId, Job>()
    private var sequence = 0L
    private var interruptedEdit = false
    fun catalogOnlyVisibility(show: Boolean) {
        if (!job.isActive || state.value.showCatalogOnlySources == show) return
        val enabled = options.filter { it.visibleInCatalogs(show) && it.source.id in selectedSources }.map { it.source.id }.toSet()
        val previous = state.value
        val hidden = previous.enabled - enabled
        hidden.forEach { requests.remove(it)?.cancel() }
        val added = options.filter { it.source.id in enabled - previous.enabled &&
            (previous.genre == null || (it.source as? DiscoverySource)?.canBrowseGenres == true) }
        val catalogs = previous.catalogs.filter { it.id in enabled } +
            if (previous.request == null) emptyList() else added.map { CatalogResults(it.source.id, interrupted = paused) }
        mutable.value = previous.copy(enabled = enabled, catalogs = catalogs, showCatalogOnlySources = show)
        // Existing visible requests/results remain intact; hiding never refetches Internet Archive.
        if (!paused && previous.request != null) added.forEach { fetch(it.source.id) }
    }
    fun edit(raw: String) {
        if (!job.isActive) return
        retire()
        mutable.value = mutable.value.copy(query = raw.take(1024), request = null, catalogs = emptyList(), generation = sequence, invalidQuery = false)
        debounce = scope.launch { delay(350); submit() }
    }
    fun filter(id: SourceId) {
        if (visibleOptions.none { it.source.id == id }) return
        selectedSources = selectedSources.toMutableSet().apply { if (!add(id)) remove(id) }
        val visible = visibleOptions.map { it.source.id }.toSet()
        mutable.value = state.value.copy(enabled = selectedSources intersect visible); submit()
    }
    fun genre(value: Genre?) { mutable.value = state.value.copy(genre = value); submit() }
    fun language(value: String?) { mutable.value = state.value.copy(language = value); submit() }
    fun browse(value: Genre) { mutable.value = state.value.copy(query = "", genre = value); submit() }
    fun resetFilters() {
        selectedSources = defaults()
        mutable.value = state.value.copy(enabled = selectedSources intersect visibleOptions.map { it.source.id }.toSet(), genre = null, language = null)
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
        val request = DiscoveryRequest(query, current.genre, current.language, current.readabilityMode)
        val eligible = visibleOptions.filter { it.source.id in current.enabled &&
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
                mutable.update { s -> if (s.generation != generation) s else s.copy(catalogs = s.catalogs.map { if (it.id == id) CatalogResults(id, entries, page.nextToken, pages = if (token == null) 1 else previous.pages + 1,
                    candidates = distinctEntries((if (token == null) emptyList() else previous.candidates) + page.candidates).filter { candidate -> entries.none { it.publication.id == candidate.publication.id } }.take(32),
                    assessmentLimited = page.assessmentLimited) else it }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ensureActive(); mutable.update { s -> if (s.generation != generation) s else s.copy(catalogs = s.catalogs.map { if (it.id == id) it.copy(loading = false, failed = true, failure = when (error) { is CatalogSourceException -> error.kind; is DiscoveryTimeout -> CatalogErrorKind.TIMEOUT; is DiscoveryInterrupted -> CatalogErrorKind.CANCELLED; else -> null }) else it }) } }
        }
    }
    fun pause() {
        paused = true
        interruptedEdit = interruptedEdit || debounce?.isActive == true
        retire()
        mutable.value = state.value.copy(generation = sequence, catalogs = state.value.catalogs.map { it.copy(loading = false, interrupted = it.interrupted || it.loading) })
    }
    /** Screen return resumes only interrupted work, never refreshes a completed search. */
    fun resume() {
        if (!job.isActive) return
        paused = false
        if (interruptedEdit) { submit(); return }
        state.value.catalogs.filter { it.interrupted && !it.loading }.forEach { fetch(it.id, it.pendingToken) }
    }
    fun close() { retire(); job.cancel(); mutable.value = DiscoveryState() }
}
