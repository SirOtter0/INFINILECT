// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.infinilect.core.SearchPage

class SearchResultsViewportTest {
    private class Position(var firstItem: Int = 0)
    private fun result(generation: Long, query: String = "books") =
        SearchResult(query, SearchPage(emptyList(), "opaque"), generation)

    @Test fun initialResultsKeepTheInitialTopPosition() {
        val viewport = SearchResultsViewport { Position() }
        val initial = viewport.forState(SearchState.Idle)
        assertSame(initial, viewport.forState(SearchState.Loading()))
        assertSame(initial, viewport.forState(SearchState.Results(result(1))))
        assertEquals(0, initial.firstItem)
    }

    @Test fun successfulNextResetsExactlyOnceEvenWhenPageContentsAreIdentical() {
        var creations = 0
        val viewport = SearchResultsViewport { creations++; Position() }
        val first = viewport.forState(SearchState.Results(result(1)))
        first.firstItem = 23
        val next = SearchState.Results(result(2))
        val position = viewport.forState(next)
        assertNotSame(first, position)
        assertEquals(0, position.firstItem)
        position.firstItem = 7
        repeat(10) { assertSame(position, viewport.forState(next.copy())) }
        assertEquals(7, position.firstItem)
        assertEquals(2, creations)
    }

    @Test fun loadingAndFailedPaginationKeepTheOldScrollPosition() {
        val viewport = SearchResultsViewport { Position() }
        val result = result(1)
        val position = viewport.forState(SearchState.Results(result))
        position.firstItem = 23
        assertSame(position, viewport.forState(SearchState.Loading(result)))
        assertSame(position, viewport.forState(SearchState.Error("Please try again.", result)))
        assertEquals(23, position.firstItem)
    }

    @Test fun cancelledPaginationReturningPreviousResultsDoesNotReset() {
        val viewport = SearchResultsViewport { Position() }
        val state = SearchState.Results(result(1))
        val position = viewport.forState(state)
        position.firstItem = 23
        viewport.forState(SearchState.Loading(state.result))
        assertSame(position, viewport.forState(state))
        assertEquals(23, position.firstItem)
    }

    @Test fun newQueryResetsOnlyAfterSuccess() {
        val viewport = SearchResultsViewport { Position() }
        val position = viewport.forState(SearchState.Results(result(1)))
        position.firstItem = 23
        assertSame(position, viewport.forState(SearchState.Loading()))
        assertSame(position, viewport.forState(SearchState.Error("Failed")))
        val next = viewport.forState(SearchState.Results(result(2, "different")))
        assertNotSame(position, next)
        assertEquals(0, next.firstItem)
    }

    @Test fun newSourceSessionStartsAtTopEvenWithTheSameGenerationNumber() {
        val oldViewport = SearchResultsViewport { Position() }
        val oldPosition = oldViewport.forState(SearchState.Results(result(1)))
        oldPosition.firstItem = 23
        val newViewport = SearchResultsViewport { Position() }
        val newPosition = newViewport.forState(SearchState.Results(result(1)))
        assertNotSame(oldPosition, newPosition)
        assertEquals(0, newPosition.firstItem)
        assertEquals(23, oldPosition.firstItem)
    }

    @Test fun successfulEmptyNextPageAlsoReplacesTheViewportOnce() {
        var creations = 0
        val viewport = SearchResultsViewport { creations++; Position() }
        val position = viewport.forState(SearchState.Results(result(1)))
        position.firstItem = 23
        val empty = SearchState.Empty(result(2))
        val next = viewport.forState(empty)
        assertNotSame(position, next)
        assertSame(next, viewport.forState(empty))
        assertEquals(2, creations)
    }
}
