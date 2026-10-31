// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.*
import org.infinilect.app.collections.*
import org.infinilect.app.ui.*
import org.infinilect.core.*
import kotlin.test.*

class SourceVisibilityTest {
    @Test fun implementationCapabilitiesNotNamesMimeTypesOrNetworkHealthDetermineVisibility() {
        val source=CatalogFixture(SourceId("future-provider"))
        val catalog=SourceOption("Internet Archive",source)
        assertFalse(catalog.supportsIntegratedReading);assertFalse(catalog.visibleInCatalogs(false));assertTrue(catalog.visibleInCatalogs(true))
        for(option in listOf(SourceOption("New",source,textReadingEnabled=true),SourceOption("New",source,epubReadingEnabled=true),
            SourceOption("New",source,pageReadingEnabled=true),SourceOption("New",source,pdfReadingEnabled=true))) {
            assertTrue(option.supportsIntegratedReading);assertTrue(option.visibleInCatalogs(false))
        }
        assertFalse(DiscoveryPreferences().showCatalogOnlySources)
    }
    @Test fun defaultHidesCatalogOnlyAndToggleRestoresSelectionWithoutRefetchingVisibleResults()=runTest {
        val reader=CatalogFixture(SourceId("new-readable"));val catalogOnly=CatalogFixture(SourceId("new-catalog"))
        val catalog=DiscoveryCatalog(listOf(reader,catalogOnly),backgroundScope){testScheduler.currentTime}
        val controller=DiscoveryController(listOf(SourceOption("Readable",reader,textReadingEnabled=true),SourceOption("Catalog",catalogOnly)),catalog,backgroundScope)
        try {
            assertEquals(setOf(reader.id),controller.state.value.enabled)
            assertEquals(listOf(reader.id),controller.visibleOptions.map {it.source.id})
            controller.edit("book");advanceTimeBy(350);runCurrent()
            assertEquals(1,reader.calls);assertEquals(0,catalogOnly.calls)
            controller.catalogOnlyVisibility(true);runCurrent()
            assertEquals(2,controller.state.value.entries.size);assertEquals(1,reader.calls);assertEquals(1,catalogOnly.calls)
            controller.catalogOnlyVisibility(false);runCurrent()
            assertEquals(setOf(reader.id),controller.state.value.enabled)
            assertEquals(listOf(reader.id),controller.state.value.entries.map {it.publication.id.sourceId})
            controller.catalogOnlyVisibility(true);runCurrent()
            assertTrue(catalogOnly.id in controller.state.value.enabled);assertEquals(1,catalogOnly.calls)
            controller.filter(catalogOnly.id);runCurrent()
            controller.catalogOnlyVisibility(false);controller.catalogOnlyVisibility(true);runCurrent()
            assertFalse(catalogOnly.id in controller.state.value.enabled,"An explicit deselection must remain a deselection")
        } finally {controller.close();catalog.close()}
    }
    @Test fun hidingCancelsPendingCatalogAndRejectsItsLateResultButKeepsVisibleRequest()=runTest {
        val readable=CatalogFixture(SourceId("readable"));val hidden=CatalogFixture(SourceId("catalog"))
        val ready=CompletableDeferred<DiscoveryPage>();val late=CompletableDeferred<DiscoveryPage>();val cancelled=CompletableDeferred<Unit>()
        readable.respond={_,_->ready.await()}
        hidden.respond={_,_->try {withContext(NonCancellable){late.await()}} finally {cancelled.complete(Unit)}}
        val catalog=DiscoveryCatalog(listOf(readable,hidden),backgroundScope){0}
        val controller=DiscoveryController(listOf(SourceOption("R",readable,pdfReadingEnabled=true),SourceOption("C",hidden)),catalog,backgroundScope)
        try {
            controller.catalogOnlyVisibility(true);controller.edit("q");advanceTimeBy(350);runCurrent()
            assertEquals(1,readable.calls);assertEquals(1,hidden.calls)
            controller.catalogOnlyVisibility(false);runCurrent()
            ready.complete(DiscoveryPage(listOf(readable.entry("visible"))));late.complete(DiscoveryPage(listOf(hidden.entry("obsolete"))));runCurrent()
            assertTrue(cancelled.isCompleted)
            assertEquals(listOf(readable.id),controller.state.value.entries.map {it.publication.id.sourceId})
            assertEquals(1,readable.calls)
        } finally {late.complete(DiscoveryPage(emptyList()));controller.close();catalog.close()}
    }
    @Test fun pausedTabVisibilityChangesDoNotRequestUntilReturning()=runTest {
        val source=CatalogFixture();val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val controller=DiscoveryController(listOf(SourceOption("C",source)),catalog,backgroundScope)
        try {
            controller.edit("q");advanceTimeBy(350);runCurrent();assertEquals(0,source.calls)
            controller.pause();controller.catalogOnlyVisibility(true);runCurrent();assertEquals(0,source.calls)
            controller.resume();runCurrent();assertEquals(1,source.calls)
        } finally {controller.close();catalog.close()}
    }
    @Test fun temporarilyFailedReadableSourceRemainsVisibleAndCanRetry()=runTest {
        val source=CatalogFixture();source.respond={_,_->error("offline")}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val controller=DiscoveryController(listOf(SourceOption("Future",source,epubReadingEnabled=true)),catalog,backgroundScope)
        try {
            controller.edit("q");advanceTimeBy(350);runCurrent()
            assertTrue(controller.state.value.catalogs.single().failed)
            assertEquals(1,controller.visibleOptions.size)
            source.respond={_,_->DiscoveryPage(listOf(source.entry("ready")))};controller.retry(source.id);runCurrent()
            assertEquals(1,controller.state.value.entries.size)
        } finally {controller.close();catalog.close()}
    }
    @Test fun homeHidesCatalogOnlyRowsAndWarmTogglesReuseTheExistingCache()=runTest {
        val r=CatalogFixture(SourceId("r"));val c=CatalogFixture(SourceId("c"));val catalog=DiscoveryCatalog(listOf(r,c),backgroundScope){0}
        val home=HomeDiscovery(listOf(SourceOption("R",r,textReadingEnabled=true),SourceOption("C",c)),catalog,backgroundScope){0}
        try {
            val prefs=DiscoveryPreferences(homeEnabled=true)
            home.refresh(prefs);runCurrent();assertEquals(0,c.calls)
            val calls=r.calls
            home.refresh(prefs.copy(showCatalogOnlySources=true));runCurrent();assertTrue(c.calls>0)
            assertEquals(calls,r.calls)
            home.catalogOnlyVisibility(false);runCurrent()
            assertTrue(home.state.value.rows.flatMap {it.entries}.all {it.publication.id.sourceId==r.id})
        } finally {home.close();catalog.close()}
    }
    private class Store(var value:ApplicationPreferences=ApplicationPreferences()):ApplicationAppearanceStore {
        var gate:CompletableDeferred<Unit>?=null;var succeed=true
        override suspend fun load()=value.mode
        override suspend fun save(mode:ApplicationThemeMode)=true
        override suspend fun loadPreferences():ApplicationPreferences {val before=value;gate?.await();return before}
        override suspend fun savePreferences(value:ApplicationPreferences):Boolean {if(succeed)this.value=value;return succeed}
    }
    @Test fun visibilityEditDuringInitialLoadWinsAndPreservesOtherLoadedPreferences()=runTest {
        val loaded=ApplicationPreferences(ApplicationThemeMode.DARK,LocalProfile("Original","ES","ca",true),readingLanguage="es")
        val store=Store(loaded).apply {gate=CompletableDeferred()}
        val preferences=ApplicationAppearancePreferences(store,StandardTestDispatcher(testScheduler));runCurrent()
        preferences.changeDiscovery(DiscoveryPreferences(showCatalogOnlySources=true));store.gate!!.complete(Unit);runCurrent()
        assertEquals(loaded.profile,preferences.state.value.profile);assertEquals("es",preferences.state.value.readingLanguage)
        assertTrue(preferences.state.value.discovery.showCatalogOnlySources)
        preferences.close();advanceUntilIdle();preferences.awaitClosed()
        assertEquals(loaded.copy(discovery=DiscoveryPreferences(showCatalogOnlySources=true)),store.value)
    }
    @Test fun changingVisibilityLeavesLibraryHistoryAndLogicalProgressUntouchedAndRestoresLegacySelection()=runTest {
        val r=CatalogFixture(SourceId("reader"));val c=CatalogFixture(SourceId("catalog"));val data=FakeCollections()
        val book=c.entry("saved").publication;val snapshot=PublicationSnapshot.from(book)
        data.saved[book.id]=LibraryEntry(snapshot,1)
        data.opened[book.id]=HistoryEntry(snapshot,2)
        val record=ReadingProgress(ReadingProgressId(book.id,"original-resource",PublicationFormat.TEXT),ReadingLocator.Text(40,100),0.4,3)
        var writes=0
        val progress=org.infinilect.app.progress.ProgressPersistence(object:ReadingProgressStore {
            override suspend fun get(id:ReadingProgressId)=record.takeIf {it.id==id}
            override suspend fun save(progress:ReadingProgress):Boolean {writes++;return true}
            override suspend fun remove(id:ReadingProgressId):Boolean {writes++;return true}
        },StandardTestDispatcher(testScheduler))
        val store=Store()
        val preferences=ApplicationAppearancePreferences(store,StandardTestDispatcher(testScheduler))
        val owner=ApplicationSources(listOf(SourceOption("R",r,textReadingEnabled=true),SourceOption("C",c)),
            appearance=preferences,progress=progress,collections=ApplicationCollections(data.library,data.history,StandardTestDispatcher(testScheduler)),sessionDispatcher=StandardTestDispatcher(testScheduler)) {}
        val app=owner.applicationSession()
        try {
            runCurrent();app.collections.ensureLibrary();runCurrent()
            preferences.changeDiscovery(DiscoveryPreferences(showCatalogOnlySources=true));runCurrent();app.selectSource(1)
            assertEquals(1,app.selected.value)
            preferences.changeDiscovery(DiscoveryPreferences());runCurrent()
            assertEquals(0,app.selected.value);assertEquals(listOf(r.id),app.catalogOptions.map {it.source.id})
            assertEquals(snapshot,data.saved[book.id]?.publication);assertEquals(HistoryEntry(snapshot,2),data.opened[book.id]);assertEquals(0,c.acquisition)
            preferences.changeDiscovery(DiscoveryPreferences(showCatalogOnlySources=true));runCurrent()
            assertEquals(1,app.selected.value);assertEquals(1,data.saved.size)
            assertEquals(record,progress.get(record.id));assertEquals(0,writes)
            assertEquals("C",app.sourceName(book.id.sourceId))
        } finally {owner.close();advanceUntilIdle();owner.awaitProgressClosed()}
    }
}
