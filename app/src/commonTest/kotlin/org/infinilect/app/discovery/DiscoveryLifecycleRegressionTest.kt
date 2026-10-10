// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.*
import kotlin.test.*

class DiscoveryLifecycleRegressionTest {
    @Test fun transportCancellationDoesNotLeaveSearchOrHomeLoadingForever() = runTest {
        val source = CatalogFixture()
        source.respond = { _, _ -> throw CancellationException("Transport cancelled independently") }
        val catalog = DiscoveryCatalog(listOf(source), backgroundScope) { 0 }
        val search = DiscoveryController(listOf(SourceOption("Catalog", source)), catalog, backgroundScope)
        val home = HomeDiscovery(listOf(SourceOption("Catalog", source)), catalog, backgroundScope) { 0 }
        try {
            search.edit("Cervantes"); advanceTimeBy(350); runCurrent()
            assertFalse(search.state.value.loading)
            assertTrue(search.state.value.catalogs.single().failed)
            home.refresh(DiscoveryPreferences(homeEnabled = true)); runCurrent()
            assertFalse(home.state.value.loading)
            assertTrue(home.state.value.failed)
        } finally { search.close(); home.close(); catalog.close() }
    }

    @Test fun resultsAreMaterializedOncePerStateRatherThanOnEveryScrollRead() {
        val source = CatalogFixture()
        val state = DiscoveryState(catalogs = listOf(CatalogResults(source.id, List(100) { source.entry("$it") })))
        val entries = state.entries
        repeat(1000) { assertSame(entries, state.entries) }
    }

    @Test fun returningToSearchResumesInterruptedPaginationWithoutReplacingPriorResults() = runTest {
        val source = CatalogFixture()
        val gate = CompletableDeferred<DiscoveryPage>()
        source.respond = { _, token -> if (token == null) DiscoveryPage(listOf(source.entry("first")), "second") else gate.await() }
        val owner = ApplicationSources(listOf(SourceOption("Catalog", source))) {}
        val app = ApplicationSession(owner, backgroundScope, StandardTestDispatcher(testScheduler)) { 0 }
        try {
            app.navigate(Destination.SEARCH); app.discovery.edit("Cervantes"); advanceTimeBy(350); runCurrent()
            app.discovery.more(source.id); runCurrent()
            app.navigate(Destination.HOME); runCurrent()
            gate.complete(DiscoveryPage(listOf(source.entry("second")))); runCurrent()
            app.navigate(Destination.SEARCH); runCurrent()
            assertEquals(listOf("first", "second"), app.discovery.state.value.entries.map { it.publication.id.localId })
            assertFalse(app.discovery.state.value.loading)
            assertEquals("Cervantes", app.discovery.state.value.query)
        } finally { app.close(); owner.close() }
    }

    @Test fun repeatedHomeSearchNavigationKeepsCompletedResultsWithoutNewProviderRequests() = runTest {
        val source = CatalogFixture()
        val owner = ApplicationSources(listOf(SourceOption("Catalog", source))) {}
        val app = ApplicationSession(owner, backgroundScope, StandardTestDispatcher(testScheduler)) { 0 }
        try {
            app.navigate(Destination.SEARCH); app.discovery.edit("Cervantes"); advanceTimeBy(350); runCurrent()
            val entries = app.discovery.state.value.entries
            repeat(100) {
                app.navigate(Destination.HOME); runCurrent()
                app.navigate(Destination.SEARCH); runCurrent()
                assertEquals(entries, app.discovery.state.value.entries)
            }
            assertEquals(1, source.calls); assertEquals(0, source.acquisition)
        } finally { app.close(); owner.close() }
    }

    @Test fun backgroundForegroundResumesOnlyInterruptedWorkWithoutRetiringApplicationSession() = runTest {
        val source = CatalogFixture()
        val gates = mutableListOf<CompletableDeferred<DiscoveryPage>>()
        source.respond = { _, _ -> CompletableDeferred<DiscoveryPage>().also { gates += it }.await() }
        val owner = ApplicationSources(listOf(SourceOption("Catalog", source)), sessionDispatcher = StandardTestDispatcher(testScheduler)) {}
        val app = owner.applicationSession()
        try {
            app.navigate(Destination.SEARCH); app.discovery.edit("Cervantes"); advanceTimeBy(350); runCurrent()
            repeat(20) {
                owner.pauseDiscovery(); runCurrent()
                assertFalse(app.discovery.state.value.loading)
                owner.resumeDiscovery(); runCurrent()
                assertTrue(app.discovery.state.value.loading)
            }
            gates.last().complete(DiscoveryPage(listOf(source.entry("current")))); runCurrent()
            gates.dropLast(1).forEach { it.complete(DiscoveryPage(listOf(source.entry("obsolete")))) }; runCurrent()
            assertEquals("current",app.discovery.state.value.entries.single().publication.id.localId)
            val calls=source.calls
            owner.pauseDiscovery(); owner.resumeDiscovery(); runCurrent(); assertEquals(calls,source.calls)
            assertSame(app,owner.applicationSession())
        } finally { owner.close() }
    }

    @Test fun clearAndResetRetainOtherFiltersUntilExplicitReset()=runTest {
        val source=CatalogFixture();val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val controller=DiscoveryController(listOf(SourceOption("Catalog",source)),catalog,backgroundScope)
        try {
            controller.edit("Cervantes");controller.genre(Genre.ADVENTURE);controller.language("es");runCurrent()
            controller.edit("");runCurrent()
            assertEquals(Genre.ADVENTURE,controller.state.value.genre);assertEquals("es",controller.state.value.language)
            assertTrue(controller.state.value.entries.isEmpty())
            advanceTimeBy(350);runCurrent();assertEquals("",controller.state.value.request?.query)
            controller.edit("new");controller.resetFilters();runCurrent()
            assertEquals("new",controller.state.value.query);assertNull(controller.state.value.genre);assertNull(controller.state.value.language)
        } finally {controller.close();catalog.close()}
    }

