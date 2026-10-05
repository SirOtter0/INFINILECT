// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.reader.TextDocument
import org.infinilect.app.reader.handlesBack
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationSourcesTest {
    @Test fun ownerCloseDrainsFinalEpubSettingsWithoutDependingOnReaderScope() = runTest {
        var saved: org.infinilect.app.reader.epub.EpubReaderPreferences? = null
        val store = object : org.infinilect.app.reader.epub.EpubReaderSettingsStore {
            override suspend fun load() = org.infinilect.app.reader.epub.EpubReaderPreferences()
            override suspend fun save(preferences: org.infinilect.app.reader.epub.EpubReaderPreferences): Boolean { saved = preferences; return true }
        }
        val preferences = org.infinilect.app.reader.epub.EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        preferences.awaitLoaded()
        val owner = ApplicationSources(emptyList(), epubSettings = preferences) {}
        val expected = org.infinilect.app.reader.epub.EpubReaderSettings(fontSize = 30)
        preferences.submit(preferences.claimReader(), expected)
        owner.close(); owner.close(); owner.awaitProgressClosed()
        assertEquals(expected, assertNotNull(saved).settings)
        assertFalse(preferences.saveFailed.value)
    }
    private class Source : PublicationSource {
        override val id = SourceId("fixture")
        val resource = PublicationResource(PublicationId(id, "1"), "text", PublicationFormat.TEXT, "text/plain")
        val publication = Publication(resource.publicationId, "Title", PublicationType.DOCUMENT, resources = listOf(resource))
        var handlesClosed = 0
        val reading = CompletableDeferred<Unit>()
        override suspend fun search(query: String, pageToken: String?) = SearchPage(listOf(publication))
        override suspend fun getPublication(publicationId: PublicationId) = publication
        override suspend fun loadResource(resource: PublicationResource): ResourceContent = object : ResourceContent {
            override val sizeBytes = 4L
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.complete(Unit); awaitCancellation()
            }
            override fun close() { handlesClosed++ }
        }
    }

    @Test fun ownerCancelsSessionBeforeTransportAndClosesOnlyOnce() = runTest {
        val source = Source()
        val session = ReadingSession(source, this, true, decodingDispatcher = StandardTestDispatcher(testScheduler))
        var releases = 0
        val owner = ApplicationSources(listOf(SourceOption("Fixture", source, true))) {
            assertIs<OpenPublicationState.Idle>(session.opening.state.value)
            releases++
        }
        owner.attach(session)
        session.open(source.publication); source.reading.await()
        owner.close(); owner.close(); owner.detach(session)
        advanceUntilIdle()
        assertEquals(1, releases)
        assertEquals(1, source.handlesClosed)
        assertFailsWith<IllegalStateException> { owner.attach(session) }
        session.editQuery("late")
        assertEquals("", session.query.value)
    }

    @Test fun switchingAttachedSessionDiscardsOldWorkWithoutClosingSources() = runTest {
        val first = Source(); val second = Source()
        val old = ReadingSession(first, this, true, decodingDispatcher = StandardTestDispatcher(testScheduler))
        val fresh = ReadingSession(second, this, true, decodingDispatcher = StandardTestDispatcher(testScheduler))
        var releases = 0
        val owner = ApplicationSources(listOf(SourceOption("Fixture", first, true))) { releases++ }
        owner.attach(old); old.open(first.publication); first.reading.await()
        owner.attach(fresh); owner.detach(old); advanceUntilIdle()
        assertIs<OpenPublicationState.Idle>(old.opening.state.value)
        assertEquals(1, first.handlesClosed); assertEquals(0, releases)
        fresh.editQuery("retained")
        owner.close(); fresh.editQuery("late")
        assertEquals("retained", fresh.query.value); assertEquals(1, releases)
    }

    @Test fun disposalWithoutSessionIsIdempotent() {
        var releases = 0
        val owner = ApplicationSources(emptyList()) { releases++ }
        owner.close(); owner.close()
        assertEquals(1, releases)
    }

    @Test fun ownerClosesAndDrainsOptionalEpubPreparationWithoutChangingTextSession() = runTest {
        var closes = 0
        var drains = 0
        val preparer = object : org.infinilect.app.reader.EpubPreparer {
            override suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader): EpubDocument =
                error("No EPUB action is enabled in the UI")
            override fun close() { closes++ }
            override suspend fun awaitClosed() { drains++ }
        }
        val owner = ApplicationSources(emptyList(), epubPreparer = preparer) {
            assertEquals(1, closes)
        }
        owner.close(); owner.close(); owner.awaitProgressClosed()
        assertEquals(1, closes); assertEquals(1, drains)
    }

    @Test fun sessionReceivesOwnedLoaderAndOwnerCancelsBeforeReleasingCacheAndSources() = runTest {
        val source = Source()
        var loads = 0
        val loader = object : ResourceLoader {
            override suspend fun load(resource: PublicationResource): ResourceContent {
                loads++; return source.loadResource(resource)
            }
        }
        lateinit var session: ReadingSession
        val owner = ApplicationSources(listOf(SourceOption("Fixture", source, true)), createLoader = { loader }) {
            assertIs<OpenPublicationState.Idle>(session.opening.state.value)
        }
        session = ReadingSession(source, this, true, owner.loaderFor(source), StandardTestDispatcher(testScheduler))
        owner.attach(session); session.open(source.publication); source.reading.await()
        assertEquals(1, loads); owner.close(); advanceUntilIdle()
        assertEquals(1, source.handlesClosed)
        assertFailsWith<IllegalStateException> { owner.loaderFor(source) }
    }

    @Test fun platformBackHandlesLoadingReaderAndErrorButNotRootSearch() {
        val source = Source()
        assertFalse(OpenPublicationState.Idle.handlesBack())
        assertTrue(OpenPublicationState.Loading(source.publication).handlesBack())
        assertTrue(OpenPublicationState.Ready(TextDocument(source.publication.id, "Title", "text")).handlesBack())
        assertTrue(OpenPublicationState.Error(source.publication, "Safe message").handlesBack())
    }
}
