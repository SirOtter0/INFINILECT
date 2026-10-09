// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySelectionTest {
    private fun populate(fake: FakeCollections, count: Int = 3): List<PublicationId> = List(count) { i ->
        val id = PublicationId(SourceId("original-selection"), "book-$i")
        val p = PublicationSnapshot(id, "Original $i", PublicationType.BOOK)
        fake.saved[id] = LibraryEntry(p, 1); fake.opened[id] = HistoryEntry(p, 2); id
    }
    private suspend fun TestScope.withController(count: Int = 3, block: suspend (FakeCollections, CollectionsController, List<PublicationId>, MutableList<PublicationId>) -> Unit) {
        val fake = FakeCollections(); val ids = populate(fake, count); val retired = mutableListOf<PublicationId>()
        val owner = ApplicationCollections(fake.library, fake.history, StandardTestDispatcher(testScheduler))
        val controller = CollectionsController(owner, this, { 3L }, { retired += it })
        advanceUntilIdle()
        try { block(fake, controller, ids, retired) }
        finally { controller.close(); owner.close(); owner.awaitClosed() }
    }
    @Test fun selectionStartsWithOneThenTapsToggleWithoutRepositoryWrites() = runTest { withController { fake, c, ids, _ ->
        c.select(ids[1]); assertEquals(setOf(ids[1]), c.selection.value)
        c.toggleSelection(ids[0]); assertEquals(setOf(ids[0], ids[1]), c.selection.value)
        c.toggleSelection(ids[1]); assertEquals(setOf(ids[0]), c.selection.value)
        c.clearSelection(); assertTrue(c.selection.value.isEmpty()); assertTrue(fake.libraryRemovals.isEmpty())
        assertEquals(3, fake.saved.size); assertEquals(3, fake.opened.size)
    } }
    @Test fun selectAllUsesCurrentLibraryOnlyAndClearDoesNotMutateStorage() = runTest { withController { fake, c, ids, _ ->
        c.select(PublicationId(SourceId("unrelated"), ids[0].localId)); assertTrue(c.selection.value.isEmpty())
        c.selectAll(); assertEquals(ids.toSet(), c.selection.value)
        c.clearSelection(); assertTrue(c.selection.value.isEmpty()); assertEquals(3, fake.saved.size)
    } }
    @Test fun selectionIsBoundedByExistingThousandEntryLibraryLimit() = runTest { withController(1005) { _, c, ids, _ ->
        c.selectAll(); assertEquals(1000, c.selection.value.size)
        c.select(ids.last()); assertEquals(1000, c.selection.value.size)
    } }
    @Test fun refreshPrunesRemovedIdsButFailedRefreshDoesNotLoseSelection() = runTest { withController { fake, c, ids, _ ->
        c.selectAll(); fake.fail = true; c.refreshLibrary(); advanceUntilIdle(); assertEquals(ids.toSet(), c.selection.value)
        fake.fail = false; fake.saved.remove(ids[1]); c.refreshLibrary(); advanceUntilIdle()
        assertEquals(setOf(ids[0], ids[2]), c.selection.value)
    } }
    @Test fun batchOnlyRemovesLibraryAndRetiresCommittedArtwork() = runTest { withController { fake, c, ids, retired ->
        c.selectAll(); c.removeSelectedLibrary(); advanceUntilIdle()
        assertTrue(fake.saved.isEmpty()); assertEquals(ids.toSet(), fake.opened.keys)
        assertEquals(ids.toSet(), retired.toSet()); assertTrue(c.selection.value.isEmpty())
        assertEquals(BatchLibraryRemoval(ids.toSet(), emptySet()), c.batchRemoval.value)
    } }
    @Test fun partialFailureKeepsFailedItemsSelectedAndRetryReconcilesDisk() = runTest { withController { fake, c, ids, retired ->
        fake.failedLibraryRemovals += ids[1]; c.selectAll(); c.removeSelectedLibrary(); advanceUntilIdle()
        assertEquals(setOf(ids[1]), fake.saved.keys); assertEquals(setOf(ids[1]), c.selection.value)
        assertEquals(setOf(ids[0], ids[2]), retired.toSet()); assertEquals(3, fake.opened.size)
        assertEquals(BatchLibraryRemoval(setOf(ids[0], ids[2]), setOf(ids[1])), c.batchRemoval.value)
        assertEquals("2 removed; 1 could not be removed. Remaining items are kept. Try again.", c.error.value)
        fake.failedLibraryRemovals.clear(); c.removeSelectedLibrary(); advanceUntilIdle()
        assertTrue(fake.saved.isEmpty()); assertNull(c.error.value); assertTrue(c.selection.value.isEmpty())
    } }
    @Test fun pendingBatchIsSequentialAndRepeatedActionsDoNotQueue() = runTest { withController { fake, c, ids, _ ->
        val gate = CompletableDeferred<Unit>(); var active = 0; var maximum = 0; var calls = 0
        fake.beforeWrite = { active++; maximum = maxOf(maximum, active); calls++; try { gate.await() } finally { active-- } }
        c.selectAll(); assertNotNull(c.removeSelectedLibrary()); runCurrent()
        assertTrue(c.busy.value); assertEquals(3, fake.saved.size); assertNull(c.batchRemoval.value)
        repeat(20) { assertNull(c.removeSelectedLibrary()); assertNull(c.removeLibrary(ids[0])) }
        assertEquals(1, calls); gate.complete(Unit); advanceUntilIdle()
        assertEquals(3, calls); assertEquals(1, maximum); assertFalse(c.busy.value)
    } }
    @Test fun confirmationSnapshotCannotRemoveAnotherNewSelection() = runTest { withController { fake, c, ids, _ ->
        c.select(ids[0]); val confirmed = c.selection.value.toSet(); c.clearSelection(); c.select(ids[1])
        c.removeSelectedLibrary(confirmed); advanceUntilIdle()
        assertEquals(setOf(ids[1], ids[2]), fake.saved.keys); assertEquals(setOf(ids[1]), c.selection.value)
    } }
    @Test fun cancelledBatchReconcilesCommittedEntriesAndNeverClaimsPendingSuccess() = runTest { withController { fake, c, ids, retired ->
        val gate = CompletableDeferred<Unit>(); var writes = 0
        fake.beforeWrite = { if (++writes == 2) gate.await() }
        c.selectAll(); val request = assertNotNull(c.removeSelectedLibrary()); runCurrent()
        assertEquals(setOf(ids[1], ids[2]), fake.saved.keys); assertTrue(c.busy.value)
        request.cancel(); advanceUntilIdle()
        assertFalse(c.busy.value); assertEquals(setOf(ids[1], ids[2]), c.selection.value)
        assertEquals(listOf(ids[0]), retired); assertNull(c.batchRemoval.value); assertEquals(3, fake.opened.size)
    } }
    @Test fun closedControllerRetiresSelectionAndRejectsFurtherWork() = runTest { withController { fake, c, _, _ ->
        c.selectAll(); c.close(); assertTrue(c.selection.value.isEmpty()); assertNull(c.removeSelectedLibrary())
        c.selectAll(); assertTrue(c.selection.value.isEmpty()); assertEquals(3, fake.saved.size)
    } }
    @Test fun allFailuresLeaveLibraryHistoryAndArtworkUntouched() = runTest { withController { fake, c, ids, retired ->
        fake.failedLibraryRemovals += ids; c.selectAll(); c.removeSelectedLibrary(); advanceUntilIdle()
        assertEquals(ids.toSet(), fake.saved.keys); assertEquals(ids.toSet(), fake.opened.keys)
        assertEquals(ids.toSet(), c.selection.value); assertTrue(retired.isEmpty())
    } }
}