    @Test fun emptyFilteredFirstPageKeepsPaginationInsteadOfClaimingGenreIsUnavailable()=runTest {
        val source=CatalogFixture();source.respond={_,token->if(token==null) DiscoveryPage(listOf(source.entry("en",languages=listOf("en"))),"next") else DiscoveryPage(listOf(source.entry("es",languages=listOf("es"))))}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0};val controller=DiscoveryController(listOf(SourceOption("Catalog",source)),catalog,backgroundScope)
        try {
            controller.genre(Genre.ADVENTURE);controller.language("es");runCurrent()
            assertTrue(controller.state.value.entries.isEmpty());assertNotNull(controller.state.value.catalogs.single().nextToken)
            controller.more(source.id);runCurrent();assertEquals("es",controller.state.value.entries.single().publication.id.localId)
        } finally {controller.close();catalog.close()}
    }

    @Test fun developmentSourcesRemainOwnedButNeverAppearInNormalDiscoveryOptions()=runTest {
        val real=CatalogFixture();val demo=CatalogFixture(org.infinilect.core.SourceId("demo"))
        val owner=ApplicationSources(listOf(SourceOption("Catalog",real),SourceOption("EPUB development demo",demo,developmentOnly=true))) {}
        val app=ApplicationSession(owner,backgroundScope,StandardTestDispatcher(testScheduler)){0}
        try {
            assertEquals(listOf(real.id),app.catalogOptions.map {it.source.id})
            assertEquals(2,owner.options.size)
            app.discovery.edit("q");advanceTimeBy(350);runCurrent();assertEquals(0,demo.calls)
        } finally {app.close();owner.close()}
    }

    @Test fun failureDiagnosticsContainOnlySourceAndCategoryAndNeverNormalLifecycleCancellation()=runTest {
        val source=CatalogFixture();val diagnostics=mutableListOf<DiscoveryDiagnostic>()
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope,diagnostics::add){0}
        try {
            source.respond={_,_->error("Private query/profile/path must not appear in diagnostics")}
            assertFailsWith<IllegalStateException>{catalog.load(source.id,DiscoveryRequest("private search"))}
            assertEquals(listOf(DiscoveryDiagnostic(source.id,DiscoveryFailure.REQUEST_FAILED)),diagnostics)
            source.respond={_,_->awaitCancellation()}
            val task=backgroundScope.launch {catalog.load(source.id,DiscoveryRequest("pending"))};runCurrent();task.cancel();runCurrent()
            assertEquals(1,diagnostics.size)
        } finally {catalog.close()}
    }

    @Test fun rapidQueriesAndOptInChangesCannotPublishRetiredHomeResults()=runTest {
        val source=CatalogFixture();val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val home=HomeDiscovery(listOf(SourceOption("Catalog",source)),catalog,backgroundScope){0}
        val gates=mutableListOf<CompletableDeferred<DiscoveryPage>>()
        source.respond={_,_->CompletableDeferred<DiscoveryPage>().also {gates+=it}.await()}
        try {
            repeat(30) {home.refresh(DiscoveryPreferences(homeEnabled=true,interests=setOf(Genre.ADVENTURE)));runCurrent();home.refresh(DiscoveryPreferences());runCurrent()}
            gates.forEach {it.complete(DiscoveryPage(listOf(source.entry("obsolete"))))};runCurrent()
            assertFalse(home.state.value.loading);assertTrue(home.state.value.rows.isEmpty());assertEquals(0,catalog.retainedPages())
        } finally {home.close();catalog.close()}
    }
    @Test fun classifiedStaleFailureCannotOverwriteNewIntentAcrossHomeSearchNavigation()=runTest {
        val source=CatalogFixture();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        source.respond={request,_->if(request.query=="Cervantes") {
            entered.complete(Unit);withContext(NonCancellable){release.await()};throw CatalogSourceException(CatalogErrorKind.TLS)
        }else DiscoveryPage(listOf(source.entry("current")))}
        val owner=ApplicationSources(listOf(SourceOption("Gutenberg",source))){}
        val app=ApplicationSession(owner,backgroundScope,StandardTestDispatcher(testScheduler)){0}
        try {
            app.navigate(Destination.SEARCH);app.discovery.edit("Cervantes");app.discovery.submit();runCurrent();entered.await()
            app.navigate(Destination.HOME);app.navigate(Destination.SEARCH)
            app.discovery.edit("Frankenstein");app.discovery.submit();runCurrent()
            release.complete(Unit);runCurrent()
            assertEquals("Frankenstein",app.discovery.state.value.query)
            assertEquals("current",app.discovery.state.value.entries.single().publication.id.localId)
            assertTrue(app.discovery.state.value.catalogs.none {it.failed || it.failure!=null})
        }finally{release.complete(Unit);app.close();owner.close()}
    }
    @Test fun outerCatalogTimeoutKeepsItsCategoryAndNoPendingResults()=runTest {
        val source=CatalogFixture();source.respond={_,_->awaitCancellation()}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val controller=DiscoveryController(listOf(SourceOption("Gutenberg",source)),catalog,backgroundScope)
        try {
            controller.edit("Cervantes");controller.submit();runCurrent()
            advanceTimeBy(20_000);runCurrent()
            assertEquals(CatalogErrorKind.TIMEOUT,controller.state.value.catalogs.single().failure)
            assertTrue(controller.state.value.catalogs.single().failed)
            assertFalse(controller.state.value.loading);assertTrue(controller.state.value.entries.isEmpty())
        }finally{controller.close();catalog.close()}
    }
}
