// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsControllerTest {
    private val id=PublicationId(SourceId("fixture"),"1")
    private fun publication(key: PublicationId=id)=Publication(key,"Title",PublicationType.BOOK)
    private fun TestScope.owner(fake: FakeCollections,release: suspend () -> Unit = {})=
        ApplicationCollections(fake.library,fake.history,StandardTestDispatcher(testScheduler),release)
    private fun TestScope.controller(owner: ApplicationCollections?)=CollectionsController(owner,this){1L}
    @Test fun initialEmptyStatesAndAddRemoveAreRepositoryDriven()=runTest {
        val fake=FakeCollections();val owner=owner(fake);val c=controller(owner)
        c.refreshLibrary();c.refreshHistory();advanceUntilIdle();assertTrue(c.library.value.entries.isEmpty());assertFalse(c.library.value.failed)
        c.enteredReader(publication());advanceUntilIdle();assertEquals(false,c.reader.value.inLibrary)
        c.toggleLibrary();advanceUntilIdle();assertEquals(true,c.reader.value.inLibrary);assertEquals(1,c.library.value.entries.size)
        c.toggleLibrary();advanceUntilIdle();assertEquals(false,c.reader.value.inLibrary);assertTrue(c.library.value.entries.isEmpty())
        c.close();owner.close();owner.awaitClosed()
    }
    @Test fun failedLibraryMutationNeverLooksSavedAndRetryCanRecover()=runTest {
        val fake=FakeCollections();val owner=owner(fake);val c=controller(owner);c.enteredReader(publication());advanceUntilIdle()
        fake.fail=true;c.toggleLibrary();advanceUntilIdle();assertEquals(false,c.reader.value.inLibrary);assertTrue(c.reader.value.failed);assertTrue(fake.saved.isEmpty());assertNotNull(c.error.value)
        fake.fail=false;c.enteredReader(publication());advanceUntilIdle();c.toggleLibrary();advanceUntilIdle()
        assertEquals(true,c.reader.value.inLibrary);assertNull(c.error.value);c.close();owner.close();owner.awaitClosed()
    }
    @Test fun pendingMutationIsNotOptimisticallyShownAsDurable()=runTest {
        val fake=FakeCollections();val gate=CompletableDeferred<Unit>();fake.beforeWrite={gate.await()}
        val owner=owner(fake);val c=controller(owner);c.enteredReader(publication());advanceUntilIdle()
        c.toggleLibrary();runCurrent();assertTrue(c.reader.value.busy);assertEquals(false,c.reader.value.inLibrary);assertTrue(fake.saved.isEmpty())
        gate.complete(Unit);advanceUntilIdle();assertEquals(true,c.reader.value.inLibrary);c.close();owner.close();owner.awaitClosed()
    }
    @Test fun lateMutationCannotSetNewReadersMembership()=runTest {
        val fake=FakeCollections();val gate=CompletableDeferred<Unit>();fake.beforeWrite={gate.await()}
        val owner=owner(fake);val c=controller(owner);c.enteredReader(publication());advanceUntilIdle();c.toggleLibrary();runCurrent()
        val other=id.copy(localId="2");c.enteredReader(publication(other));runCurrent();gate.complete(Unit);advanceUntilIdle()
        assertEquals(other,c.reader.value.publication?.id);assertEquals(false,c.reader.value.inLibrary)
        assertTrue(id in fake.saved);assertFalse(other in fake.saved);c.close();owner.close();owner.awaitClosed()
    }
    @Test fun clearRequiresConfirmationAndKeepsLibrary()=runTest {
        val fake=FakeCollections();val s=PublicationSnapshot.from(publication());fake.saved[id]=LibraryEntry(s,1);fake.opened[id]=HistoryEntry(s,2)
        val owner=owner(fake);val c=controller(owner)
        c.confirmClearHistory();advanceUntilIdle();assertEquals(1,fake.opened.size)
        c.requestClearHistory();assertTrue(c.confirmClear.value);c.dismissClearHistory();assertFalse(c.confirmClear.value);assertEquals(1,fake.opened.size)
        c.requestClearHistory();c.confirmClearHistory();advanceUntilIdle();assertTrue(fake.opened.isEmpty());assertEquals(1,fake.saved.size)
        c.close();owner.close();owner.awaitClosed()
    }
    @Test fun historyRefreshAndIndividualRemoval()=runTest {
        val fake=FakeCollections();val owner=owner(fake);val c=controller(owner)
        owner.recordOpened(publication(),1);advanceUntilIdle();assertEquals(1,c.history.value.entries.size)
        c.removeHistory(id);advanceUntilIdle();assertTrue(c.history.value.entries.isEmpty());c.close();owner.close();owner.awaitClosed()
    }
    @Test fun failedClearPreservesEntriesAndShowsFixedError()=runTest {
        val fake=FakeCollections();fake.opened[id]=HistoryEntry(PublicationSnapshot.from(publication()),1)
        val owner=owner(fake);val c=controller(owner);fake.fail=true;c.requestClearHistory();c.confirmClearHistory();advanceUntilIdle()
        assertEquals(1,fake.opened.size);assertEquals("History could not be cleared on this device.",c.error.value);c.close();owner.close();owner.awaitClosed()
    }
    @Test fun unavailableRepositoriesFailGracefullyWithoutFakeEmptySuccess()=runTest {
        val c=controller(null);c.refreshLibrary();c.refreshHistory();c.enteredReader(publication());advanceUntilIdle()
        assertTrue(c.library.value.failed);assertTrue(c.history.value.failed);assertTrue(c.reader.value.failed);assertNull(c.reader.value.inLibrary);c.close()
    }
    @Test fun queuedOpenCannotReappearAfterConfirmedClear()=runTest {
        val fake=FakeCollections();val gate=CompletableDeferred<Unit>();fake.beforeWrite={gate.await()}
        val owner=owner(fake);val c=controller(owner);owner.recordOpened(publication(),1)
        c.requestClearHistory();c.confirmClearHistory();runCurrent();gate.complete(Unit);advanceUntilIdle()
        assertTrue(fake.opened.isEmpty());c.close();owner.close();owner.awaitClosed()
    }
    @Test fun ownerCloseDrainsCapturedHistoryAndReleasesExactlyOnce()=runTest {
        val fake=FakeCollections();var released=0;val owner=owner(fake){released++}
        owner.recordOpened(publication(),1);owner.close();owner.close();owner.awaitClosed()
        assertEquals(1,fake.opened.size);assertEquals(1,released)
    }
    @Test fun historyFailureIsVisibleAndLaterCommittedOpenClearsIt()=runTest {
        val fake=FakeCollections();val owner=owner(fake);fake.fail=true;owner.recordOpened(publication(),1);advanceUntilIdle()
        assertTrue(owner.historyFailed.value);assertTrue(fake.opened.isEmpty())
        fake.fail=false;owner.recordOpened(publication(),2);advanceUntilIdle();assertFalse(owner.historyFailed.value)
        assertEquals(2L,fake.opened[id]?.lastOpenedAtEpochMillis);owner.close();owner.awaitClosed()
    }
    @Test fun repeatedToggleDuringPendingWriteDoesNotSubmitTwice()=runTest {
        val fake=FakeCollections();val gate=CompletableDeferred<Unit>();var writes=0;fake.beforeWrite={writes++;gate.await()}
        val owner=owner(fake);val c=controller(owner);c.enteredReader(publication());advanceUntilIdle()
        c.toggleLibrary();c.toggleLibrary();runCurrent();assertEquals(1,writes)
        gate.complete(Unit);advanceUntilIdle();assertEquals(true,c.reader.value.inLibrary)
        c.close();owner.close();owner.awaitClosed()
    }
}
