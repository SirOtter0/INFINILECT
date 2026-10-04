// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.infinilect.core.Publication
import org.infinilect.core.PublicationId
import org.infinilect.core.PublicationResource
import org.infinilect.core.PublicationSource
import org.infinilect.core.PublicationType
import org.infinilect.core.ResourceContent
import org.infinilect.core.SearchPage
import org.infinilect.core.SourceId

class SearchControllerTest {
    private val book = Publication(PublicationId(SourceId("fixture"), "1"), "A Book", PublicationType.BOOK)
    private class FixtureSource(val action: suspend (String, String?) -> SearchPage) : PublicationSource {
        override val id = SourceId("fixture")
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun search(query: String, pageToken: String?): SearchPage {
            calls += query to pageToken
            return action(query, pageToken)
        }
        override suspend fun getPublication(publicationId: PublicationId): Publication? = error("Not used")
        override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("Not used")
    }

    @Test fun idleBlankAndOverlongSearchesDoNotAccessSource() = runTest {
        val source = FixtureSource { _, _ -> error("Unexpected request") }
        val controller = SearchController(source)
        assertIs<SearchState.Idle>(controller.state.value)
        controller.search(" ")
        assertIs<SearchState.Idle>(controller.state.value)
        controller.search("x".repeat(257))
        assertIs<SearchState.Error>(controller.state.value)
        assertTrue(source.calls.isEmpty())
    }

    @Test fun loadingResultsAndNextPageOnlyFollowExplicitActions() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val source = FixtureSource { _, token ->
            if (token == null) { entered.complete(Unit); gate.await(); SearchPage(listOf(book), "opaque") }
            else SearchPage(emptyList())
        }
        val controller = SearchController(source)
        val request = async { controller.search(" books ") }
        entered.await()
        assertIs<SearchState.Loading>(controller.state.value)
        controller.search("duplicate")
        controller.nextPage()
        assertEquals(1, source.calls.size)
        gate.complete(Unit); request.await()
        assertIs<SearchState.Results>(controller.state.value)
        assertEquals("books", source.calls.single().first)
        assertNull(source.calls.single().second)
        controller.nextPage()
        assertEquals(listOf("books" to null, "books" to "opaque"), source.calls)
        assertIs<SearchState.Empty>(controller.state.value)
        controller.nextPage()
        assertEquals(2, source.calls.size)
    }

    @Test fun nextPageFailureRetainsPreviousResultsAndCanBeRetried() = runTest {
        var fail = true
        val source = FixtureSource { _, token ->
            if (token != null && fail) throw SearchException("Please try again later.")
            SearchPage(listOf(book), if (token == null) "opaque" else null)
        }
        val controller = SearchController(source)
        controller.search("books")
        controller.nextPage()
        val error = assertIs<SearchState.Error>(controller.state.value)
        assertEquals("Please try again later.", error.message)
        assertEquals(listOf(book), error.previous!!.page.publications)
        fail = false
        controller.nextPage()
        assertIs<SearchState.Results>(controller.state.value)
        assertEquals(listOf("books" to null, "books" to "opaque", "books" to "opaque"), source.calls)
        controller.search("different")
        assertEquals("different" to null, source.calls.last())
    }

    @Test fun errorsHideImplementationDetailsAndForeignResultsAreRejected() = runTest {
        val controller = SearchController(FixtureSource { _, _ -> throw IllegalStateException("private parser detail") })
        controller.search("books")
        assertEquals("Search failed. Please try again.", assertIs<SearchState.Error>(controller.state.value).message)
        val foreign = book.copy(id = PublicationId(SourceId("other"), "1"))
        val inconsistent = SearchController(FixtureSource { _, _ -> SearchPage(listOf(foreign)) })
        inconsistent.search("books")
        assertIs<SearchState.Error>(inconsistent.state.value)
    }

    @Test fun cancellationPropagatesAndDoesNotLeaveLoadingStuck() = runTest {
        val controller = SearchController(FixtureSource { _, _ -> throw CancellationException("cancel") })
        assertFailsWith<CancellationException> { controller.search("books") }
        assertIs<SearchState.Idle>(controller.state.value)
    }

    @Test fun onlySuccessfulPagesAdvanceGenerationEvenWithIdenticalResults() = runTest {
        val controller = SearchController(FixtureSource { _, _ -> SearchPage(listOf(book), "opaque") })
        controller.search("books")
        val initial = assertIs<SearchState.Results>(controller.state.value).result
        controller.nextPage()
        val next = assertIs<SearchState.Results>(controller.state.value).result
        assertEquals(initial.page, next.page)
        assertEquals(initial.generation + 1, next.generation)
        controller.search("new query")
        assertEquals(next.generation + 1, assertIs<SearchState.Results>(controller.state.value).result.generation)
    }

    @Test fun failedNextRetainsGenerationAndSuccessfulRetryAdvancesItOnce() = runTest {
        var fail = true
        val source = FixtureSource { _, token ->
            if (token != null && fail) throw SearchException("Please try again.")
            SearchPage(listOf(book), "opaque")
        }
        val controller = SearchController(source)
        controller.search("books")
        val initial = assertIs<SearchState.Results>(controller.state.value).result
        controller.nextPage()
        assertEquals(initial, assertIs<SearchState.Error>(controller.state.value).previous)
        fail = false
        controller.nextPage()
        assertEquals(initial.generation + 1, assertIs<SearchState.Results>(controller.state.value).result.generation)
        assertEquals(listOf("books" to null, "books" to "opaque", "books" to "opaque"), source.calls)
    }

    @Test fun cancelledNextRetainsGeneration() = runTest {
        val controller = SearchController(FixtureSource { _, token ->
            if (token != null) throw CancellationException("cancel")
            SearchPage(listOf(book), "opaque")
        })
        controller.search("books")
        val initial = controller.state.value
        assertFailsWith<CancellationException> { controller.nextPage() }
        assertEquals(initial, controller.state.value)
    }
}
