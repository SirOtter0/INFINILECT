// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*

/** Deterministic providers validate shared logic, not native translation quality. */
@OptIn(ExperimentalCoroutinesApi::class)
class EpubTranslationTest {
    private val pair = TranslationPair("en", "es")
    private val model = TranslationModel(pair, "test-1", 10)
    private val path = EpubEntryPath("OPS/chapter.xhtml")
    private fun chapter(vararg runs: EpubRun, kind: EpubBlockKind = EpubBlockKind.PARAGRAPH) = EpubChapter(path,
        listOf(EpubBlock(listOf(0, 1), 0, kind, runs.toList(), 0)), emptyMap())
    private inner class Engine : EpubTranslationEngine {
        override val identity = "deterministic-test-v1"
        override val unavailable: String? = null
        override val models = listOf(model, model.copy(pair = TranslationPair("es", "en"), revision = "test-2"))
        var ready = true; var installs = 0; var installFailures = 0; var calls = 0; var closes = 0; var active = 0; var peak = 0
        var operation: suspend (String) -> String = { "[$it]" }
        override suspend fun installed(model: TranslationModel) = ready
        override suspend fun install(model: TranslationModel, progress: (Long, Long) -> Unit) { installs++; if (installFailures-- > 0) throw EpubTranslationException("Model download failed"); progress(5, 10); ready = true }
        override suspend fun remove(model: TranslationModel) { ready = false }
        override suspend fun translate(model: TranslationModel, text: String): String {
            calls++; active++; peak = maxOf(peak, active)
            return try { operation(text) } finally { active-- }
        }
        override fun close() { closes++ }
    }
    private class Cache : EpubTranslationCache {
        val values = mutableMapOf<List<String>, String>()
        override suspend fun get(key: List<String>) = values[key]
        override suspend fun put(key: List<String>, value: String) { values[key] = value }
        override suspend fun clear() { values.clear() }
    }
    @Test fun selectionUsesExactUnicodeTextWithoutInstallingModels() = runTest {
        val engine = Engine(); val controller = EpubTranslationController(EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)), this, listOf("book", "sha"), "en") {}
        controller.selection("Hello 🌍"); runCurrent()
        assertEquals("[Hello 🌍]", controller.state.value.selectionResult)
        assertEquals("Hello 🌍", controller.state.value.selection)
        assertEquals(0, engine.installs)
        controller.dismissSelection(); assertNull(controller.state.value.selection); controller.close()
    }
    @Test fun preservesStylesOwnedLinksImagesAndSourceOffsets() = runTest {
        val target = EpubTarget(path, "note")
        val source = chapter(EpubRun("Bold", strong = true), EpubRun(" italic ", emphasis = true), EpubRun("note", target = target), EpubRun("[Image alt]", translatable = false))
        val engine = Engine(); val projected = EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)).passage(model, listOf("book"), source).single()
        assertSame(source.blocks.single(), projected.source)
        assertTrue(projected.runs[0].strong); assertTrue(projected.runs[1].emphasis); assertEquals(target, projected.runs[2].target)
        assertEquals("[Image alt]", projected.runs[3].text)
        assertEquals("Bold italic note[Image alt]", source.blocks.single().text)
        assertEquals(3, engine.calls)
    }
    @Test fun skipsPreformattedCodeWhitespaceAndLiteralUrls() = runTest {
        val engine = Engine(); val service = EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler))
        val code = chapter(EpubRun("println(hello)"), kind = EpubBlockKind.PREFORMATTED)
        assertEquals(code.blocks.single().runs, service.passage(model, null, code).single().runs)
        val text = chapter(EpubRun("code", translatable = false), EpubRun("https://example.org/a"), EpubRun(" \n "))
        assertEquals(text.blocks.single().runs, service.passage(model, null, text).single().runs)
        assertEquals(0, engine.calls)
    }
    @Test fun sourceToDisplayMappingRespectsRunsAndUnicodeEndpoints() {
        val source = chapter(EpubRun("🌍Hi", strong = true), EpubRun(" world", emphasis = true)).blocks.single()
        val result = EpubTranslatedBlock(source, listOf(source.runs[0].copy(text = "🌍Hola"), source.runs[1].copy(text = " mundo")))
        assertEquals(0, result.sourceToDisplay(0)); assertEquals(5, result.sourceToDisplay(3))
        assertEquals(source.codePoints, result.displayToSource(result.text.epubCodePoints()))
        assertEquals(3, result.displayToSource(5))
    }
    @Test fun segmentBoundaries2047_2048_2049AndLargeRunNeverDropCharacters() = runTest {
        for (size in listOf(2047, 2048, 2049, 8192)) {
            val engine = Engine(); engine.operation = { it }
            val input = "🌍".repeat(size)
            assertEquals(input, EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)).text(model, null, input))
            assertEquals((size + 2047) / 2048, engine.calls)
        }
    }
    @Test fun cacheReuseSeparatesPublicationRevisionLanguageAndModel() = runTest {
        val cache = Cache(); val engine = Engine(); val service = EpubTranslationService(engine, cache, modelDispatcher = StandardTestDispatcher(testScheduler))
        val identity = listOf("source/book", "resource", "sha1")
        repeat(2) { assertEquals("[text]", service.text(model, identity, "text")) }
        assertEquals(1, engine.calls)
        service.text(model, identity.dropLast(1) + "sha2", "text")
        service.text(model, listOf("different/book", "resource", "sha1"), "text")
        service.text(model.copy(revision = "new"), identity, "text")
        service.text(model.copy(pair = TranslationPair("es", "en")), identity, "text")
        assertEquals(5, engine.calls)
        service.clear(); service.text(model, identity, "text"); assertEquals(6, engine.calls)
    }
    @Test fun unknownByteRevisionCannotPersistentlyReuseResults() = runTest {
        val cache = Cache(); val engine = Engine(); val service = EpubTranslationService(engine, cache, modelDispatcher = StandardTestDispatcher(testScheduler))
        repeat(2) { service.text(model, null, "text") }
        assertEquals(2, engine.calls); assertTrue(cache.values.isEmpty())
    }
    @Test fun providerErrorsAndOversizedOutputRemainControlled() = runTest {
        val engine = Engine(); val service = EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler))
        engine.operation = { error("failure") }
        assertFailsWith<IllegalStateException> { service.text(model, null, "text") }
        engine.operation = { "x".repeat(EpubTranslationPolicy.OUTPUT_UNITS + 1) }
        assertFailsWith<IllegalArgumentException> { service.text(model, null, "text") }
    }
    @Test fun explicitDownloadRemovalAndRetryWithoutAutomaticInstallation() = runTest {
        val engine = Engine(); engine.ready = false
        val controller = EpubTranslationController(EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)), this, null, "en") {}
        controller.chapter(chapter(EpubRun("Hello"))); controller.reading(true); runCurrent()
        assertNull(controller.state.value.blocks); assertNotNull(controller.state.value.message); assertEquals(0, engine.installs)
        controller.install(); runCurrent(); assertEquals(1, engine.installs); assertTrue(controller.state.value.installed)
        controller.reading(true); runCurrent(); assertEquals("[Hello]", controller.state.value.blocks!!.single().text)
        controller.remove(); runCurrent(); assertFalse(controller.state.value.translated); assertFalse(controller.state.value.installed)
        controller.close()
    }
    @Test fun cancelledNoncooperativeNativeWorkCannotPublishOrOverlapNewIntent() = runTest {
        val gate = CompletableDeferred<Unit>(); val engine = Engine()
        engine.operation = { withContext(NonCancellable) { gate.await() }; "[$it]" }
        val cache = Cache(); val service = EpubTranslationService(engine, cache, modelDispatcher = StandardTestDispatcher(testScheduler))
        val controller = EpubTranslationController(service, this, listOf("book", "sha"), "en") {}
        controller.chapter(chapter(EpubRun("old"))); controller.reading(true); runCurrent()
        repeat(20) { controller.chapter(chapter(EpubRun("new $it"))); runCurrent() }
        assertEquals(1, engine.calls); assertEquals(1, engine.active)
        gate.complete(Unit); runCurrent()
        assertEquals("[new 19]", controller.state.value.blocks!!.single().text)
        assertEquals(2, engine.calls); assertEquals(1, engine.peak)
        assertFalse(cache.values.values.contains("[old]")); controller.close()
    }
    @Test fun switchingOriginalOrLanguageRetiresPreviousProjection() = runTest {
        val gate = CompletableDeferred<Unit>(); val engine = Engine()
        engine.operation = { withContext(NonCancellable) { gate.await() }; "[$it]" }
        val controller = EpubTranslationController(EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)), this, null, "en") {}
        controller.chapter(chapter(EpubRun("old"))); controller.reading(true); runCurrent()
        controller.model(engine.models.last()); gate.complete(Unit); runCurrent()
        assertFalse(controller.state.value.translated); assertNull(controller.state.value.blocks)
        assertEquals("es", controller.state.value.model!!.pair.source); controller.close()
    }
    @Test fun closeRejectsStaleCallbacksAndReleasesOnlyApplicationOwnedEngine() = runTest {
        val gate = CompletableDeferred<Unit>(); val engine = Engine()
        engine.operation = { withContext(NonCancellable) { gate.await() }; it }
        val service = EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler))
        val controller = EpubTranslationController(service, this, null, "en") {}
        controller.selection("old"); runCurrent(); controller.close(); service.close(); gate.complete(Unit); runCurrent()
        assertNull(controller.state.value.selectionResult); assertEquals(1, engine.closes)
        assertFailsWith<IllegalStateException> { service.text(model, null, "new") }
    }
    @Test fun unsupportedMetadataRequiresAnExplicitSupportedPair() = runTest {
        val engine = Engine(); val controller = EpubTranslationController(EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)), this, null, "fr") {}
        assertNull(controller.state.value.model)
        controller.model(model); runCurrent(); assertEquals(model, controller.state.value.model); controller.close()
        assertFailsWith<IllegalArgumentException> { TranslationPair("fr", "es") }
    }
    @Test fun newWindowReleasesOldProjectionAndFailedReplacementDoesNotPublishPartialText() = runTest {
        val engine = Engine(); val controller = EpubTranslationController(EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)), this, null, "en") {}
        controller.chapter(chapter(EpubRun("one"))); controller.reading(true); runCurrent()
        val previous = controller.state.value.blocks!!
        engine.operation = { error("failure") }
        controller.chapter(chapter(EpubRun("two"))); runCurrent()
        assertNull(controller.state.value.blocks); assertNotNull(controller.state.value.message)
        assertEquals("[one]", previous.single().text); controller.original(); controller.close()
    }
    @Test fun cacheClearDoesNotChangeModeAndNavigationDuringClearResumesLatestPassage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cache = object : EpubTranslationCache {
            override suspend fun get(key: List<String>): String? = null
            override suspend fun put(key: List<String>, value: String) = Unit
            override suspend fun clear() { gate.await() }
        }
        val controller = EpubTranslationController(EpubTranslationService(Engine(), cache), this, listOf("book"), "en") {}
        controller.chapter(chapter(EpubRun("old"))); controller.reading(true); runCurrent()
        controller.clear(); runCurrent(); controller.chapter(chapter(EpubRun("latest"))); runCurrent()
        assertTrue(controller.state.value.translated); assertTrue(controller.state.value.busy)
        gate.complete(Unit); runCurrent()
        assertEquals("[latest]", controller.state.value.blocks!!.single().text)
        assertTrue(controller.state.value.translated); controller.close()
    }
    @Test fun sourcePresentationAndPerRequestLimitsAreEnforcedBeforeInference() = runTest {
        val engine = Engine(); val service = EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalArgumentException> { service.text(model, null, "x".repeat(8193)) }
        val block = chapter(EpubRun("text")).blocks.single()
        val huge = EpubChapter(path, List(257) { block }, emptyMap())
        assertFailsWith<IllegalArgumentException> { service.passage(model, null, huge) }
        assertEquals(0, engine.calls)
    }

    @Test fun staleSelectionActionCannotBorrowTextFromAnObsoleteChapter() = runTest {
        val engine = Engine(); val controller = EpubTranslationController(EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)), this, null, "en") {}
        val old = chapter(EpubRun("old")); val latest = chapter(EpubRun("latest"))
        controller.chapter(old); controller.chapter(latest)
        controller.selection("old", old); runCurrent()
        assertNull(controller.state.value.selection); assertEquals(0, engine.calls)
        controller.selection("latest", latest); runCurrent()
        assertEquals("[latest]", controller.state.value.selectionResult); controller.close()
    }

    @Test fun retryRepeatsExplicitFailedDownloadWithoutAutomaticInstallation() = runTest {
        val engine = Engine(); engine.ready = false; engine.installFailures = 1
        val controller = EpubTranslationController(EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)), this, null, "en") {}
        controller.refresh(); runCurrent(); assertEquals(0, engine.installs)
        controller.install(); runCurrent(); assertEquals(1, engine.installs); assertNotNull(controller.state.value.message)
        controller.retry(); runCurrent(); assertEquals(2, engine.installs); assertTrue(controller.state.value.installed)
        controller.close()
    }
    @Test fun segmentationPrefersWordBoundariesAndPreservesAllOriginalWhitespace() = runTest {
        val received = mutableListOf<String>(); val engine = Engine()
        engine.operation = { received.add(it); it }
        val input = "word ".repeat(1025)
        assertEquals(input, EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)).text(model, null, input))
        assertTrue(received.all { it.startsWith("word") && it.endsWith("word") })
        assertTrue(received.all { it.epubCodePoints() <= 2048 })
    }
    @Test fun metadataAliasesAreRealSuggestionsAndSameLanguageDoesNotSelectAnotherTarget() = runTest {
        assertEquals("en", epubTranslationLanguage("eng")); assertEquals("es", epubTranslationLanguage("es-MX"))
        assertNull(epubTranslationLanguage("fr"))
        val controller = EpubTranslationController(EpubTranslationService(Engine()), this, null, "es") {}
        assertNull(controller.state.value.model) // default Spanish target already matches this book
        controller.close()
    }

    @Test fun oversizedSelectionShowsABoundedErrorPanelAndNeverInvokesProvider() = runTest {
        val engine = Engine(); val controller = EpubTranslationController(EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler)), this, null, "en") {}
        controller.selection("x".repeat(2049)); runCurrent()
        assertEquals("", controller.state.value.selection); assertNotNull(controller.state.value.message)
        assertEquals(0, engine.calls); controller.dismissSelection(); controller.close()
    }

}
