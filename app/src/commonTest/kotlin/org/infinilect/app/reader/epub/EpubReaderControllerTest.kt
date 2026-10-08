// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*
import org.infinilect.app.reader.EpubException
import org.infinilect.app.reader.EpubFailure

@OptIn(ExperimentalCoroutinesApi::class)
class EpubReaderControllerTest {
    private val publicationId = PublicationId(SourceId("fixture"), "epub")
    private val id = ReadingProgressId(publicationId, "book", PublicationFormat.EPUB)
    private val paths = (1..3).map { EpubEntryPath("OPS/chapter$it.xhtml") }
    private class SettingsStore : EpubReaderSettingsStore {
        var record = EpubReaderPreferences()
        var saves = 0
        override suspend fun load() = record
        override suspend fun save(preferences: EpubReaderPreferences): Boolean { saves++; record = preferences; return true }
    }
    @Test fun immediateReaderBackPersistsGlobalPreferencesForAnotherEpubAndPreservesLocator() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsStore = SettingsStore()
        val preferences = EpubSettingsPersistence(settingsStore, dispatcher)
        val progressStore = Store(); val progress = ProgressPersistence(progressStore, dispatcher) { 5 }
        val first = EpubReaderController(Doc(), id, this, Parser(), progress, preferences)
        val settings = EpubReaderSettings(26, 190, 36, EpubReadingTheme.DARK)
        try {
            first.initialize(null)
            val original = assertIs<EpubReaderState.Ready>(first.state.value)
            first.report(original.ticket, 0, 2)
            first.presentationChanged(settings)
            assertEquals(0 to 2, assertIs<EpubReaderState.Ready>(first.state.value).initialPosition)
            first.close(); runCurrent()
            assertEquals(settings, settingsStore.record.settings)
            val otherId = ReadingProgressId(PublicationId(SourceId("fixture"), "another"), "another-book", PublicationFormat.EPUB)
            val second = EpubReaderController(Doc(otherId.publicationId), otherId, this, Parser(), preferences = preferences)
            try {
                second.initialize(null); assertEquals(settings, second.settings.value)
                first.presentationChanged(EpubReaderSettings()) // old reader is closed
                assertEquals(settings, preferences.settings.value)
                assertEquals(1, settingsStore.saves)
                second.presentationChanged() // viewport/layout event, not a preference edit
                second.close(); runCurrent(); assertEquals(1, settingsStore.saves)
            } finally { second.close() }
            assertEquals(2L, (assertNotNull(progressStore.values[id]).locator as ReadingLocator.Epub).codePointOffset)
        } finally { first.close(); preferences.close(); preferences.awaitClosed(); progress.close(); progress.awaitClosed() }
    }
    private inner class Doc(override val publicationId: PublicationId = this@EpubReaderControllerTest.publicationId) : EpubDocument {
        override val packagePath = EpubEntryPath("OPS/package.opf")
        override val metadata = EpubMetadata("fixture", "Title", listOf("en"), "2026-10-04T00:00:00Z")
        override val manifest = paths.mapIndexed { i, path -> EpubManifestItem("c$i", path, "application/xhtml+xml") }
        override val spine = manifest.map { EpubSpineItem(it.id) }
        override val navigationItemId = "nav"
        var closes = 0
        override suspend fun openResource(path: EpubEntryPath): ResourceContent = error("Parser fake must not acquire bytes")
        override fun close() { closes++ }
    }
    private inner class Parser : EpubParser {
        val parsed = mutableListOf<EpubEntryPath>()
        var action: suspend (EpubEntryPath) -> Unit = {}
        override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter {
            action(path); parsed.add(path)
            val blocks = listOf(EpubBlock(listOf(0, 0), 0, EpubBlockKind.PARAGRAPH, listOf(EpubRun("A📖BC")), 0),
                EpubBlock(listOf(0, 1), 0, EpubBlockKind.HEADING, listOf(EpubRun("Later")), 4))
            return EpubChapter(path, blocks, mapOf("later" to EpubPosition(listOf(0, 1), 0)))
        }
        override suspend fun toc(document: EpubDocument) = paths.mapIndexed { i, path -> EpubTocEntry("Chapter $i", EpubTarget(path), 0) }
    }
    private class Store : ReadingProgressStore {
        val values = mutableMapOf<ReadingProgressId, ReadingProgress>()
        override suspend fun get(id: ReadingProgressId) = values[id]
        override suspend fun save(progress: ReadingProgress): Boolean { values[progress.id] = progress; return true }
        override suspend fun remove(id: ReadingProgressId): Boolean { values.remove(id); return true }
    }
    private suspend fun TestScope.use(action: suspend (EpubReaderController, Doc, Parser, Store, ProgressPersistence) -> Unit) {
        val doc = Doc(); val parser = Parser(); val store = Store()
        val persistence = ProgressPersistence(store, StandardTestDispatcher(testScheduler), { testScheduler.currentTime + 1 })
        val reader = EpubReaderController(doc, id, this, parser, persistence)
        try { reader.initialize(null); action(reader, doc, parser, store, persistence) }
        finally { reader.close(); persistence.close(); persistence.awaitClosed() }
    }
    @Test fun firstChapterAndTocAreReadyBeforeReaderPublication() = runTest { use { reader, _, _, _, _ ->
        assertEquals(paths[0], assertIs<EpubReaderState.Ready>(reader.state.value).chapter.path); assertEquals(3, reader.toc.size)
    } }
    @Test fun nextAndPreviousSpineNavigationIsOrdered() = runTest { use { reader, _, _, _, _ ->
        reader.chapter(1); advanceUntilIdle(); assertEquals(1, assertIs<EpubReaderState.Ready>(reader.state.value).spineIndex)
        reader.chapter(0); advanceUntilIdle(); assertEquals(0, assertIs<EpubReaderState.Ready>(reader.state.value).spineIndex)
    } }
    @Test fun internalAnchorNavigatesToSemanticBlock() = runTest { use { reader, _, _, _, _ ->
        reader.navigate(EpubTarget(paths[1], "later")); advanceUntilIdle(); assertEquals(1 to 0, assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
    } }
    @Test fun unknownSpineTargetCannotOpenAnyResource() = runTest { use { reader, _, parser, _, _ ->
        val before = reader.state.value; reader.navigate(EpubTarget(EpubEntryPath("not-owned.xhtml"))); advanceUntilIdle()
        assertEquals(before, reader.state.value); assertEquals(1, parser.parsed.size)
    } }
    @Test fun missingAnchorIsSafeError() = runTest { use { reader, _, _, _, _ ->
        reader.navigate(EpubTarget(paths[1], "missing")); advanceUntilIdle(); assertIs<EpubReaderState.Error>(reader.state.value)
    } }
    @Test fun parsedChapterCacheIsBoundedAndReusesVisitedChapter() = runTest { use { reader, _, parser, _, _ ->
        reader.chapter(1); advanceUntilIdle(); reader.chapter(0); advanceUntilIdle(); assertEquals(2, parser.parsed.size)
        reader.chapter(2); advanceUntilIdle(); assertEquals(2, reader.retainedChapters)
        reader.chapter(1); advanceUntilIdle(); assertEquals(4, parser.parsed.size)
    } }
    @Test fun loadingStateIsExplicit() = runTest { use { reader, _, _, _, _ ->
        reader.chapter(1); assertIs<EpubReaderState.Loading>(reader.state.value); advanceUntilIdle(); assertIs<EpubReaderState.Ready>(reader.state.value)
    } }
    @Test fun failedChapterDoesNotExposeParserDetails() = runTest { use { reader, _, parser, _, _ ->
        parser.action = { error("secret path and content") }; reader.chapter(1); advanceUntilIdle()
        assertFalse(assertIs<EpubReaderState.Error>(reader.state.value).message.contains("secret"))
    } }
    @Test fun categorizedChapterFailureKeepsOnlySuccessfulProgressAndSafeMessages() = runTest {
        for (failure in EpubFailure.entries) use { reader, _, parser, store, _ ->
            val ready = assertIs<EpubReaderState.Ready>(reader.state.value)
            reader.report(ready.ticket, 0, 2)
            val progression = reader.progression.value
            parser.action = { throw EpubException(failure, IllegalStateException("secret filesystem path")) }
            reader.chapter(1); advanceUntilIdle()
            assertEquals(failure.userMessage, assertIs<EpubReaderState.Error>(reader.state.value).message)
            reader.report(ready.ticket, 1, 4); reader.flush(); runCurrent()
            assertEquals(progression, reader.progression.value); assertEquals(1, reader.retainedChapters)
            val locator = assertIs<ReadingLocator.Epub>(assertNotNull(store.values[id]).locator)
            assertEquals(paths[0], locator.spinePath); assertEquals(2L, locator.codePointOffset)
        }
    }
    @Test fun obsoleteCategorizedFailureCannotReplaceSuccessfullyPresentedChapter() = runTest { use { reader, _, parser, store, _ ->
        val gate = CompletableDeferred<Unit>()
        parser.action = { path -> if (path == paths[1]) withContext(NonCancellable) { gate.await(); throw EpubException(EpubFailure.LIMIT) } }
        reader.chapter(1); runCurrent(); reader.chapter(2); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        val ready = assertIs<EpubReaderState.Ready>(reader.state.value)
        assertEquals(paths[2], ready.chapter.path); reader.flush(); runCurrent()
        assertEquals(paths[2], assertIs<ReadingLocator.Epub>(assertNotNull(store.values[id]).locator).spinePath)
    } }
    @Test fun noncooperativeLateChapterCannotReplaceNewerNavigation() = runTest { use { reader, _, parser, _, _ ->
        val gate = CompletableDeferred<Unit>()
        parser.action = { path -> if (path == paths[1]) withContext(NonCancellable) { gate.await() } }
        reader.chapter(1); runCurrent(); reader.chapter(2); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(paths[2], assertIs<EpubReaderState.Ready>(reader.state.value).chapter.path)
    } }
    @Test fun closeDuringChapterLoadingInvalidatesLateResult() = runTest { use { reader, _, parser, _, _ ->
        val gate = CompletableDeferred<Unit>(); parser.action = { withContext(NonCancellable) { gate.await() } }
        reader.chapter(1); runCurrent(); reader.close(); gate.complete(Unit); advanceUntilIdle(); assertEquals(0, reader.retainedChapters)
        assertIs<EpubReaderState.Loading>(reader.state.value)
    } }
    @Test fun closeIsIdempotentAndClearsRetainedChapters() = runTest { use { reader, doc, _, _, _ ->
        reader.close(); reader.close(); assertEquals(1, doc.closes); assertEquals(0, reader.retainedChapters); assertTrue(reader.toc.isEmpty())
    } }
    @Test fun staleUiProgressCallbackIsIgnored() = runTest { use { reader, _, _, store, _ ->
        val old = assertIs<EpubReaderState.Ready>(reader.state.value).ticket
        reader.chapter(1); advanceUntilIdle(); val saved = store.values[id]
        reader.report(old, 1, 5); reader.flush(); advanceUntilIdle(); assertEquals(saved, store.values[id])
    } }
    @Test fun saveUsesEpubLocatorAndWholePublicationApproximation() = runTest { use { reader, _, _, store, _ ->
        val ready = assertIs<EpubReaderState.Ready>(reader.state.value)
        reader.report(ready.ticket, 0, 2); reader.flush(); runCurrent()
        assertEquals(ReadingLocator.Epub(paths[0], listOf(0, 0), 2, 2.0 / 9), store.values[id]?.locator)
        assertEquals(2.0 / 27, store.values[id]?.progression)
    } }
    @Test fun fixedThrottleSavesWhileReaderRemainsOpen() = runTest { use { reader, _, _, store, _ ->
        val ticket = assertIs<EpubReaderState.Ready>(reader.state.value).ticket
        reader.report(ticket, 0, 2); advanceTimeBy(1000); reader.report(ticket, 0, 3)
        assertTrue(store.values.isEmpty()); advanceTimeBy(1000); runCurrent(); assertEquals(3L, (store.values[id]?.locator as ReadingLocator.Epub).codePointOffset)
    } }
    @Test fun leavingFlushesLatestMeaningfulProgress() = runTest { use { reader, _, _, store, _ ->
        reader.report(assertIs<EpubReaderState.Ready>(reader.state.value).ticket, 1, 3); reader.close(); runCurrent()
        assertEquals(3L, (store.values[id]?.locator as ReadingLocator.Epub).codePointOffset)
    } }
    @Test fun unchangedLogicalPositionDoesNotAmplifyWrites() = runTest { use { reader, _, _, store, _ ->
        val ticket = assertIs<EpubReaderState.Ready>(reader.state.value).ticket
        repeat(100) { reader.report(ticket, 0, 0) }; reader.flush(); runCurrent(); assertTrue(store.values.isEmpty())
    } }
    @Test fun restoredLocatorSelectsSpineAndUnicodePosition() = runTest {
        val doc = Doc(); val reader = EpubReaderController(doc, id, this, Parser())
        val locator = ReadingLocator.Epub(paths[2], listOf(0, 0), 2, 2.0 / 9)
        reader.initialize(ReadingProgress(id, locator, 0.8, 1))
        val ready = assertIs<EpubReaderState.Ready>(reader.state.value)
        assertEquals(2, ready.spineIndex); assertEquals(0 to 2, ready.initialPosition); reader.close()
    }
    @Test fun foreignProgressCannotRestoreAnotherPublication() = runTest {
        val reader = EpubReaderController(Doc(), id, this, Parser())
        reader.initialize(ReadingProgress(id.copy(resourceKey = "different"), ReadingLocator.Epub(paths[2], listOf(0), 3, 0.9), 0.9, 1))
        assertEquals(0, assertIs<EpubReaderState.Ready>(reader.state.value).spineIndex); reader.close()
    }
    @Test fun changedSpineFallsBackSafelyToFirstChapter() = runTest {
        val reader = EpubReaderController(Doc(), id, this, Parser())
        reader.initialize(ReadingProgress(id, ReadingLocator.Epub(EpubEntryPath("removed.xhtml"), listOf(0), 3, 0.9), 0.9, 1))
        assertEquals(0.0, reader.progression.value); reader.close()
    }
    @Test fun historicalTextLocatorIsNotUsedForEpubRestoration() = runTest {
        val reader = EpubReaderController(Doc(), id, this, Parser())
        reader.initialize(ReadingProgress(id.copy(format = PublicationFormat.TEXT), ReadingLocator.Text(2, 4), 0.5, 1))
        assertEquals(0 to 0, assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition); reader.close()
    }
    @Test fun timeoutProducesFixedSafeError() = runTest { use { reader, _, parser, _, _ ->
        parser.action = { awaitCancellation() }; reader.chapter(1); advanceTimeBy(15_001); runCurrent(); assertIs<EpubReaderState.Error>(reader.state.value)
    } }
    @Test fun outOfRangeChapterActionIsIgnored() = runTest { use { reader, _, _, _, _ ->
        val state = reader.state.value; reader.chapter(-1); reader.chapter(3); assertEquals(state, reader.state.value)
    } }
    @Test fun fontChangePreservesLatestUnicodeLocatorWithoutReparse() = runTest { use { reader, _, parser, store, _ ->
        val ready = assertIs<EpubReaderState.Ready>(reader.state.value)
        reader.report(ready.ticket, 0, 2)
        reader.presentationChanged(EpubReaderSettings(fontSize = 26))
        val after = assertIs<EpubReaderState.Ready>(reader.state.value)
        assertEquals(0 to 2, after.initialPosition); assertNotEquals(ready.ticket, after.ticket)
        assertEquals(1, parser.parsed.size); runCurrent()
        assertEquals(2L, (store.values[id]?.locator as ReadingLocator.Epub).codePointOffset)
    } }
    @Test fun marginsSpacingThemeAndViewportKeepSemanticPosition() = runTest { use { reader, _, _, _, _ ->
        reader.report(assertIs<EpubReaderState.Ready>(reader.state.value).ticket, 1, 3)
        for (settings in listOf(EpubReaderSettings(margin = 32), EpubReaderSettings(lineSpacingPercent = 190), EpubReaderSettings(theme = EpubReadingTheme.DARK))) {
            reader.presentationChanged(settings)
            assertEquals(1 to 3, assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
        }
        reader.presentationChanged() // orientation/viewport without a settings mutation
        assertEquals(1 to 3, assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
    } }
    @Test fun staleLayoutAfterSettingsCannotOverwriteProgress() = runTest { use { reader, _, _, store, _ ->
        val old = assertIs<EpubReaderState.Ready>(reader.state.value).ticket
        reader.report(old, 0, 2); reader.presentationChanged(EpubReaderSettings(fontSize = 24))
        reader.report(old, 1, 5); reader.flush(); runCurrent()
        assertEquals(2L, (store.values[id]?.locator as ReadingLocator.Epub).codePointOffset)
    } }
    @Test fun loadingSettingsDoNotCancelOrSupersedeChapterNavigation() = runTest { use { reader, _, _, _, _ ->
        reader.chapter(2); reader.presentationChanged(EpubReaderSettings(fontSize = 22)); advanceUntilIdle()
        assertEquals(2, assertIs<EpubReaderState.Ready>(reader.state.value).spineIndex)
        assertEquals(22, reader.settings.value.fontSize)
    } }
    @Test fun backAfterSettingsFlushesAndClosesDocument() = runTest { use { reader, doc, _, store, _ ->
        reader.report(assertIs<EpubReaderState.Ready>(reader.state.value).ticket, 1, 3)
        reader.presentationChanged(EpubReaderSettings(margin = 40)); reader.close(); reader.close(); runCurrent()
        assertEquals(1, doc.closes); assertEquals(3L, (store.values[id]?.locator as ReadingLocator.Epub).codePointOffset)
    } }
    @Test fun settingsOnClosedReaderAreIgnored() = runTest { use { reader, _, _, _, _ ->
        reader.close(); reader.presentationChanged(EpubReaderSettings(fontSize = 30)); assertEquals(18, reader.settings.value.fontSize)
    } }
    @Test fun settingsHaveFiniteValidatedBounds() {
        for (action in listOf({ EpubReaderSettings(fontSize = 13) }, { EpubReaderSettings(fontSize = 31) },
            { EpubReaderSettings(lineSpacingPercent = 119) }, { EpubReaderSettings(lineSpacingPercent = 201) },
            { EpubReaderSettings(margin = 7) }, { EpubReaderSettings(margin = 41) })) assertFailsWith<IllegalArgumentException> { action() }
    }

    @Test fun staleVisibleMediaCallbackCannotAttachOldChapterImages() = runTest { use { reader, _, _, _, _ ->
        val old = assertIs<EpubReaderState.Ready>(reader.state.value).ticket
        reader.presentationChanged(EpubReaderSettings(fontSize = 24))
        reader.visibleMedia(old, listOf(EpubImage(EpubEntryPath("OPS/missing.png"), "image/png", "Alt")))
        runCurrent(); assertTrue(reader.media.state.value.isEmpty())
        reader.close(); reader.visibleMedia(old, emptyList()); assertEquals(0, reader.media.retained)
    } }

}
