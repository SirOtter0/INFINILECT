// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.infinilect.core.PublicationSource
import org.infinilect.core.SearchPage

/** Safe, user-facing explanation supplied by an application source adapter. */
open class SearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Session-local identity advances only when a requested page succeeds. */
data class SearchResult(val query: String, val page: SearchPage, val generation: Long)

sealed interface SearchState {
    data object Idle : SearchState
    data class Loading(val previous: SearchResult? = null) : SearchState
    data class Results(val result: SearchResult) : SearchState
    data class Empty(val result: SearchResult) : SearchState
    data class Error(val message: String, val previous: SearchResult? = null) : SearchState
}

/** No HTTP, OPDS or Compose knowledge. Only explicit user actions initiate a request. */
class SearchController(private val source: PublicationSource) {
    private val mutableState = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = mutableState.asStateFlow()
    private val requestLock = Mutex()
    private var generation = 0L

    suspend fun search(query: String) {
        request(query.trim(), pageToken = null, previous = null)
    }

    suspend fun nextPage() {
        val previous = when (val current = state.value) {
            is SearchState.Results -> current.result
            is SearchState.Empty -> current.result
            is SearchState.Error -> current.previous
            else -> null
        } ?: return
        val token = previous.page.nextPageToken ?: return
        request(previous.query, token, previous)
    }

    private suspend fun request(query: String, pageToken: String?, previous: SearchResult?) {
        // Ignore repeated actions while busy, rather than queue extra network requests.
        if (!requestLock.tryLock()) return
        try {
            if (query.isBlank()) {
                mutableState.value = SearchState.Idle
                return
            }
            if (query.length > 256) {
                mutableState.value = SearchState.Error("Use at most 256 characters in your search.")
                return
            }
            mutableState.value = SearchState.Loading(previous)
            try {
                val page = source.search(query, pageToken)
                check(page.publications.all { it.id.sourceId == source.id })
                mutableState.value = SearchResult(query, page, ++generation).toState()
            } catch (cancelled: CancellationException) {
                mutableState.value = previous?.toState() ?: SearchState.Idle
                throw cancelled
            } catch (error: Exception) {
                val message = if (error is SearchException) error.message!!
                    else "Search failed. Please try again."
                mutableState.value = SearchState.Error(message, previous)
            }
        } finally {
            requestLock.unlock()
        }
    }

    private fun SearchResult.toState(): SearchState =
        if (page.publications.isEmpty()) SearchState.Empty(this) else SearchState.Results(this)
}
