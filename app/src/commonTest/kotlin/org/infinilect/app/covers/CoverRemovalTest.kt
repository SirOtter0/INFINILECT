// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.covers
import kotlinx.coroutines.test.*
import org.infinilect.app.collections.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoverRemovalTest {
    @Test fun failedRemovalKeepsThumbnailAndCommittedRemovalInvalidatesIt()=runTest {
        val data=FakeCollections();val id=PublicationId(SourceId("original"),"one")
        val snapshot=PublicationSnapshot(id,"Original",PublicationType.BOOK)
        data.saved[id]=LibraryEntry(snapshot,1)
        val owner=ApplicationCollections(data.library,data.history,StandardTestDispatcher(testScheduler))
        val removed=mutableListOf<PublicationId>();val controller=CollectionsController(owner,this,{1L}) { removed+=it }
        advanceUntilIdle();data.fail=true;controller.removeLibrary(id);advanceUntilIdle();assertTrue(removed.isEmpty());assertTrue(id in data.saved)
        data.fail=false;controller.refreshLibrary();advanceUntilIdle();controller.removeLibrary(id);advanceUntilIdle()
        assertEquals(listOf(id),removed);assertFalse(id in data.saved)
        controller.close();owner.close();owner.awaitClosed()
    }
    @Test fun historyRemovalAndClearInvalidateOnlyAfterRepositorySuccess()=runTest {
        val data=FakeCollections();val ids=(0..2).map { PublicationId(SourceId("original"),"$it") }
        ids.forEach {data.opened[it]=HistoryEntry(PublicationSnapshot(it,"Original",PublicationType.BOOK),1)}
        val owner=ApplicationCollections(data.library,data.history,StandardTestDispatcher(testScheduler))
        val removed=mutableListOf<PublicationId>();val controller=CollectionsController(owner,this,{1L}) {removed+=it}
        controller.refreshHistory();advanceUntilIdle();controller.removeHistory(ids[0]);advanceUntilIdle();assertEquals(listOf(ids[0]),removed)
        data.fail=true;controller.requestClearHistory();controller.confirmClearHistory();advanceUntilIdle();assertEquals(1,removed.size)
        data.fail=false;controller.refreshHistory();advanceUntilIdle();controller.requestClearHistory();controller.confirmClearHistory();advanceUntilIdle()
        assertEquals(ids.toSet(),removed.toSet());assertTrue(data.opened.isEmpty())
        controller.close();owner.close();owner.awaitClosed()
    }
}
