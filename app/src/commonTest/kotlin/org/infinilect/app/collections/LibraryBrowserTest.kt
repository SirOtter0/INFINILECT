// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBrowserTest {
    private val a=LibraryEntry(PublicationSnapshot(PublicationId(SourceId("fixture"),"a"),"La ciència de l’univers",PublicationType.BOOK,listOf("María López")),1,3,
        LibraryReadingState.READING,false,true,listOf(LibraryTag(1,"Estudis"),LibraryTag(2,"FANTASÍA")))
    private val b=LibraryEntry(PublicationSnapshot(PublicationId(SourceId("fixture"),"b"),"Manga 作者",PublicationType.COMIC),2)
    private val c=a.copy(publication=a.publication.copy(id=a.publication.id.copy(localId="c"),title="Other",authors=listOf("Other")),addedAtEpochMillis=3,tags=listOf(LibraryTag(1,"Estudis")),favorite=false)
    private val rows get()=listOf(b,c,a)
    private fun matches(query: LibraryQuery)=libraryMatches(libraryIndex(rows),query).map {it.publication.id.localId}
    @Test fun searchesTitleAuthorAndTagsWithCaseUnicodeAndCanonicalAccents() {
        for(query in listOf("CIENCIA", "cie\u0300ncia", "univers", "MARIA lopez", "fantasia", "ESTUDIS fantasía")) assertEquals(listOf("a"),matches(LibraryQuery(text=query)),query)
        assertEquals(listOf("b"),matches(LibraryQuery(text="作者")))
        assertTrue(matches(LibraryQuery(text="absent")).isEmpty())
    }
    @Test fun combinedFiltersRequireAllSelectedTagsAndSearchTerms() {
        assertEquals(listOf("a"),matches(LibraryQuery(text="maria",state=LibraryReadingState.READING,favorites=true,tags=setOf(1,2))))
        assertEquals(listOf("b"),matches(LibraryQuery(untagged=true)))
        assertTrue(matches(LibraryQuery(tags=setOf(2),untagged=true)).isEmpty())
        assertEquals(listOf("c","a"),matches(LibraryQuery(tags=setOf(1))))
    }
    @Test fun everyReadingStateCanBeFilteredWithoutChangingStoredRecords() {
        for(state in LibraryReadingState.entries) {
            val entry=a.copy(readingState=state)
            assertEquals(listOf(entry),libraryMatches(libraryIndex(listOf(entry)),LibraryQuery(state=state)))
            assertTrue(libraryMatches(libraryIndex(listOf(entry)),LibraryQuery(state=LibraryReadingState.entries.first {it!=state})).isEmpty())
        }
        assertEquals(LibraryReadingState.READING,a.readingState)
    }
    @Test fun allSortsUseDeterministicIdentityTiesAndPutMissingMetadataLast() {
        assertEquals(listOf("c","b","a"),matches(LibraryQuery()))
        assertEquals(listOf("a","b","c"),matches(LibraryQuery(descending=false)))
        assertEquals(listOf("a","c","b"),matches(LibraryQuery(sort=LibrarySort.LAST_READ)))
        for(sort in LibrarySort.entries) for(descending in listOf(true,false)) {
            val identical=listOf(a.copy(publication=a.publication.copy(id=a.publication.id.copy(localId="z"))),a)
            assertEquals(listOf("a","z"),libraryMatches(libraryIndex(identical),LibraryQuery(sort=sort,descending=descending)).map {it.publication.id.localId})
        }
        assertEquals(listOf("a","c","b"),matches(LibraryQuery(sort=LibrarySort.AUTHOR,descending=false)))
        assertEquals(listOf("a","b","c"),matches(LibraryQuery(sort=LibrarySort.TITLE,descending=false)))
    }
    @Test fun progressSortUsesNewestRecordAndNeverTurnsCompletionIntoAReadingState() {
        fun record(entry: LibraryEntry, amount: Double, time: Long) = ReadingProgress(ReadingProgressId(entry.publication.id,"text",PublicationFormat.TEXT),ReadingLocator.Text(1,10),amount,time)
        val index=libraryIndex(rows,listOf(record(a,.2,1),record(a,1.0,2),record(b,.5,3)))
        assertEquals(listOf("a","b","c"),libraryMatches(index,LibraryQuery(sort=LibrarySort.PROGRESS)).map {it.publication.id.localId})
        assertEquals(listOf("b","a","c"),libraryMatches(index,LibraryQuery(sort=LibrarySort.PROGRESS,descending=false)).map {it.publication.id.localId})
        assertEquals(listOf("a","b","c"),libraryMatches(index,LibraryQuery(sort=LibrarySort.LAST_READ)).map {it.publication.id.localId})
        assertEquals(LibraryReadingState.READING,a.readingState)
    }
    @Test fun queryChangesNeverReadRepositoriesOrReloadProgressAndSurviveScreenDisposal()=runTest {
        val entries=MutableStateFlow(CollectionList(rows));var loads=0
        val browser=LibraryBrowser(backgroundScope,entries,StandardTestDispatcher(testScheduler)) {loads++;emptyList()}
        browser.activate();runCurrent();assertEquals(1,loads)
        repeat(30) {browser.change(LibraryQuery(text=if(it%2==0) "missing" else "ciència",favorites=it%2!=0))}
        runCurrent();assertEquals(listOf(a),browser.result.value);assertEquals(1,loads)
        browser.activate();runCurrent();assertEquals(1,loads);assertEquals("ciència",browser.query.value.text)
        browser.resetFilters();runCurrent();assertEquals("ciència",browser.query.value.text);assertFalse(browser.query.value.filtered)
    }
    @Test fun aLateProgressResponseCannotOverwriteNewerLibrarySnapshot()=runTest {
        val entries=MutableStateFlow(CollectionList(listOf(a)));val gate=CompletableDeferred<Unit>();var calls=0
        val browser=LibraryBrowser(backgroundScope,entries,StandardTestDispatcher(testScheduler)) {
            calls++;if(calls==1) withContext(NonCancellable) {gate.await()};emptyList()
        }
        browser.activate();runCurrent();entries.value=CollectionList(listOf(b));browser.change(LibraryQuery(text="作者"));gate.complete(Unit);runCurrent()
        assertEquals(listOf(b),browser.result.value);assertFalse(browser.working.value)
    }
    @Test fun incrementalMetadataUpdatesReindexWithoutAProgressRescanAndEmptyResultsRecover()=runTest {
        val entries=MutableStateFlow(CollectionList(listOf(a)));var loads=0
        val browser=LibraryBrowser(backgroundScope,entries,StandardTestDispatcher(testScheduler)) {loads++;emptyList()}
        browser.activate();runCurrent();browser.change(LibraryQuery(tags=setOf(3)));runCurrent();assertTrue(browser.result.value.isEmpty())
        entries.value=CollectionList(listOf(a.copy(tags=listOf(LibraryTag(3,"New")))));runCurrent()
        assertEquals(1,loads);assertEquals(1,browser.result.value.size)
        browser.resetFilters();runCurrent();assertEquals(1,browser.result.value.size)
    }
    @Test fun organizationDeltasDoNotExposeMutableCallerSets()=runTest {
        val entries=MutableStateFlow(CollectionList(rows));val browser=LibraryBrowser(backgroundScope,entries,StandardTestDispatcher(testScheduler))
        val chosen=mutableSetOf(1L);browser.change(LibraryQuery(tags=chosen));chosen.clear()
        assertEquals(setOf(1L),browser.query.value.tags)
        browser.change(LibraryQuery(text="x".repeat(500)));assertEquals(256,browser.query.value.text.length)
    }

    @Test fun downloadedIsCombinedWithExistingFiltersAndDoesNotRescanProgress()=runTest {
        val entries=MutableStateFlow(CollectionList(rows));val available=MutableStateFlow(setOf(a.publication.id,b.publication.id));var loads=0
        val browser=LibraryBrowser(backgroundScope,entries,StandardTestDispatcher(testScheduler),available) {loads++;emptyList()}
        browser.activate();browser.change(LibraryQuery(text="fantasia",favorites=true,state=LibraryReadingState.READING,tags=setOf(2),downloaded=true));runCurrent()
        assertEquals(listOf(a),browser.result.value);assertEquals(1,loads)
        available.value=setOf(b.publication.id);runCurrent();assertTrue(browser.result.value.isEmpty());assertEquals(1,loads)
        browser.resetFilters();runCurrent();assertFalse(browser.query.value.downloaded);assertEquals("fantasia",browser.query.value.text);assertEquals(listOf(a),browser.result.value)
        assertEquals(rows,entries.value.entries)
    }
}
