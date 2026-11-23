// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.SourceOption
import org.infinilect.core.*
import kotlin.test.*

class AutomaticReadabilityTest {
    private class Source(private val raw: CatalogFixture = CatalogFixture(SourceId("wiki-test"))) :
        PublicationSource by raw, ReadabilityCatalogSource {
        val books = listOf("About A Venus", "A Venus", "Another A Venus").mapIndexed { index, title ->
            raw.entry(index.toString()).let {
                it.copy(publication = it.publication.copy(title = title), readability = CatalogReadability.READABLE_CANDIDATE)
            }
        }
        var pages = 0
        var discoveryDelay: Long = 0
        val checks = mutableListOf<String>()
        val confirmed = mutableSetOf<PublicationId>()
        var check: suspend (Publication) -> CatalogReadability = { CatalogReadability.READABLE_CONFIRMED }
        override suspend fun discover(request: DiscoveryRequest, token: String?) = discoverReadable(request, token)
        override suspend fun discoverReadable(request: DiscoveryRequest, token: String?): DiscoveryPage {
            pages++
            delay(discoveryDelay)
            if (request.query == "empty") return DiscoveryPage(emptyList())
            val entries = books.map { entry -> entry.copy(readability = cachedReadability(entry.publication)) }
            return if (request.readabilityMode == ReadabilityMode.ALL) DiscoveryPage(entries)
            else DiscoveryPage(entries.filter { it.readability == CatalogReadability.READABLE_CONFIRMED },
                candidates = entries.filter { it.readability == CatalogReadability.READABLE_CANDIDATE })
        }
        override suspend fun cachedReadability(publication: Publication) =
            if (publication.id in confirmed) CatalogReadability.READABLE_CONFIRMED else CatalogReadability.READABLE_CANDIDATE
        override suspend fun verifyReadable(publication: Publication): CatalogReadability {
            checks += publication.title
            val result = check(publication)
            currentCoroutineContext().ensureActive()
            if (result == CatalogReadability.READABLE_CONFIRMED) confirmed += publication.id
            return result
        }
    }

    @Test fun singleColdSubmissionPromotesAnEpubThatFinishesAfterSlowCatalogDiscovery() = runTest {
        for (discoveryDelay in listOf(0L, 15_000L)) {
            val source = Source().apply { this.discoveryDelay = discoveryDelay }
            val release = CompletableDeferred<Unit>()
            source.check = { if (it.title == "A Venus") release.await(); CatalogReadability.READABLE_CONFIRMED }
            val cache = DiscoveryCatalog(listOf(source), backgroundScope) { testScheduler.currentTime }
            val controller = DiscoveryController(listOf(SourceOption("Wiki", source, epubReadingEnabled = true)), cache, backgroundScope)
            try {
                controller.edit("A Venus")
                controller.submit()
                advanceTimeBy(discoveryDelay)
                runCurrent()
                assertEquals(listOf("A Venus"), source.checks)
                assertTrue(controller.state.value.entries.isEmpty())
                assertTrue(controller.state.value.catalogs.single().candidates.any { it.publication.title == "A Venus" })
                advanceTimeBy(4_000)
                release.complete(Unit)
                runCurrent()
                assertTrue(controller.state.value.entries.any {
                    it.publication.title == "A Venus" && it.readability == CatalogReadability.READABLE_CONFIRMED
                }, "EPUB confirmation must be visible after a catalog delay of ${discoveryDelay} ms")
                assertFalse(controller.state.value.catalogs.single().candidates.any { it.publication.title == "A Venus" })
                assertEquals(1, source.pages)
                assertEquals(1, source.checks.count { it == "A Venus" })
            } finally {
                release.complete(Unit)
                controller.close()
                cache.close()
            }
        }
    }

    @Test fun explicitColdSearchRanksExactTitleAndPublishesBeforeTheNextCheckFinishes() = runTest {
        val source = Source()
        val release = CompletableDeferred<Unit>()
        source.check = { if (it.title != "A Venus") release.await(); CatalogReadability.READABLE_CONFIRMED }
        val cache = DiscoveryCatalog(listOf(source), backgroundScope) { testScheduler.currentTime }
        val controller = DiscoveryController(listOf(SourceOption("Wiki", source, epubReadingEnabled = true)), cache, backgroundScope)
        try {
            controller.edit("A Venus")
            advanceTimeBy(351)
            runCurrent()
            assertEquals(emptyList(), source.checks)
            assertEquals(DiscoveryStatus.PENDING, controller.state.value.resultStatus())
            controller.submit()
            runCurrent()
            assertEquals(listOf("A Venus", "About A Venus"), source.checks)
            assertEquals(listOf("A Venus"), controller.state.value.entries.map { it.publication.title })
            assertEquals(DiscoveryStatus.CHECKING, controller.state.value.resultStatus())
            release.complete(Unit)
            runCurrent()
            assertEquals(2, controller.state.value.entries.size)
            assertEquals("Another A Venus", controller.state.value.catalogs.single().candidates.single().publication.title)
            controller.submit()
            runCurrent()
            assertEquals(2, source.checks.size)
        } finally {
            controller.close()
            cache.close()
        }
    }

