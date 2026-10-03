// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogLibraryControllerTest {
    private val id=PublicationId(SourceId("fixture"),"1")
    private fun publication(key: PublicationId=id, title: String="Same title") =
        Publication(key,title,PublicationType.BOOK,listOf("Author"),
            listOf(PublicationResource(key,"text",PublicationFormat.TEXT,"text/plain")),
            languages=listOf("en"),sourceUrl="https://example.org/details",rights="Original rights")
    private class Queries(val fake: FakeCollections) : LibraryRepository by fake.library {
        var lists=0;var lookups=0;var writes=0
        var read: (suspend () -> LocalStoreResult<List<LibraryEntry>>)?=null
        override suspend fun list(): LocalStoreResult<List<LibraryEntry>> { lists++;return read?.invoke() ?: fake.library.list() }
        override suspend fun get(id: PublicationId): LocalStoreResult<LibraryEntry?> { lookups++;return fake.library.get(id) }
        override suspend fun contains(id: PublicationId): LocalStoreResult<Boolean> { lookups++;return fake.library.contains(id) }
        override suspend fun put(publication: PublicationSnapshot,atEpochMillis: Long): LocalStoreResult<LibraryEntry> {
            writes++;return fake.library.put(publication,atEpochMillis)
        }
        override suspend fun remove(id: PublicationId): LocalStoreResult<Unit> { writes++;return fake.library.remove(id) }
    }
    private class Fixture(val owner: ApplicationCollections,val controller: CollectionsController) {
        suspend fun close() { controller.close();owner.close();owner.awaitClosed() }
    }
    private fun TestScope.fixture(fake: FakeCollections, queries: Queries=Queries(fake)): Fixture {
        val owner=ApplicationCollections(queries,fake.history,StandardTestDispatcher(testScheduler))
        return Fixture(owner,CollectionsController(owner,this){1L})
    }
    @Test fun unknownMembershipDisablesMutationUntilBoundedSnapshotLoads()=runTest {
        val fake=FakeCollections();val queries=Queries(fake);val gate=CompletableDeferred<Unit>()
        queries.read={gate.await();fake.library.list()};val f=fixture(fake,queries)
        runCurrent();assertTrue(f.controller.membership.value.loading)
        assertNull(f.controller.membership.value.forPublication(id).inLibrary)
        assertFalse(f.controller.membership.value.forPublication(id).enabled)
        assertNull(f.controller.toggleCatalogLibrary(publication()));assertEquals(0,queries.writes)
        gate.complete(Unit);advanceUntilIdle();assertEquals(false,f.controller.membership.value.forPublication(id).inLibrary)
        assertTrue(f.controller.membership.value.forPublication(id).enabled);f.close()
    }
    @Test fun catalogAddUsesMetadataSnapshotAndRefreshesCommittedMembership()=runTest {
        val fake=FakeCollections();val f=fixture(fake);advanceUntilIdle()
        f.controller.toggleCatalogLibrary(publication());advanceUntilIdle()
        assertEquals(PublicationSnapshot.from(publication()),fake.saved[id]?.publication)
        assertEquals(true,f.controller.membership.value.forPublication(id).inLibrary)
        assertEquals(listOf(id),f.controller.library.value.entries.map {it.publication.id})
        assertTrue(fake.opened.isEmpty());f.close()
    }
    @Test fun catalogRemovePreservesHistory()=runTest {
        val fake=FakeCollections();val snapshot=PublicationSnapshot.from(publication())
        fake.saved[id]=LibraryEntry(snapshot,1);fake.opened[id]=HistoryEntry(snapshot,2)
        val f=fixture(fake);advanceUntilIdle();f.controller.toggleCatalogLibrary(publication());advanceUntilIdle()
        assertEquals(false,f.controller.membership.value.forPublication(id).inLibrary)
        assertTrue(f.controller.library.value.entries.isEmpty());assertEquals(2L,fake.opened[id]?.lastOpenedAtEpochMillis);f.close()
    }
    @Test fun membershipUsesSourceScopedIdentityRatherThanTitles()=runTest {
        val fake=FakeCollections();fake.saved[id]=LibraryEntry(PublicationSnapshot.from(publication()),1)
        val f=fixture(fake);advanceUntilIdle()
        val others=listOf(id.copy(sourceId=SourceId("other")),id.copy(localId="other"))
        assertEquals(true,f.controller.membership.value.forPublication(publication(title="Renamed").id).inLibrary)
        others.forEach { assertEquals(false,f.controller.membership.value.forPublication(publication(it).id).inLibrary) }
        f.close()
    }
    @Test fun resultMembershipDoesNotQueryDatabasePerRow()=runTest {
        val fake=FakeCollections();val queries=Queries(fake);val f=fixture(fake,queries);advanceUntilIdle()
        repeat(100){f.controller.membership.value.forPublication(id.copy(localId="$it"))}
        assertEquals(1,queries.lists);assertEquals(0,queries.lookups)
        f.controller.enteredReader(publication());advanceUntilIdle();assertEquals(0,queries.lookups);f.close()
    }
    @Test fun pendingWriteIsNotOptimisticAndDoesNotDisableOtherRows()=runTest {
        val fake=FakeCollections();val gate=CompletableDeferred<Unit>();fake.beforeWrite={gate.await()}
        val queries=Queries(fake);val f=fixture(fake,queries);advanceUntilIdle()
        f.controller.toggleCatalogLibrary(publication());runCurrent()
        val action=f.controller.membership.value.forPublication(id)
        assertTrue(action.busy);assertFalse(action.enabled);assertEquals(false,action.inLibrary)
        val other=id.copy(localId="2");assertTrue(f.controller.membership.value.forPublication(other).enabled)
        assertNull(f.controller.toggleCatalogLibrary(publication()))
        f.controller.toggleCatalogLibrary(publication(other));runCurrent();assertEquals(2,queries.writes)
        gate.complete(Unit);advanceUntilIdle();assertEquals(setOf(id,other),f.controller.membership.value.saved);f.close()
    }
    @Test fun failedPutKeepsUnsavedStateAndFixedError()=runTest {
        val fake=FakeCollections();val f=fixture(fake);advanceUntilIdle();fake.fail=true
        f.controller.toggleCatalogLibrary(publication());advanceUntilIdle()
        assertEquals(false,f.controller.membership.value.forPublication(id).inLibrary)
        assertFalse(f.controller.membership.value.forPublication(id).busy);assertTrue(fake.saved.isEmpty())
        assertEquals("The library change could not be saved on this device.",f.controller.error.value);f.close()
    }
    @Test fun failedRemoveKeepsSavedStateAndNoFalseDurableSuccess()=runTest {
        val fake=FakeCollections();fake.saved[id]=LibraryEntry(PublicationSnapshot.from(publication()),1)
        val f=fixture(fake);advanceUntilIdle();fake.fail=true;f.controller.toggleCatalogLibrary(publication());advanceUntilIdle()
        assertEquals(true,f.controller.membership.value.forPublication(id).inLibrary);assertTrue(id in fake.saved)
        assertTrue(f.controller.membership.value.unavailable);assertFalse(f.controller.membership.value.forPublication(id).enabled);f.close()
    }
    @Test fun explicitRetryRecoversUnavailableStateAndAllowsDurableMutation()=runTest {
        val fake=FakeCollections();fake.fail=true;val f=fixture(fake);advanceUntilIdle()
        assertTrue(f.controller.membership.value.unavailable);assertNull(f.controller.toggleCatalogLibrary(publication()))
        fake.fail=false;f.controller.refreshLibrary();advanceUntilIdle();f.controller.toggleCatalogLibrary(publication());advanceUntilIdle()
        assertEquals(true,f.controller.membership.value.forPublication(id).inLibrary);assertNull(f.controller.error.value);f.close()
    }
    @Test fun searchAndReaderShareMembershipInBothDirections()=runTest {
        val fake=FakeCollections();val f=fixture(fake);advanceUntilIdle()
        f.controller.toggleCatalogLibrary(publication());advanceUntilIdle();f.controller.enteredReader(publication());advanceUntilIdle()
        assertEquals(true,f.controller.reader.value.inLibrary)
        f.controller.toggleLibrary();advanceUntilIdle();f.controller.leftReader()
        assertEquals(false,f.controller.membership.value.forPublication(id).inLibrary);assertTrue(fake.saved.isEmpty());f.close()
    }
    @Test fun readerAndCatalogCannotSubmitConflictingMutationsForSameId()=runTest {
        val fake=FakeCollections();val queries=Queries(fake);val gate=CompletableDeferred<Unit>();fake.beforeWrite={gate.await()}
        val f=fixture(fake,queries);advanceUntilIdle();f.controller.enteredReader(publication());advanceUntilIdle()
        f.controller.toggleCatalogLibrary(publication());f.controller.toggleLibrary();runCurrent()
        assertEquals(1,queries.writes);assertTrue(f.controller.reader.value.busy)
        gate.complete(Unit);advanceUntilIdle();assertEquals(true,f.controller.reader.value.inLibrary);f.close()
    }
    @Test fun cancelledMutationClearsBusyAndCanRetryWithoutLateSuccess()=runTest {
        val fake=FakeCollections();val gate=CompletableDeferred<Unit>();fake.beforeWrite={gate.await()}
        val f=fixture(fake);advanceUntilIdle();val request=assertNotNull(f.controller.toggleCatalogLibrary(publication()));runCurrent()
        request.cancel();advanceUntilIdle();assertFalse(f.controller.membership.value.forPublication(id).busy)
        assertEquals(false,f.controller.membership.value.forPublication(id).inLibrary);assertTrue(fake.saved.isEmpty())
        fake.beforeWrite={};f.controller.toggleCatalogLibrary(publication());advanceUntilIdle();gate.complete(Unit);advanceUntilIdle()
        assertEquals(true,f.controller.membership.value.forPublication(id).inLibrary);f.close()
    }
    @Test fun cancelledStaleListCannotOverwriteNewCommittedMembership()=runTest {
        val fake=FakeCollections();val queries=Queries(fake);val f=fixture(fake,queries);advanceUntilIdle()
        val gate=CompletableDeferred<Unit>();var delayed=true
        queries.read={if(delayed){delayed=false;withContext(NonCancellable){gate.await()};LocalStoreResult.Success(emptyList())} else fake.library.list()}
        f.controller.refreshLibrary();runCurrent();f.controller.toggleCatalogLibrary(publication());runCurrent()
        assertEquals(true,f.controller.membership.value.forPublication(id).inLibrary)
        gate.complete(Unit);advanceUntilIdle();assertEquals(true,f.controller.membership.value.forPublication(id).inLibrary);f.close()
    }
    @Test fun malformedSnapshotFailsSafelyWithoutMutation()=runTest {
        val fake=FakeCollections();val queries=Queries(fake);val f=fixture(fake,queries);advanceUntilIdle()
        assertNull(f.controller.toggleCatalogLibrary(publication(title="x".repeat(2049))))
        assertEquals(0,queries.writes);assertNotNull(f.controller.error.value);f.close()
    }
    @Test fun closingDuringLoadLeavesNoInfiniteLoadingState()=runTest {
        val fake=FakeCollections();val queries=Queries(fake);queries.read={awaitCancellation()}
        val f=fixture(fake,queries);runCurrent();f.controller.close();advanceUntilIdle()
        assertFalse(f.controller.membership.value.loading);assertTrue(f.controller.membership.value.mutating.isEmpty())
        f.close()
    }
    @Test fun absentStorageReportsUnavailableRatherThanUnsavedMembership()=runTest {
        val controller=CollectionsController(null,this){1L};advanceUntilIdle()
        val action=controller.membership.value.forPublication(id)
        assertNull(action.inLibrary);assertTrue(action.unavailable);assertFalse(action.enabled)
        assertNull(controller.toggleCatalogLibrary(publication()));controller.close()
    }
    @Test fun alreadyCancelledCallerScopeCannotLeaveMembershipLoading()=runTest {
        val fake=FakeCollections();val owner=ApplicationCollections(fake.library,fake.history,StandardTestDispatcher(testScheduler))
        val caller=CoroutineScope(SupervisorJob().also { it.cancel() }+StandardTestDispatcher(testScheduler))
        val controller=CollectionsController(owner,caller){1L};advanceUntilIdle()
        assertFalse(controller.membership.value.loading);assertTrue(controller.membership.value.unavailable)
        assertTrue(fake.saved.isEmpty());controller.close();owner.close();owner.awaitClosed()
    }
    @Test fun storageFailureRetainsPreviousVisibleMembershipAndDisablesUnknownActions() {
        val saved=LibraryActionState(true,unavailable=true);val unsaved=LibraryActionState(false,unavailable=true)
        assertEquals("Remove from Library",saved.label);assertEquals("Add to Library",unsaved.label)
        assertFalse(saved.enabled);assertFalse(unsaved.enabled)
        assertEquals("Checking Library…",LibraryActionState().label);assertFalse(LibraryActionState().enabled)
        assertEquals("Library unavailable",LibraryActionState(unavailable=true).label)
    }
}
