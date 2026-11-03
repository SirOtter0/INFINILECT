// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.*
import org.infinilect.app.diagnostics.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeDiagnosticEventsTest {
    @Test fun departureRecordsTechnicalCancellationWithoutBlockingNavigationOrResumingOldGeneration() = runTest {
        val source = CatalogFixture()
        val gates = mutableListOf<CompletableDeferred<DiscoveryPage>>()
        source.respond = { _, _ ->
            val gate = CompletableDeferred<DiscoveryPage>(); gates += gate
            withContext(NonCancellable) { gate.await() }
        }
        val events = mutableListOf<Pair<TechnicalEvent, Long>>()
        val owner = ApplicationSources(listOf(SourceOption("Original fixture", source)), sessionDispatcher = StandardTestDispatcher(testScheduler)) {}
        owner.technicalDiagnostics = TechnicalDiagnostics { event, generation, _ -> events += event to generation }
        val app = owner.applicationSession()
        try {
            app.homeDiscovery.refresh(DiscoveryPreferences(homeEnabled = true, showCatalogOnlySources = true)); runCurrent()
            val firstGeneration = events.first { it.first == TechnicalEvent.HOME_LOADING }.second
            app.navigate(Destination.LIBRARY); runCurrent()
            assertEquals(Destination.LIBRARY, app.destination.value); assertFalse(app.homeDiscovery.state.value.loading)
            assertTrue(events.any { it.first == TechnicalEvent.HOME_PAUSED && it.second == firstGeneration })
            assertTrue(events.any { it.first == TechnicalEvent.REQUEST_CANCELLED && it.second == firstGeneration })
            assertTrue(events.any { it.first == TechnicalEvent.NAV_LIBRARY })
            val abandoned = app.homeDiscovery.state.value
            gates.toList().forEach { it.complete(DiscoveryPage(listOf(source.entry("stale")))) }; runCurrent()
            assertEquals(abandoned, app.homeDiscovery.state.value)
        } finally { owner.close(); gates.forEach { it.complete(DiscoveryPage(emptyList())) }; runCurrent() }
    }
    @Test fun failuresAndCompletedBatchUseClosedVocabularyAndRetainRetryBehavior() = runTest {
        val source = CatalogFixture(); var fails = true
        source.respond = { _, _ -> if (fails) error("Private metadata must never enter the sink") else DiscoveryPage(listOf(source.entry("original"))) }
        val events = mutableListOf<TechnicalEvent>()
        val catalog = DiscoveryCatalog(listOf(source), backgroundScope) { 0 }
        val home = HomeDiscovery(listOf(SourceOption("Original fixture", source)), catalog, backgroundScope,
            TechnicalDiagnostics { event, _, _ -> events += event }) { 0 }
        try {
            home.refresh(DiscoveryPreferences(homeEnabled = true, showCatalogOnlySources = true)); runCurrent()
            assertTrue(home.state.value.failed); assertFalse(home.state.value.loading)
            assertTrue(TechnicalEvent.REQUEST_FAILED in events); assertTrue(TechnicalEvent.HOME_COMPLETE in events)
            fails = false; home.refresh(DiscoveryPreferences(homeEnabled = true, showCatalogOnlySources = true), retry = true); runCurrent()
            assertFalse(home.state.value.failed); assertFalse(home.state.value.loading)
            assertTrue(TechnicalEvent.REQUEST_COMPLETE in events); assertTrue(home.state.value.rows.isNotEmpty())
        } finally { home.close(); catalog.close(); catalog.awaitClosed() }
    }
}