    @Test fun changingQueryModeOrScreenCancelsChecksAndRejectsLateResults() = runTest {
        for (interrupt in listOf<(DiscoveryController) -> Unit>(
            { it.edit("empty") }, { it.readabilityMode(ReadabilityMode.ALL) }, { it.pause() },
        )) {
            val source = Source()
            val release = CompletableDeferred<Unit>()
            source.check = {
                withContext(NonCancellable) { release.await() }
                CatalogReadability.READABLE_CONFIRMED
            }
            val cache = DiscoveryCatalog(listOf(source), backgroundScope) { testScheduler.currentTime }
            val controller = DiscoveryController(listOf(SourceOption("Wiki", source, epubReadingEnabled = true)), cache, backgroundScope)
            try {
                controller.edit("A Venus")
                controller.submit()
                runCurrent()
                assertEquals(listOf("A Venus"), source.checks)
                interrupt(controller)
                release.complete(Unit)
                advanceTimeBy(351)
                runCurrent()
                assertFalse(controller.state.value.checkingReadability)
                assertTrue(controller.state.value.entries.none { it.readability == CatalogReadability.READABLE_CONFIRMED })
                assertEquals(1, source.checks.size)
            } finally {
                release.complete(Unit)
                controller.close()
                cache.close()
            }
        }
    }

    @Test fun timeoutOffersAnEpubOnlyRetryAndPreservesTheOtherResults() = runTest {
        val source = Source()
        source.check = { if (it.title == "A Venus") awaitCancellation(); CatalogReadability.READABLE_CONFIRMED }
        val cache = DiscoveryCatalog(listOf(source), backgroundScope) { testScheduler.currentTime }
        val controller = DiscoveryController(listOf(SourceOption("Wiki", source, epubReadingEnabled = true)), cache, backgroundScope)
        val venus = source.books.single { it.publication.title == "A Venus" }.publication.id
        try {
            controller.edit("A Venus")
            controller.submit()
            runCurrent()
            advanceTimeBy(8_000)
            runCurrent()
            val pending = controller.state.value.catalogs.single().candidates.single { it.publication.id == venus }
            assertEquals(CatalogReadability.TEMPORARY_ERROR, pending.readability)
            assertEquals(CatalogErrorKind.TIMEOUT, pending.readabilityFailure)
            assertEquals(listOf("About A Venus"), controller.state.value.entries.map { it.publication.title })
            source.check = { CatalogReadability.READABLE_CONFIRMED }
            controller.retryReadability(venus)
            controller.retryReadability(venus)
            runCurrent()
            assertEquals(listOf("A Venus", "About A Venus", "A Venus"), source.checks)
            assertEquals(setOf("A Venus", "About A Venus"), controller.state.value.entries.map { it.publication.title }.toSet())
            assertEquals(1, source.pages)
        } finally {
            controller.close()
            cache.close()
        }
    }

    @Test fun confirmationRecordedBeforeTimeoutIsRecoveredWithoutAnotherExport() = runTest {
        val source = Source()
        source.check = {
            source.confirmed += it.id
            awaitCancellation()
        }
        val cache = DiscoveryCatalog(listOf(source), backgroundScope) { testScheduler.currentTime }
        try {
            val venus = source.books.single { it.publication.title == "A Venus" }.publication
            val result = async { cache.verifyReadable(venus) }
            runCurrent()
            advanceTimeBy(8_000)
            assertEquals(CatalogReadability.READABLE_CONFIRMED, result.await())
            assertEquals(CatalogReadability.READABLE_CONFIRMED, cache.verifyReadable(venus))
            assertEquals(listOf("A Venus"), source.checks)
        } finally {
            cache.close()
        }
    }

    @Test fun retryingAFailedProviderKeepsConfirmedWikiResultsAndTheSameGeneration() = runTest {
        val source = Source()
        val failed = CatalogFixture(SourceId("retry-other"))
        failed.respond = { _, _ -> error("offline") }
        val cache = DiscoveryCatalog(listOf(source, failed), backgroundScope) { testScheduler.currentTime }
        val controller = DiscoveryController(listOf(SourceOption("Wiki", source, epubReadingEnabled = true),
            SourceOption("Other", failed, textReadingEnabled = true)), cache, backgroundScope)
        try {
            controller.edit("A Venus")
            controller.submit()
            runCurrent()
            val before = controller.state.value
            assertTrue(before.catalogs.single { it.id == failed.id }.failed)
            assertTrue(before.entries.any { it.publication.title == "A Venus" })
            failed.respond = { _, _ -> DiscoveryPage(listOf(failed.entry("restored"))) }
            controller.submit()
            runCurrent()
            assertEquals(before.generation, controller.state.value.generation)
            assertEquals(before.entries, controller.state.value.entries.filter { it.publication.id.sourceId == source.id })
            assertEquals(1, source.pages)
            assertEquals(2, failed.calls)
        } finally {
            controller.close()
            cache.close()
        }
    }

    @Test fun sessionGateSerializesChecksAndCapsNewAttemptsAcrossSearches() = runTest {
        val source = Source()
        val release = CompletableDeferred<Unit>()
        source.check = { release.await(); CatalogReadability.READABLE_CANDIDATE }
        val cache = DiscoveryCatalog(listOf(source), backgroundScope) { testScheduler.currentTime }
        try {
            val checks = List(6) { async { cache.verifyReadable(source.books.first().publication) } }
            runCurrent()
            assertEquals(1, source.checks.size)
            release.complete(Unit)
            checks.awaitAll()
            assertEquals(4, source.checks.size)
            advanceTimeBy(60_001)
            cache.verifyReadable(source.books.first().publication)
            assertEquals(5, source.checks.size)
        } finally {
            release.complete(Unit)
            cache.close()
        }
    }
}
