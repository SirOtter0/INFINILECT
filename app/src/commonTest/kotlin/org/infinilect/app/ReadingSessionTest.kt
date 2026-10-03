// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.search.SearchState
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionTest {
    private class Source(
        override val id: SourceId = SourceId("fixture"),
        val searchAction: suspend () -> Unit = {},
        val readAction: suspend () -> Unit = {},
        val hasDetails: Boolean = true,
    ) : PublicationSource {
        val resource = PublicationResource(PublicationId(id, "1"), "text", PublicationFormat.TEXT, "text/plain")
        val publication = Publication(resource.publicationId, "Title", PublicationType.DOCUMENT, resources = listOf(resource))
        val calls = mutableListOf<Pair<String, String?>>()
        var details = 0; var loads = 0; var closes = 0
        override suspend fun search(query: String, pageToken: String?): SearchPage {
            calls += query to pageToken
            searchAction()
            return SearchPage(listOf(publication.copy(resources = emptyList())), if (pageToken == null) "next" else null)
        }
        override suspend fun getPublication(publicationId: PublicationId): Publication? {
            details++
            return if (hasDetails) publication else null
        }
        override suspend fun loadResource(resource: PublicationResource): ResourceContent {
            assertEquals(this.resource, resource); loads++
            return object : ResourceContent {
                private var done = false
                override val sizeBytes = 4L
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    readAction()
                    if (done) return -1
                    "text".encodeToByteArray().copyInto(buffer, offset)
                    done = true
                    return 4
                }
                override fun close() { closes++ }
            }
        }
    }

    private fun TestScope.session(source: Source, enabled: Boolean = true) = ReadingSession(source, this, enabled,
        decodingDispatcher = StandardTestDispatcher(testScheduler))

    @Test fun initialAndEditedQueryDoNotAutomaticallySearch() = runTest {
        val source = Source(); val session = session(source)
        assertEquals("", session.query.value)
        session.editQuery("books"); advanceUntilIdle()
        assertIs<SearchState.Idle>(session.search.state.value)
        assertTrue(source.calls.isEmpty())
    }

    @Test fun searchLoadingReaderAndBackPreserveQueryAndExactResults() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = Source(readAction = { gate.await() }); val session = session(source)
        session.editQuery(" query "); session.submitSearch(); advanceUntilIdle()
        val results = assertIs<SearchState.Results>(session.search.state.value)
        session.open(results.result.page.publications.single()); runCurrent()
        assertIs<OpenPublicationState.Loading>(session.opening.state.value)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("text", assertIs<OpenPublicationState.Ready>(session.opening.state.value).document.window(0).text)
        assertEquals(1, source.closes)
        session.back()
        assertIs<OpenPublicationState.Idle>(session.opening.state.value)
        assertEquals(" query ", session.query.value)
        assertSame(results, session.search.state.value)
        assertEquals(1, source.calls.size)
    }

    @Test fun backWhileLoadingCancelsClosesAndKeepsSearch() = runTest {
        val entered = CompletableDeferred<Unit>()
        val source = Source(readAction = { entered.complete(Unit); awaitCancellation() }); val session = session(source)
        session.editQuery("query"); session.submitSearch(); advanceUntilIdle()
        val before = session.search.state.value
        session.open(source.publication); entered.await()
        session.back(); advanceUntilIdle()
        assertIs<OpenPublicationState.Idle>(session.opening.state.value)
        assertSame(before, session.search.state.value); assertEquals("query", session.query.value)
        assertEquals(1, source.closes)
    }

    @Test fun searchOnlySourceNeverRequestsDetailsOrResources() = runTest {
        val source = Source(); val session = session(source, enabled = false)
        session.editQuery("books"); session.submitSearch(); advanceUntilIdle()
        session.open(source.publication); advanceUntilIdle()
        assertIs<SearchState.Results>(session.search.state.value)
        assertIs<OpenPublicationState.Idle>(session.opening.state.value)
        assertEquals(0, source.details); assertEquals(0, source.loads)
    }

    @Test fun duplicateSearchAndAutomaticNextPageAreAvoided() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = Source(searchAction = { gate.await() }); val session = session(source)
        session.editQuery("one"); session.submitSearch(); session.open(source.publication)
        assertEquals(0, source.details)
        runCurrent()
        session.submitSearch(); session.nextPage(); session.open(source.publication)
        assertEquals(1, source.calls.size); assertEquals(0, source.details)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, source.calls.size)
        session.nextPage(); advanceUntilIdle()
        assertEquals(listOf("one" to null, "one" to "next"), source.calls)
    }

    @Test fun sourceChangeDiscardsOldSessionAndLateSearchCannotOverwriteNewOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        val oldSource = Source(SourceId("old"), searchAction = { withContext(NonCancellable) { gate.await() } })
        val oldSession = session(oldSource)
        oldSession.editQuery("old query"); oldSession.submitSearch(); runCurrent()
        oldSession.close()
        val newSource = Source(SourceId("new")); val newSession = session(newSource)
        assertEquals("", newSession.query.value); assertIs<SearchState.Idle>(newSession.search.state.value)
        assertTrue(newSource.calls.isEmpty())
        newSession.editQuery("new query"); newSession.submitSearch(); runCurrent()
        val expected = assertIs<SearchState.Results>(newSession.search.state.value)
        gate.complete(Unit); advanceUntilIdle()
        assertSame(expected, newSession.search.state.value)
        assertEquals(newSource.id, expected.result.page.publications.single().id.sourceId)
        assertEquals("new query", newSession.query.value)
    }

    @Test fun backFromErrorKeepsResultsForAnotherExplicitAttempt() = runTest {
        val source = Source(hasDetails = false); val session = session(source)
        session.editQuery("query"); session.submitSearch(); advanceUntilIdle()
        val previous = session.search.state.value
        session.open(source.publication); advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(session.opening.state.value)
        session.back()
        assertSame(previous, session.search.state.value); assertEquals("query", session.query.value)
        assertIs<OpenPublicationState.Idle>(session.opening.state.value)
    }

    @Test fun closingSessionCancelsAcquisitionAndPreventsFutureActions() = runTest {
        val entered = CompletableDeferred<Unit>()
        val source = Source(readAction = { entered.complete(Unit); awaitCancellation() }); val session = session(source)
        session.open(source.publication); entered.await()
        session.close(); session.close()
        session.open(source.publication); session.editQuery("ignored"); session.submitSearch(); session.nextPage()
        advanceUntilIdle()
        assertEquals(1, source.closes); assertEquals(1, source.loads)
        assertEquals("", session.query.value); assertTrue(source.calls.isEmpty())
        assertIs<OpenPublicationState.Idle>(session.opening.state.value)
    }

    @Test fun readerDoesNotTriggerSearchOrNextPage() = runTest {
        val source = Source(); val session = session(source)
        session.open(source.publication); advanceUntilIdle()
        assertIs<OpenPublicationState.Ready>(session.opening.state.value)
        session.editQuery("new query"); session.submitSearch(); session.nextPage(); advanceUntilIdle()
        assertTrue(source.calls.isEmpty())
    }

    @Test fun submittedQueryIsCapturedBeforeCoroutineStarts() = runTest {
        val source = Source(); val session = session(source)
        session.editQuery("submitted"); session.submitSearch(); session.editQuery("edited later")
        advanceUntilIdle()
        assertEquals("submitted", source.calls.single().first)
        assertEquals("edited later", session.query.value)
    }
}
