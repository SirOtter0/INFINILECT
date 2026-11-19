// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.SourceOption
import org.infinilect.core.*
import kotlin.test.*

class ReadabilityModeTest {
    @Test fun switchPreservesOtherProvidersAndDiscardsObsoleteStrictResults() = runTest {
        val regular = CatalogFixture()
        val raw = CatalogFixture(SourceId("readability-fixture"))
        val late = CompletableDeferred<Unit>()
        val wiki = object : PublicationSource by raw, ReadabilityCatalogSource {
            override suspend fun discover(request: DiscoveryRequest, token: String?) = raw.discover(request, token)
            override suspend fun cachedReadability(publication: Publication) = CatalogReadability.UNVERIFIED
            override suspend fun discoverReadable(request: DiscoveryRequest, token: String?): DiscoveryPage {
                if (request.readabilityMode == ReadabilityMode.READABLE) {
                    withContext(NonCancellable) { late.await() }
                    return DiscoveryPage(listOf(raw.entry("obsolete")))
                }
                return DiscoveryPage(listOf(raw.entry("all")))
            }
        }
        val cache = DiscoveryCatalog(listOf(wiki, regular), backgroundScope) { testScheduler.currentTime }
        val controller = DiscoveryController(listOf(SourceOption("Wiki", wiki, epubReadingEnabled = true),
            SourceOption("Regular", regular, textReadingEnabled = true)), cache, backgroundScope)
        try {
            assertEquals(ReadabilityMode.READABLE, controller.state.value.readabilityMode)
            controller.edit("book")
            advanceTimeBy(350)
            runCurrent()
            controller.readabilityMode(ReadabilityMode.ALL)
            runCurrent()
            late.complete(Unit)
            runCurrent()
            assertEquals("book", controller.state.value.query)
            assertEquals(listOf("all", "1"), controller.state.value.entries.map { it.publication.id.localId })
            assertEquals(1, regular.calls)
            controller.pause()
            controller.resume()
            assertEquals(ReadabilityMode.ALL, controller.state.value.readabilityMode)
        } finally {
            late.complete(Unit)
            controller.close()
            cache.close()
        }
    }
    @Test fun emptyStatusDistinguishesCandidatesBudgetAndExhaustedCatalog() {
        val source = CatalogFixture()
        val initial = DiscoveryState(request = DiscoveryRequest("A"))
        fun status(row: CatalogResults) = initial.copy(catalogs = listOf(row)).resultStatus()
        val row = CatalogResults(source.id)
        assertEquals(DiscoveryStatus.NO_MATCHES, status(row))
        assertEquals(DiscoveryStatus.UNCONFIRMED, status(row.copy(hadCatalogMatches = true)))
        assertEquals(DiscoveryStatus.LIMITED, status(row.copy(nextToken = "next", assessmentLimited = true)))
        assertEquals(DiscoveryStatus.PARTIAL_ERROR, status(row.copy(assessmentFailed = true)))
        assertEquals(DiscoveryStatus.SEARCHING, status(row.copy(loading = true)))
        assertEquals(DiscoveryStatus.PENDING, status(row.copy(nextToken = "next", assessmentLimited = true,
            candidates = listOf(source.entry("authorized")))))
    }

}
