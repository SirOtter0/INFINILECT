// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.reader.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationContinuityTest {
    private class Store : ReadingProgressStore {
        val values = mutableMapOf<ReadingProgressId, ReadingProgress>(); var reads = 0
        var saveGate: CompletableDeferred<Unit>? = null
        override suspend fun get(id: ReadingProgressId): ReadingProgress? { reads++; return values[id] }
        override suspend fun save(progress: ReadingProgress): Boolean { saveGate?.await(); values[progress.id] = progress; return true }
        override suspend fun remove(id: ReadingProgressId) = values.remove(id) != null
    }
    private class Source : PublicationSource {
        override val id = SourceId("continuity")
        val books = listOf("first", "second").map { name ->
            val r = PublicationResource(PublicationId(id, name), "text", PublicationFormat.TEXT, "text/plain")
            Publication(r.publicationId, name, PublicationType.DOCUMENT, resources = listOf(r))
        }
        var lookups = 0; var gate: CompletableDeferred<Unit>? = null
        override suspend fun search(query: String, pageToken: String?) = SearchPage(books)
        override suspend fun getPublication(publicationId: PublicationId): Publication { lookups++; gate?.await(); return books.single { it.id == publicationId } }
        override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("Owned fixture preparation")
    }
    private class Preparer : TextPreparer {
        var prepared = 0; var documentCloses = 0; var closes = 0
        override suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader, dispatcher: CoroutineDispatcher): TextDocument {
            prepared++
            val windows = object : TextWindows {
                override val count = 1; override val codePoints = 100
                override fun start(index: Int) = 0
                override suspend fun read(index: Int) = TextWindow(0, 0, "a".repeat(100))
                override fun close() { documentCloses++ }
            }
            return TextDocument(publication.id, publication.title, windows, ReadingProgressId(publication.id, resource.key, PublicationFormat.TEXT))
        }
        override fun close() { closes++ }
    }
    private suspend fun TestScope.fixture(durable: Boolean = true, action: suspend (ApplicationSources, Source, Preparer, Store, ProgressPersistence?) -> Unit) {
        val source = Source(); val prep = Preparer(); val store = Store()
        val writer = if (durable) ProgressPersistence(store, StandardTestDispatcher(testScheduler)) { 10 } else null
        val owner = ApplicationSources(listOf(SourceOption("Original", source, true)), progress = writer,
            textPreparer = prep, sessionDispatcher = StandardTestDispatcher(testScheduler)) {}
        try { action(owner, source, prep, store, writer) }
        finally { store.saveGate?.complete(Unit); source.gate?.complete(Unit); owner.close(); owner.awaitProgressClosed(); runCurrent() }
        assertEquals(prep.prepared, prep.documentCloses); assertEquals(1, prep.closes)
    }
    @Test fun remountReusesOneLiveSessionAndNeverReloadsOlderDurableProgress() = runTest { fixture { owner, source, prep, store, _ ->
        val id = ReadingProgressId(source.books[0].id, "text", PublicationFormat.TEXT)
        store.values[id] = ReadingProgress(id, ReadingLocator.Text(10, 100), .1, 1)
        val session = owner.applicationSession(); session.navigate(Destination.SEARCH); session.openSearch(source.books[0]); runCurrent()
        val ready = assertIs<OpenPublicationState.Ready>(session.opening.value); val reading = assertNotNull(ready.reading)
        assertEquals(10, reading.codePointOffset.value); reading.report(60)
        store.saveGate = CompletableDeferred()
        repeat(100) {
            owner.flushProgress(); runCurrent(); val presentedAgain = owner.applicationSession()
            assertSame(session, presentedAgain); assertSame(ready, presentedAgain.opening.value)
            assertEquals(60, reading.codePointOffset.value)
        }
        assertEquals(1, source.lookups); assertEquals(1, prep.prepared); assertEquals(1, store.reads)
        assertEquals(10L, (assertNotNull(store.values[id]).locator as ReadingLocator.Text).codePointOffset)
        store.saveGate!!.complete(Unit); runCurrent()
        assertEquals(60L, (assertNotNull(store.values[id]).locator as ReadingLocator.Text).codePointOffset)
    } }
    @Test fun presentationReplacementWhileOpeningDoesNotCancelOrAcquireTwice() = runTest { fixture { owner, source, prep, _, _ ->
        source.gate = CompletableDeferred()
        val first = owner.applicationSession(); first.navigate(Destination.SEARCH); first.openSearch(source.books[0]); runCurrent()
        assertIs<OpenPublicationState.Loading>(first.opening.value)
        repeat(20) { assertSame(first, owner.applicationSession()); owner.flushProgress() }
        source.gate!!.complete(Unit); runCurrent()
        assertIs<OpenPublicationState.Ready>(first.opening.value); assertEquals(1, source.lookups); assertEquals(1, prep.prepared)
    } }
    @Test fun differentPublicationHasIndependentPositionAndBackReopenRestoresTheOriginal() = runTest { fixture { owner, source, prep, store, _ ->
        val session = owner.applicationSession(); session.navigate(Destination.SEARCH); session.openSearch(source.books[0]); runCurrent()
        val first = assertNotNull(assertIs<OpenPublicationState.Ready>(session.opening.value).reading); first.report(60)
        session.back(); runCurrent(); session.openSearch(source.books[1]); runCurrent()
        val second = assertNotNull(assertIs<OpenPublicationState.Ready>(session.opening.value).reading)
        assertEquals(0, second.codePointOffset.value); first.report(0); assertEquals(60, first.codePointOffset.value)
        second.report(30); session.back(); runCurrent(); session.openSearch(source.books[0]); runCurrent()
        val reopened = assertNotNull(assertIs<OpenPublicationState.Ready>(session.opening.value).reading)
        assertEquals(60, reopened.codePointOffset.value); assertNotSame(first, reopened)
        assertEquals(3, prep.prepared); assertEquals(2, store.values.size)
    } }
    @Test fun textStillHasOneSemanticOwnerWhenDurableStorageIsAbsent() = runTest { fixture(false) { owner, source, _, _, _ ->
        val session = owner.applicationSession(); session.navigate(Destination.SEARCH); session.openSearch(source.books[0]); runCurrent()
        val reading = assertNotNull(assertIs<OpenPublicationState.Ready>(session.opening.value).reading)
        reading.report(60); owner.flushProgress()
        assertSame(session, owner.applicationSession()); assertEquals(60, reading.codePointOffset.value)
        session.back(); session.openSearch(source.books[0]); runCurrent()
        assertEquals(0, assertNotNull(assertIs<OpenPublicationState.Ready>(session.opening.value).reading).codePointOffset.value)
    } }
    @Test fun finalOwnerCloseFlushesLatestProgressAndRetiresLateCallbacksExactlyOnce() = runTest { fixture { owner, source, prep, store, _ ->
        val session = owner.applicationSession(); session.navigate(Destination.SEARCH); session.openSearch(source.books[0]); runCurrent()
        val ready = assertIs<OpenPublicationState.Ready>(session.opening.value); val reading = assertNotNull(ready.reading)
        reading.report(60); owner.close(); owner.close(); runCurrent(); reading.report(0)
        assertIs<OpenPublicationState.Idle>(session.searchSession.value.opening.state.value)
        assertEquals(60, reading.codePointOffset.value)
        assertEquals(60L, (assertNotNull(store.values[ready.document.progressId]).locator as ReadingLocator.Text).codePointOffset)
        assertEquals(1, prep.documentCloses); assertFailsWith<IllegalStateException> { owner.applicationSession() }
    } }
}
