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
        val checks = mutableListOf<String>()
        val confirmed = mutableSetOf<PublicationId>()
        var check: suspend (Publication) -> CatalogReadability = { CatalogReadability.READABLE_CONFIRMED }
        override suspend fun discover(request: DiscoveryRequest, token: String?) = discoverReadable(request, token)
        override suspend fun discoverReadable(request: DiscoveryRequest, token: String?): DiscoveryPage {
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
