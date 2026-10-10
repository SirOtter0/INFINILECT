// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.infinilect.app.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.SourceOption
import org.infinilect.core.*
import kotlin.test.*

internal class CatalogFixture(override val id: SourceId = SourceId("catalog")) : PublicationSource, DiscoverySource {
    var calls = 0; var acquisition = 0
    val requests = mutableListOf<Pair<DiscoveryRequest,String?>>()
    override val canBrowseGenres = true
    var respond: suspend (DiscoveryRequest,String?) -> DiscoveryPage = { _, _ -> DiscoveryPage(listOf(entry("1"))) }
    fun entry(local: String, subjects: List<String> = emptyList(), languages: List<String> = listOf("en")) = DiscoveryEntry(
        Publication(PublicationId(id,local),"Original publication $local",PublicationType.BOOK,languages=languages),subjects)
    override suspend fun discover(request: DiscoveryRequest, token: String?): DiscoveryPage { calls++; requests += request to token; return respond(request,token) }
    override suspend fun search(query: String, pageToken: String?): SearchPage = discover(DiscoveryRequest(query),pageToken).let {SearchPage(it.entries.map { e -> e.publication },it.nextToken)}
    override suspend fun getPublication(publicationId: PublicationId): Publication? = entry(publicationId.localId).publication
    override suspend fun loadResource(resource: PublicationResource): ResourceContent { acquisition++; error("Discovery is not acquisition") }
}
class DiscoveryTest {
    @Test fun normalizesWhitespaceWithoutChangingAuthorOrAdvancedQuerySyntax() {
        assertEquals("María author:Smith",normalizedQuery("  María   author:Smith  "))
        assertEquals("",normalizedQuery(" \n "))
        assertFailsWith<IllegalArgumentException> { normalizedQuery("x\u0000") }
        assertFailsWith<IllegalArgumentException> { normalizedQuery("x".repeat(257)) }
    }
    @Test fun exactSubjectsOnlyNeverTitleGuessing() {
        assertEquals(setOf(Genre.SCIENCE_FICTION),mappedGenres("Science fiction -- History and criticism"))
        assertTrue(mappedGenres("Science fictional observations").isEmpty())
        assertTrue(CatalogFixture().entry("Science fiction").genres.isEmpty())
        assertEquals(setOf(Genre.MYSTERY),mappedGenres("Detective and mystery stories"))
    }
    @Test fun sameSourceIdentityDeduplicatesButSameTitleAcrossSourcesRemainsSeparate() {
        val a=CatalogFixture();val b=CatalogFixture(SourceId("other"))
        assertEquals(2,distinctEntries(listOf(a.entry("1"),a.entry("1"),b.entry("1"))).size)
        assertEquals(2,distinctEntries(listOf(a.entry("1"),a.entry("2").copy(publication=a.entry("2").publication.copy(title=a.entry("1").publication.title)))).size)
    }
    @Test fun independentFailuresDoNotDiscardSuccessfulSourcesAndRetryIsIsolated()=runTest {
        val a=CatalogFixture(); val b=CatalogFixture(SourceId("other"));b.respond={_,_->error("private error path")}
        val catalog=DiscoveryCatalog(listOf(a,b),backgroundScope){testScheduler.currentTime}
        val controller=DiscoveryController(listOf(SourceOption("A",a),SourceOption("B",b)),catalog,backgroundScope)
        controller.edit("book");advanceTimeBy(350);runCurrent()
        assertEquals(1,controller.state.value.entries.size);assertTrue(controller.state.value.catalogs.last().failed)
        b.respond={_,_->DiscoveryPage(listOf(b.entry("2")))};controller.retry(b.id);runCurrent()
        assertEquals(2,controller.state.value.entries.size);assertEquals(1,a.calls);assertEquals(2,b.calls)
        assertEquals(0,a.acquisition+b.acquisition);controller.close();catalog.close()
    }
    @Test fun queryEditIsDebouncedAndCancelsObsoleteWorkBeforeReplacement()=runTest {
        val source=CatalogFixture();val pending=CompletableDeferred<DiscoveryPage>();source.respond={request,_->if(request.query=="first") pending.await() else DiscoveryPage(listOf(source.entry("new")))}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val controller=DiscoveryController(listOf(SourceOption("A",source)),catalog,backgroundScope)
        controller.edit("f");advanceTimeBy(100);controller.edit("first");advanceTimeBy(349);runCurrent();assertEquals(0,source.calls)
        advanceTimeBy(1);runCurrent();assertEquals(1,source.calls)
        controller.edit("second");advanceTimeBy(350);runCurrent();pending.complete(DiscoveryPage(listOf(source.entry("old"))));runCurrent()
        assertEquals("new",controller.state.value.entries.single().publication.id.localId)
        controller.close();catalog.close()
    }
    @Test fun paginationAppendsWithoutOverlapAndStopsAfterFourPages()=runTest {
        val source=CatalogFixture();source.respond={_,token->val page=token?.toInt()?:1;DiscoveryPage(listOf(source.entry("$page"),source.entry("shared")),"${page+1}")}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0};val controller=DiscoveryController(listOf(SourceOption("A",source)),catalog,backgroundScope)
        controller.edit("q");advanceTimeBy(350);runCurrent()
        repeat(6) {controller.more(source.id);runCurrent()}
        assertEquals(listOf("1","shared","2","3","4"),controller.state.value.entries.map {it.publication.id.localId})
        assertEquals(4,source.calls);controller.close();catalog.close()
    }
    @Test fun languageFilterExcludesUnknownAndKeepsRegionalTags()=runTest {
        val source=CatalogFixture();source.respond={_,_->DiscoveryPage(listOf(source.entry("en",languages=listOf("en-US")),source.entry("es",languages=listOf("es")),source.entry("missing",languages=emptyList())))}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        assertEquals(listOf("en"),catalog.load(source.id,DiscoveryRequest("q",language="en")).entries.map {it.publication.id.localId})
        assertFailsWith<IllegalArgumentException> { DiscoveryRequest("q",language="made-up") };catalog.close()
    }
    @Test fun cacheIsBoundedExpiresAndDoesNotRepeatHomeSearchRequests()=runTest {
        val source=CatalogFixture();var now=0L;val catalog=DiscoveryCatalog(listOf(source),backgroundScope){now}
        repeat(12){catalog.load(source.id,DiscoveryRequest("q$it"))}
        assertEquals(8,catalog.retainedPages());catalog.load(source.id,DiscoveryRequest("q11"));assertEquals(12,source.calls)
        now=300001;catalog.load(source.id,DiscoveryRequest("q11"));assertEquals(13,source.calls);catalog.close();runCurrent();assertEquals(0,catalog.retainedPages())
    }
    @Test fun sameKeyBorrowersShareOneRequestAndCancellationKeepsRemainingBorrower()=runTest {
        val source=CatalogFixture();val ready=CompletableDeferred<DiscoveryPage>();source.respond={_,_->ready.await()}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val a=backgroundScope.async {catalog.load(source.id,DiscoveryRequest("q"))};val b=backgroundScope.async {catalog.load(source.id,DiscoveryRequest("q"))};runCurrent()
        assertEquals(1,source.calls);a.cancel();runCurrent();ready.complete(DiscoveryPage(listOf(source.entry("1"))));runCurrent()
        assertEquals(1,b.await().entries.size);catalog.close()
    }
    @Test fun simultaneousMetadataConcurrencyNeverExceedsTwo()=runTest {
        val source=CatalogFixture();val ready=CompletableDeferred<Unit>();var active=0;var max=0
        source.respond={_,_->active++;max=maxOf(max,active);try{ready.await();DiscoveryPage(emptyList())}finally{active--}}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val requests=List(4){backgroundScope.async {catalog.load(source.id,DiscoveryRequest("q$it"))}}
        runCurrent();assertEquals(2,max);ready.complete(Unit);runCurrent();requests.forEach {it.await()};assertEquals(0,active);assertEquals(2,max);catalog.close()
    }
    @Test fun lastCancellationRetiresTaskAndNeverCachesPartialResult()=runTest {
        val source=CatalogFixture();var cancelled=false;source.respond={_,_->try{awaitCancellation()}finally{cancelled=true}}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val task=backgroundScope.launch {catalog.load(source.id,DiscoveryRequest("q"))};runCurrent();task.cancel();runCurrent()
        assertTrue(cancelled);assertEquals(0,catalog.retainedPages());catalog.close()
    }
    @Test fun foreignIdentityAndOversizedPagesFailWithoutCacheOrAcquisition()=runTest {
        val source=CatalogFixture();val other=CatalogFixture(SourceId("other"));val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        source.respond={_,_->DiscoveryPage(listOf(other.entry("1")))}
        assertFailsWith<IllegalArgumentException>{catalog.load(source.id,DiscoveryRequest("q"))}
        source.respond={_,_->DiscoveryPage(List(26){source.entry("$it")})}
        assertFailsWith<IllegalArgumentException>{catalog.load(source.id,DiscoveryRequest("q"))}
        assertEquals(0,catalog.retainedPages());assertEquals(0,source.acquisition);catalog.close()
    }
    @Test fun recommendationsExcludeLibraryUseKnownSubjectsAndCapHistoryInfluence() {
        val source=CatalogFixture();val fantasy=source.entry("fantasy",listOf("Fantasy"));val mystery=source.entry("mystery",listOf("Detective and mystery stories"));val inLibrary=source.entry("owned",listOf("Fantasy"))
        val ranked=rankRecommendations(listOf(mystery,fantasy,inLibrary),setOf(Genre.FANTASY),setOf(inLibrary.publication.id),mapOf(Genre.MYSTERY to 1000))
        assertEquals(listOf("fantasy","mystery"),ranked.map {it.publication.id.localId})
        assertEquals(2,rankRecommendations(listOf(fantasy,mystery),emptySet(),emptySet()).size)
    }
    @Test fun recommendationDiversityPreservesSourceAttributionAndEditionIdentity() {
        val a=CatalogFixture();val b=CatalogFixture(SourceId("other"))
        val ranked=rankRecommendations(List(25){a.entry("$it",listOf("Fantasy"))}+b.entry("1",listOf("Fantasy")),setOf(Genre.FANTASY),emptySet())
        assertEquals(12,ranked.size);assertEquals(b.id,ranked[1].publication.id.sourceId)
    }
    @Test fun homeDoesNotRequestWithoutOptInAndFailedDiscoveryNeverCreatesFakeRows()=runTest {
        val source=CatalogFixture();source.respond={_,_->error("offline")};val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0}
        val home=HomeDiscovery(listOf(SourceOption("A",source)),catalog,backgroundScope){0}
        home.refresh(DiscoveryPreferences());runCurrent();assertEquals(0,source.calls)
        home.refresh(DiscoveryPreferences(homeEnabled=true));runCurrent();assertTrue(home.state.value.failed);assertTrue(home.state.value.rows.isEmpty())
        home.close();catalog.close()
    }
    @Test fun homeRowsComeFromExactSourceRequestsAndReuseCache()=runTest {
        val source=CatalogFixture();val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0};val home=HomeDiscovery(listOf(SourceOption("A",source)),catalog,backgroundScope){0}
        val prefs=DiscoveryPreferences(homeEnabled=true,interests=setOf(Genre.FANTASY));home.refresh(prefs);runCurrent()
        assertEquals(3,source.calls);assertEquals(Genre.FANTASY,source.requests[1].first.genre)
        home.pause();home.refresh(prefs);runCurrent();assertEquals(3,source.calls)
        home.refresh(prefs.copy(homeEnabled=false));runCurrent();assertTrue(home.state.value.rows.isEmpty());home.close();catalog.close()
    }
    @Test fun closingRejectsFurtherWorkAndClearsPrivateMetadata()=runTest {
        val source=CatalogFixture();val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0};catalog.load(source.id,DiscoveryRequest("q"));catalog.close();runCurrent()
        assertEquals(0,catalog.retainedPages());assertFailsWith<CancellationException>{catalog.load(source.id,DiscoveryRequest("q"))}
    }
    @Test fun displayMetadataIsInertAndBoundedWithMissingFieldsRemainingAbsent() {
        assertEquals("A & B",catalogPlainText("<p>A &amp; B</p>"));assertNull(catalogPlainText("   "))
        assertEquals(4096,catalogPlainText("x".repeat(20000))!!.length)
        val entry=boundedEntry(CatalogFixture().entry("1").copy(subjects=List(50){"s".repeat(1000)},description="x".repeat(10000)))
        assertEquals(16,entry.subjects.size);assertEquals(256,entry.subjects.first().length);assertEquals(4096,entry.description!!.length);assertNull(entry.publication.rights)
    }

    @Test fun duplicateSubmitCoalescesAndPaginationRetryKeepsPriorResults()=runTest {
        val source=CatalogFixture();val wait=CompletableDeferred<Unit>();var fail=true
        source.respond={_,token->if(token==null){wait.await();DiscoveryPage(listOf(source.entry("first")),"next")}else{if(fail)error("offline");DiscoveryPage(listOf(source.entry("second")))}}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0};val controller=DiscoveryController(listOf(SourceOption("A",source)),catalog,backgroundScope)
        controller.edit("q");advanceTimeBy(350);runCurrent();repeat(5){controller.submit()};runCurrent();assertEquals(1,source.calls)
        wait.complete(Unit);runCurrent();controller.more(source.id);runCurrent();assertEquals("first",controller.state.value.entries.single().publication.id.localId)
        fail=false;controller.retry(source.id);runCurrent();assertEquals(listOf("first","second"),controller.state.value.entries.map{it.publication.id.localId})
        assertEquals("next",source.requests.last().second);controller.close();catalog.close()
    }
    @Test fun timedOutSourceStopsLoadingAndAllowsSafeRetry()=runTest {
        val source=CatalogFixture();source.respond={_,_->awaitCancellation()}
        val catalog=DiscoveryCatalog(listOf(source),backgroundScope){testScheduler.currentTime};val controller=DiscoveryController(listOf(SourceOption("A",source)),catalog,backgroundScope)
        controller.edit("q");advanceTimeBy(350);runCurrent();advanceTimeBy(20000);runCurrent()
        assertFalse(controller.state.value.loading);assertTrue(controller.state.value.catalogs.single().failed)
        source.respond={_,_->DiscoveryPage(listOf(source.entry("ready")))};controller.retry(source.id);runCurrent();assertEquals("ready",controller.state.value.entries.single().publication.id.localId)
        controller.close();catalog.close()
    }
    @Test fun isoLanguageAliasesAndOnePassMetadataEntitiesRemainDeterministic() {
        assertTrue(languageMatches("eng","en"));assertTrue(languageMatches("deu","de"));assertFalse(languageMatches("unknown","en"))
        assertEquals("&lt;",catalogPlainText("&amp;lt;"))
    }

    @Test fun optingOutIgnoresStoredInterestsInHomeRequestsAndResetRetainsLocalDataBoundary()=runTest {
        val source=CatalogFixture();val catalog=DiscoveryCatalog(listOf(source),backgroundScope){0};val home=HomeDiscovery(listOf(SourceOption("A",source)),catalog,backgroundScope){0}
        home.refresh(DiscoveryPreferences(homeEnabled=true,personalized=false,interests=setOf(Genre.FANTASY)));runCurrent()
        assertEquals(listOf(null,Genre.PHILOSOPHY,Genre.HISTORY),source.requests.map{it.first.genre})
        assertEquals(0,source.acquisition);home.close();catalog.close()
    }

    @Test fun homePublishesFastProviderWhileOtherCatalogIsStillPending()=runTest {
        val slow=CatalogFixture();slow.respond={_,_->awaitCancellation()};val fast=CatalogFixture(SourceId("fast"))
        val catalog=DiscoveryCatalog(listOf(slow,fast),backgroundScope){0};val home=HomeDiscovery(listOf(SourceOption("Slow",slow),SourceOption("Fast",fast)),catalog,backgroundScope){0}
        home.refresh(DiscoveryPreferences(homeEnabled=true));runCurrent()
        assertTrue(home.state.value.loading);assertEquals(fast.id,home.state.value.rows.single().entries.single().publication.id.sourceId)
        home.pause();runCurrent();assertFalse(home.state.value.loading);home.close();catalog.close()
    }

    @Test fun sourceRightsStatementIsRetainedAcrossTheFullVerifiedMetadataLimit() {
        val source=CatalogFixture();val rights="A".repeat(8000)+" Restricted conditions remain part of this statement."
        val entry=boundedEntry(source.entry("1").copy(publication=source.entry("1").publication.copy(rights=rights)))
        assertEquals(rights,entry.publication.rights)
    }
}
