// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryOrganizationControllerTest {
    private suspend fun TestScope.fixture(block: suspend (FakeCollections,CollectionsController,PublicationId) -> Unit) {
        val data=FakeCollections();val id=PublicationId(SourceId("original"),"one")
        data.saved[id]=LibraryEntry(PublicationSnapshot(id,"Original",PublicationType.BOOK),1)
        val owner=ApplicationCollections(data.library,data.history,StandardTestDispatcher(testScheduler))
        val controller=CollectionsController(owner,backgroundScope,{2}) {fail("Organization must not invalidate covers or delete files")}
        runCurrent()
        try {block(data,controller,id)} finally {controller.close();owner.close();runCurrent();owner.awaitClosed()}
    }
    @Test fun pendingEditIsNotOptimisticAndDuplicateWritesDoNotQueue()=runTest {fixture {data,c,id ->
        val gate=CompletableDeferred<Unit>();data.beforeWrite={gate.await()}
        val reads=data.libraryReads
        assertNotNull(c.organize(setOf(id),LibraryOrganizationChange(favorite=true)));runCurrent()
        assertTrue(c.busy.value);assertFalse(data.saved.getValue(id).favorite);assertNull(c.organizationResult.value)
        repeat(10) {assertNull(c.organize(setOf(id),LibraryOrganizationChange(state=LibraryReadingState.COMPLETED)))}
        gate.complete(Unit);runCurrent()
        assertFalse(c.busy.value);assertEquals(true,c.organizationResult.value)
        assertTrue(c.library.value.entries.single().favorite);assertEquals(LibraryReadingState.NOT_STARTED,c.library.value.entries.single().readingState)
        assertEquals(reads+1,data.libraryReads)
    } }
    @Test fun failedOrCanceledOrganizationKeepsCommittedMetadataAndAllowsRetry()=runTest {fixture {data,c,id ->
        data.fail=true;c.organize(setOf(id),LibraryOrganizationChange(favorite=true));runCurrent()
        assertEquals(false,c.organizationResult.value);assertFalse(data.saved.getValue(id).favorite);assertFalse(c.busy.value)
        data.fail=false;val gate=CompletableDeferred<Unit>();data.beforeWrite={gate.await()}
        val job=assertNotNull(c.organize(setOf(id),LibraryOrganizationChange(favorite=true)));runCurrent();job.cancel();runCurrent()
        assertFalse(c.busy.value);assertFalse(data.saved.getValue(id).favorite)
        data.beforeWrite={};c.organize(setOf(id),LibraryOrganizationChange(state=LibraryReadingState.PAUSED));runCurrent()
        assertEquals(true,c.organizationResult.value);assertEquals(LibraryReadingState.PAUSED,c.library.value.entries.single().readingState)
    } }
    @Test fun sharedTagChangesRefreshEveryAssignedEntryWithoutSourceInvalidation()=runTest {fixture {data,c,id ->
        val second=id.copy(localId="two");data.saved[second]=data.saved.getValue(id).copy(publication=data.saved.getValue(id).publication.copy(id=second))
        c.createTag("Estudis");runCurrent();val tag=c.tags.value.single()
        c.organize(setOf(id,second),LibraryOrganizationChange(addTags=setOf(tag.id)));runCurrent()
        c.renameTag(tag.id,"Ciència");runCurrent();assertTrue(c.library.value.entries.all {it.tags.single().name=="Ciència"})
        c.deleteTag(tag.id);runCurrent();assertTrue(c.library.value.entries.all {it.tags.isEmpty()});assertEquals(2,data.saved.size)
    } }
}
