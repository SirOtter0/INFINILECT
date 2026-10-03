// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.reader.OpenPublicationController
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.search.SearchController
import org.infinilect.app.search.SearchState
import org.infinilect.core.Publication
import org.infinilect.core.PublicationSource
import org.infinilect.core.ResourceLoader

/** One selected source, two screens, in-memory query/results. No registry or cross-source search. */
internal class ReadingSession(
    source: PublicationSource,
    private val scope: CoroutineScope,
    val textReadingEnabled: Boolean,
    loader: ResourceLoader = DirectResourceLoader(source),
    decodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    progress: ProgressPersistence? = null,
) {
    val search = SearchController(source)
    val opening = OpenPublicationController(source, loader, scope, decodingDispatcher, progress)
    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()
    private var searchJob: Job? = null
    private var closed = false

    fun editQuery(value: String) { if (!closed) mutableQuery.value = value }

    fun submitSearch() {
        val submitted = query.value
        searchAction { search.search(submitted) }
    }
    fun nextPage() = searchAction { search.nextPage() }

    private fun searchAction(action: suspend () -> Unit) {
        if (closed || searchJob?.isActive == true || opening.state.value !is OpenPublicationState.Idle) return
        searchJob = scope.launch { action() }
    }

    fun open(publication: Publication) {
        if (!closed && textReadingEnabled && searchJob?.isActive != true && search.state.value !is SearchState.Loading)
            opening.open(publication)
    }

    fun back() { opening.cancel() }

    /** Discarded on source change/application disposal; a later selection creates a fresh session. */
    fun close() {
        if (closed) return
        closed = true
        searchJob?.cancel()
        opening.close()
    }
}
