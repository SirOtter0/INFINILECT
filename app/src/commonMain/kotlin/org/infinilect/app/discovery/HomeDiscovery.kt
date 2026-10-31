// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.app.SourceOption
import org.infinilect.app.diagnostics.*

internal data class DiscoveryRow(val key: String, val title: String, val entries: List<DiscoveryEntry>, val genre: Genre? = null)
internal data class HomeDiscoveryState(val rows: List<DiscoveryRow> = emptyList(), val loading: Boolean = false, val failed: Boolean = false)
/** Explicit opt-in metadata discovery. At most four bounded requests in two batches, no endless prefetch. */
internal class HomeDiscovery(private val options: List<SourceOption>, private val catalog: DiscoveryCatalog, parent: CoroutineScope, private val diagnostics: TechnicalDiagnostics? = null, private val now: () -> Long) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutable = MutableStateFlow(HomeDiscoveryState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    private var generation = 0L
    private var loadedAt: Long? = null
    private var choice: DiscoveryPreferences? = null
    private var showCatalogOnly = false
    fun catalogOnlyVisibility(show: Boolean) {
        if (showCatalogOnly == show) return
        showCatalogOnly = show
        pause(); choice = null; loadedAt = null
        val visible = options.filter { it.visibleInCatalogs(show) }.map { it.source.id }.toSet()
        mutable.value = state.value.copy(rows = state.value.rows.mapNotNull { row ->
            row.copy(entries = row.entries.filter { it.publication.id.sourceId in visible }).takeIf { it.entries.isNotEmpty() }
        }, failed = false)
    }
    fun refresh(preferences: DiscoveryPreferences, retry: Boolean = false) {
        catalogOnlyVisibility(preferences.showCatalogOnlySources)
        if (!preferences.homeEnabled) { pause(); choice = preferences; loadedAt = null; mutable.value = HomeDiscoveryState(); return }
        if (!retry && preferences == choice && (task?.isActive == true || loadedAt?.let { now() - it in 0..300_000 } == true)) return
        pause(); choice = preferences
        val stamp = generation
        mutable.value = mutable.value.copy(loading = true, failed = false)
        diagnostics.event(TechnicalEvent.HOME_LOADING, stamp)
        task = scope.launch {
            val genres = ((if (preferences.personalized) preferences.interests.sortedBy { it.ordinal } else emptyList()) +
                listOf(Genre.PHILOSOPHY, Genre.HISTORY)).distinct().take(2)
            mutable.update { it.copy(rows = it.rows.filter { row -> row.genre == null || row.genre in genres }) }
            var failed = false
            coroutineScope {
                options.filter { it.visibleInCatalogs(preferences.showCatalogOnlySources) }.map { option -> async {
                    try {
                        val query = if ((option.source as? DiscoverySource)?.canBrowseGenres == true) "*" else "classics"
                        diagnostics.event(TechnicalEvent.GENERAL_STARTED, stamp)
                        val page = catalog.load(option.source.id, DiscoveryRequest(query))
                        if (stamp != generation) diagnostics.event(TechnicalEvent.STALE_RESPONSE, stamp)
                        ensureActive()
                        diagnostics.event(TechnicalEvent.REQUEST_COMPLETE, stamp)
                        if (stamp == generation) mutable.update { state ->
                            val previous = state.rows.firstOrNull { it.key == "general" }?.entries.orEmpty()
                                .filter { it.publication.id.sourceId != option.source.id }
                            val entries = distinctEntries(previous + page.entries)
                            val general = if (entries.isEmpty()) emptyList() else listOf(DiscoveryRow("general", DiscoveryStrings.GENERAL, entries))
                            state.copy(rows = general + state.rows.filter { it.key != "general" })
                        }
                    } catch (e: CancellationException) { diagnostics.event(TechnicalEvent.REQUEST_CANCELLED, stamp); throw e }
                    catch (_: Exception) { diagnostics.event(TechnicalEvent.REQUEST_FAILED, stamp); failed = true }
                } }.awaitAll()
            }
            val browser = options.firstOrNull { it.visibleInCatalogs(preferences.showCatalogOnlySources) && (it.source as? DiscoverySource)?.canBrowseGenres == true }
            if (browser != null) coroutineScope {
                genres.map { genre -> async {
                    try {
                        diagnostics.event(TechnicalEvent.GENRE_STARTED, stamp)
                        val entries = catalog.load(browser.source.id, DiscoveryRequest("", genre)).entries
                        if (stamp != generation) diagnostics.event(TechnicalEvent.STALE_RESPONSE, stamp)
                        ensureActive()
                        diagnostics.event(TechnicalEvent.REQUEST_COMPLETE, stamp)
                        if (stamp == generation) mutable.update { state ->
                            val retained = state.rows.filter { it.genre != genre }
                            val rows = retained + if (entries.isEmpty()) emptyList() else listOf(DiscoveryRow(genre.name, genre.label, entries, genre))
                            state.copy(rows = rows.sortedBy { if (it.genre == null) -1 else genres.indexOf(it.genre) })
                        }
                    } catch (e: CancellationException) { diagnostics.event(TechnicalEvent.REQUEST_CANCELLED, stamp); throw e }
                    catch (_: Exception) { diagnostics.event(TechnicalEvent.REQUEST_FAILED, stamp); failed = true }
                } }.awaitAll()
            }
            ensureActive()
            if (stamp == generation) { mutable.update { it.copy(loading = false, failed = failed) }; loadedAt = if (failed) null else now(); diagnostics.event(TechnicalEvent.HOME_COMPLETE, stamp, mutable.value.rows.size) }

        }
    }
    fun pause() { if (task?.isActive == true) diagnostics.event(TechnicalEvent.HOME_PAUSED, generation); generation++; task?.cancel(); task = null; mutable.value = mutable.value.copy(loading = false) }
    fun close() { pause(); scope.cancel(); mutable.value = HomeDiscoveryState() }
}
