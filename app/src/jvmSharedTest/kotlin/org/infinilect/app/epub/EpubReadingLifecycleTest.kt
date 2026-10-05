// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import org.infinilect.app.*
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.collections.*
import org.infinilect.app.progress.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*

/** Real ZIP/parser/progress/SQLite/resource-loader integration on host files; NOT a device test. */
class EpubReadingLifecycleTest {
    private lateinit var root: Path
    @BeforeTest fun setup() { root = Files.createTempDirectory("epub-reader-lifecycle") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    private class Source : PublicationSource {
        private val delegate = DevelopmentEpubSource()
        override val id = delegate.id
        var overrideBytes: ByteArray? = null
        var details = 0; var loads = 0; var failDetails = false; var failLoad = false
        var beforeLoad: suspend () -> Unit = {}
        override suspend fun search(query: String, pageToken: String?) = delegate.search(query, pageToken)
        override suspend fun getPublication(publicationId: PublicationId): Publication? { details++; return if (failDetails) null else delegate.getPublication(publicationId) }
        override suspend fun loadResource(resource: PublicationResource): ResourceContent { loads++; beforeLoad(); if (failLoad) error("private details"); return overrideBytes?.let { EpubBytes(it) } ?: delegate.loadResource(resource) }
    }
    private inner class Owner(scope: CoroutineScope, val source: Source = Source()) {
        val cache = DiskResourceCache(root.resolve("resource-cache-v1"))
        val store = FileReadingProgressStore(root.resolve(PROGRESS_DIRECTORY_NAME))
        val progress = ProgressPersistence(store, clock = ::progressTime)
        val database = SqlCollectionsStore({ JdbcSqliteDriver("jdbc:sqlite:${root.resolve("collections.db")}", collectionsJdbcProperties()).also(::initializeCollectionsSchema) })
        val collections = ApplicationCollections(database.library, database.history, release = database::close)
        val preparer = FileEpubPreparer(root.resolve("epub-preparation-v1"))
        val sources = ApplicationSources(listOf(SourceOption("EPUB development demo", source, epubReadingEnabled = true)),
            createLoader = { cache.loader(it.id, DirectResourceLoader(it)) }, progress = progress, collections = collections, epubPreparer = preparer) { cache.close() }
        val session = ApplicationSession(sources, scope)
        init { sources.attach(session) }
        suspend fun searchOpen(): OpenPublicationState.EpubReady {
            val query = session.searchSession.value
            query.editQuery("original")
            if (query.search.state.value !is org.infinilect.app.search.SearchState.Results) {
                query.submitSearch()
                withTimeout(5000) { query.search.state.first { it is org.infinilect.app.search.SearchState.Results } }
            }
            val publication = source.search("original").publications.single()
            session.openSearch(publication)
            return ready()
        }
        suspend fun ready() = withTimeout(5000) { assertIs<OpenPublicationState.EpubReady>(session.opening.first { it is OpenPublicationState.EpubReady || it is OpenPublicationState.Error }) }
        suspend fun close() { sources.close(); sources.awaitProgressClosed() }
    }
    private suspend fun EpubReaderController.next(index: Int): EpubReaderState.Ready {
        chapter(index)
        return withTimeout(5000) { assertIs<EpubReaderState.Ready>(state.first { it is EpubReaderState.Ready || it is EpubReaderState.Error }) }
    }
    @Test fun realZipOpensFirstChapterAndRecordsSuccessfulHistory() = runBlocking<Unit> {
        val owner = Owner(this)
        try {
            val open = owner.searchOpen()
            val chapter = assertIs<EpubReaderState.Ready>(open.reader.state.value).chapter
            assertTrue(chapter.blocks.any { it.runs.any { run -> run.strong } }); assertEquals(3, open.reader.toc.size)
            owner.collections.flushHistory()
            assertEquals(open.publication.id, assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(owner.database.history.listRecent()).value.single().publication.id)
        } finally { owner.close() }
    }
    @Test fun backPreservesSearchAndClosesPreparedDocument() = runBlocking<Unit> {
        val owner = Owner(this)
        try {
            val open = owner.searchOpen(); val doc = open.reader.document
            owner.session.back()
            assertEquals("original", owner.session.searchSession.value.query.value)
            assertIs<org.infinilect.app.search.SearchState.Results>(owner.session.searchSession.value.search.state.value)
            assertFails { doc.openResource(doc.manifest.first().path) }
        } finally { owner.close() }
        assertTrue(payloads(root).isEmpty())
    }
    @Test fun readerBackFromSearchFlushesPendingProgressAndIgnoresClosedReaderCallbacks() = runBlocking<Unit> {
        verifyReaderBack(Destination.SEARCH)
    }
    @Test fun readerBackFromLibraryFlushesPendingProgressAndKeepsPreviousDestination() = runBlocking<Unit> {
        verifyReaderBack(Destination.LIBRARY)
    }
    @Test fun readerBackFromHistoryFlushesPendingProgressAndKeepsPreviousDestination() = runBlocking<Unit> {
        verifyReaderBack(Destination.HISTORY)
    }
    private suspend fun CoroutineScope.verifyReaderBack(destination: Destination) {
        val owner = Owner(this)
        val id: ReadingProgressId
        val expected: ReadingLocator.Epub
        try {
            var open = owner.searchOpen()
            val search = owner.session.searchSession.value
            val results = search.search.state.value
            if (destination != Destination.SEARCH) {
                val snapshot = PublicationSnapshot.from(open.publication)
                assertIs<LocalStoreResult.Success<LibraryEntry>>(owner.database.library.put(snapshot, 1))
                owner.collections.flushHistory()
                owner.session.back(); owner.session.navigate(destination); owner.session.openSaved(snapshot)
                open = owner.ready()
            }
            val chapter = open.reader.next(2)
            open.reader.report(chapter.ticket, 15, 5)
            expected = chapter.chapter.locator(15, 5)
            id = open.reader.progressId
            assertTrue(owner.session.handlesBack())
            // Same command installed in Android's system callback and the visible Back button.
            // No two-second wait: leaving must flush the latest pending semantic position.
            owner.session.back()
            assertIs<OpenPublicationState.Idle>(owner.session.opening.value)
            assertEquals(destination, owner.session.destination.value)
            assertSame(search, owner.session.searchSession.value)
            assertEquals("original", search.query.value); assertSame(results, search.search.state.value)
            assertFails { open.reader.document.openResource(chapter.chapter.path) }
            assertEquals(0, open.reader.retainedChapters)
            open.reader.report(chapter.ticket, 0, 0); open.reader.chapter(0)
            assertIs<EpubReaderState.Loading>(open.reader.state.value)
            if (destination != Destination.SEARCH) {
                assertTrue(owner.session.handlesBack()); owner.session.back()
            }
            assertFalse(owner.session.handlesBack())
        } finally { owner.close() }
        // Entire owner/writer is drained; a brand-new store must read committed bytes, not RAM.
        assertEquals(expected, FileReadingProgressStore(root.resolve(PROGRESS_DIRECTORY_NAME)).get(id)?.locator)
        assertTrue(payloads(root).isEmpty())
    }
    @Test fun readerBackDuringChapterLoadingCancelsNavigationAndCannotReopenReader() = runBlocking<Unit> {
        val owner = Owner(this)
        try {
            val open = owner.searchOpen()
            open.reader.chapter(2)
            assertIs<EpubReaderState.Loading>(open.reader.state.value)
            owner.session.back()
            delay(100)
            assertIs<OpenPublicationState.Idle>(owner.session.opening.value)
            assertEquals(Destination.SEARCH, owner.session.destination.value)
            assertFalse(owner.session.handlesBack()); assertEquals(0, open.reader.retainedChapters)
            assertIs<EpubReaderState.Loading>(open.reader.state.value)
            assertFails { open.reader.document.openResource(open.reader.document.manifest.first().path) }
        } finally { owner.close() }
        assertTrue(payloads(root).isEmpty())
    }
    @Test fun fullOwnerRestartAndCacheDeletionRestoreEpubFromCommittedRecord() = runBlocking<Unit> {
        val first = Owner(this)
        val open = first.searchOpen()
        val chapter = open.reader.next(2)
        open.reader.report(chapter.ticket, 15, 5)
        val expected = chapter.chapter.locator(15, 5)
        first.session.back(); first.close()
        assertEquals(1, Files.list(root.resolve(PROGRESS_DIRECTORY_NAME)).use { it.filter { p -> p.toString().endsWith(".progress") }.count() })
        root.resolve("resource-cache-v1").toFile().deleteRecursively()
        root.resolve("epub-preparation-v1").toFile().deleteRecursively()
        val second = Owner(this)
        try {
            val reopened = second.searchOpen()
            val restored = assertIs<EpubReaderState.Ready>(reopened.reader.state.value)
            assertEquals(2, restored.spineIndex); assertEquals(15 to 5, restored.initialPosition)
            assertEquals(expected, second.store.get(reopened.reader.progressId)?.locator)
            assertEquals(1, second.source.details); assertEquals(1, second.source.loads)
            assertNull(reopened.publication.resources.single().revision)
        } finally { second.close() }
    }
    @Test fun libraryAndHistoryReopenReresolveSourceAndRestoreEpub() = runBlocking<Unit> {
        val owner = Owner(this)
        try {
            val opened = owner.searchOpen()
            val snapshot = PublicationSnapshot.from(opened.publication)
            assertIs<LocalStoreResult.Success<LibraryEntry>>(owner.database.library.put(snapshot, 1))
            val chapter = opened.reader.next(1); opened.reader.report(chapter.ticket, 10, 3); owner.session.back()
            for (destination in listOf(Destination.LIBRARY, Destination.HISTORY)) {
                owner.session.navigate(destination); owner.session.openSaved(snapshot)
                val restored = assertIs<EpubReaderState.Ready>(owner.ready().reader.state.value)
                assertEquals(1, restored.spineIndex); assertEquals(10 to 3, restored.initialPosition)
                owner.session.back(); assertEquals(destination, owner.session.destination.value)
            }
            assertEquals(3, owner.source.details); assertEquals(3, owner.source.loads)
            owner.collections.flushHistory(); assertEquals(1, assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(owner.database.history.listRecent()).value.size)
        } finally { owner.close() }
    }
    @Test fun failedLibraryResolutionKeepsSavedEntryAndCreatesNoHistory() = runBlocking<Unit> {
        val owner = Owner(this)
        try {
            val publication = owner.source.search("original").publications.single()
            val snapshot = PublicationSnapshot.from(publication)
            owner.database.library.put(snapshot, 1); owner.source.failDetails = true
            owner.session.navigate(Destination.LIBRARY); owner.session.openSaved(snapshot)
            withTimeout(5000) { owner.session.opening.first { it is OpenPublicationState.Error } }
            assertNotNull(assertIs<LocalStoreResult.Success<LibraryEntry?>>(owner.database.library.get(publication.id)).value)
            owner.collections.flushHistory(); assertTrue(assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(owner.database.history.listRecent()).value.isEmpty())
        } finally { owner.close() }
    }
    @Test fun failedAcquisitionDoesNotRecordHistoryOrDeleteLibrary() = runBlocking<Unit> {
        val owner = Owner(this)
        try {
            val publication = owner.source.search("original").publications.single(); val snapshot = PublicationSnapshot.from(publication)
            owner.database.library.put(snapshot, 1); owner.source.failLoad = true
            owner.session.navigate(Destination.LIBRARY); owner.session.openSaved(snapshot)
            withTimeout(5000) { owner.session.opening.first { it is OpenPublicationState.Error } }
            assertNotNull(assertIs<LocalStoreResult.Success<LibraryEntry?>>(owner.database.library.get(publication.id)).value)
            owner.collections.flushHistory(); assertTrue(assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(owner.database.history.listRecent()).value.isEmpty())
        } finally { owner.close() }
    }
    @Test fun cancelDuringAcquisitionClosesSessionWithoutLateReaderOrHistory() = runBlocking<Unit> {
        val owner = Owner(this)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        owner.source.beforeLoad = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        try {
            val publication = owner.source.search("original").publications.single()
            owner.session.openSearch(publication); withTimeout(5000) { entered.await() }
            owner.session.back(); release.complete(Unit); delay(100)
            assertIs<OpenPublicationState.Idle>(owner.session.opening.value)
            owner.collections.flushHistory(); assertTrue(assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(owner.database.history.listRecent()).value.isEmpty())
        } finally { release.complete(Unit); owner.close() }
        assertTrue(payloads(root).isEmpty())
    }
    @Test fun historicalTextAndNewEpubRecordsCoexistWithoutMigration() = runBlocking<Unit> {
        val store = FileReadingProgressStore(root.resolve(PROGRESS_DIRECTORY_NAME))
        val publication = DevelopmentEpubSource().search("original").publications.single()
        val textId = ReadingProgressId(publication.id, "old-text", PublicationFormat.TEXT)
        val epubId = ReadingProgressId(publication.id, publication.resources.single().key, PublicationFormat.EPUB)
        val text = ReadingProgress(textId, ReadingLocator.Text(3, 10), 0.3, 1)
        val epub = ReadingProgress(epubId, ReadingLocator.Epub(EpubEntryPath("OPS/chapter2.xhtml"), listOf(0, 10), 5, 0.4), 0.5, 1)
        assertTrue(store.save(text)); assertTrue(store.save(epub))
        val reopened = FileReadingProgressStore(root.resolve(PROGRESS_DIRECTORY_NAME))
        assertEquals(text, reopened.get(textId)); assertEquals(epub, reopened.get(epubId))
        val versions = Files.list(root.resolve(PROGRESS_DIRECTORY_NAME)).use { paths -> paths.filter { it.toString().endsWith(".progress") }.map { ByteBuffer.wrap(Files.readAllBytes(it)).getInt(8) }.toList() }
        assertEquals(setOf(1, 2), versions.toSet())
    }
    @Test fun unsupportedFutureEpubVersionIsRejectedWithoutDestroyingText() = runBlocking<Unit> {
        val store = FileReadingProgressStore(root.resolve(PROGRESS_DIRECTORY_NAME))
        val id = ReadingProgressId(epubId, "book", PublicationFormat.EPUB)
        assertTrue(store.save(ReadingProgress(id, ReadingLocator.Epub(EpubEntryPath("OPS/chapter.xhtml"), listOf(0), 0, 0.0), 0.0, 1)))
        val path = Files.list(root.resolve(PROGRESS_DIRECTORY_NAME)).use { files -> files.filter { it.toString().endsWith(".progress") }.findFirst().get() }
        val bytes = Files.readAllBytes(path); ByteBuffer.wrap(bytes).putInt(8, 99); Files.write(path, bytes)
        assertNull(FileReadingProgressStore(root.resolve(PROGRESS_DIRECTORY_NAME)).get(id))
    }
    @Test fun developmentSourceRejectsForeignIdsAndResources() = runBlocking<Unit> {
        val source = DevelopmentEpubSource(); val publication = source.search("original").publications.single()
        assertFailsWith<IllegalArgumentException> { source.getPublication(publication.id.copy(sourceId = SourceId("internet-archive"))) }
        assertFailsWith<IllegalArgumentException> { source.loadResource(publication.resources.single().copy(key = "another")) }
        assertFailsWith<IllegalArgumentException> { source.search("original", "next") }
    }
    @Test fun repeatedOpenCloseDoesNotLeavePreparedPayloadsOrDuplicateHistory() = runBlocking<Unit> {
        val owner = Owner(this)
        try { repeat(5) { owner.searchOpen(); owner.session.back() }; owner.collections.flushHistory()
            assertEquals(1, assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(owner.database.history.listRecent()).value.size)
        } finally { owner.close() }
        assertTrue(payloads(root).isEmpty())
    }
    @Test fun rendererRejectionAfterStructuralPreparationDoesNotRecordHistory() = runBlocking<Unit> {
        val owner = Owner(this)
        owner.source.overrideBytes = EpubFixture().apply {
            change("OPS/chapter.xhtml") { it.replace("</body>", "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle/></svg></body>") }
        }.zip()
        try {
            val publication = owner.source.search("original").publications.single()
            owner.session.openSearch(publication)
            withTimeout(5000) { owner.session.opening.first { it is OpenPublicationState.Error } }
            owner.collections.flushHistory()
            assertTrue(assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(owner.database.history.listRecent()).value.isEmpty())
        } finally { owner.close() }
        assertTrue(payloads(root).isEmpty())
    }

    @Test fun settingsThenBackAndFreshOwnerRestoreSemanticPassage() = runBlocking<Unit> {
        val first = Owner(this)
        val open = first.searchOpen(); val ready = open.reader.next(2)
        val block = ready.chapter.blocks.indexOfFirst { it.text.startsWith("Passage 20 ") }
        open.reader.report(ready.ticket, block, 12)
        open.reader.presentationChanged(EpubReaderSettings(fontSize = 28, margin = 36, lineSpacingPercent = 180, theme = EpubReadingTheme.DARK))
        assertEquals(block to 12, assertIs<EpubReaderState.Ready>(open.reader.state.value).initialPosition)
        first.session.back(); first.close()
        root.resolve("resource-cache-v1").toFile().deleteRecursively()
        root.resolve("epub-preparation-v1").toFile().deleteRecursively()
        val second = Owner(this)
        try {
            val restored = second.searchOpen(); val position = assertIs<EpubReaderState.Ready>(restored.reader.state.value)
            assertEquals(2, position.spineIndex); assertEquals(block to 12, position.initialPosition)
            assertEquals(EpubReaderSettings(), restored.reader.settings.value) // settings deliberately session-only
            assertEquals(1, second.source.details); assertEquals(1, second.source.loads)
        } finally { second.close() }
    }
    @Test fun originalPreparedZipExposesOnlyLocalDeclaredRasterArtwork() = runBlocking<Unit> {
        val owner = Owner(this)
        try {
            val open = owner.searchOpen(); val c = assertIs<EpubReaderState.Ready>(open.reader.state.value).chapter
            assertEquals(listOf("image/png", "image/jpeg", "image/svg+xml"), c.blocks.mapNotNull { it.image?.mediaType })
            for (image in c.blocks.mapNotNull { it.image }.filter { it.mediaType != "image/svg+xml" }) {
                val bytes = open.reader.document.openResource(image.path).readBytes(2 * 1024 * 1024)
                assertEquals(96 to 64, epubRasterDimensions(bytes, image.mediaType))
            }
            assertEquals(1, owner.source.loads) // media uses document handles, never another acquisition
        } finally { owner.close() }
        assertTrue(payloads(root).isEmpty())
    }

}
