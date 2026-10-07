// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PageSettingsPersistenceTest {
    private class Store : PageReaderSettingsStore {
        var value=PageReaderPreferences();val writes=mutableListOf<PageReaderPreferences>()
        var action:suspend ()->Boolean={true}
        override suspend fun load()=value
        override suspend fun save(preferences:PageReaderPreferences):Boolean{writes.add(preferences);if(!action())return false;value=preferences;return true}
    }
    @Test fun defaultsDoNotCreateRecord()=runTest {
        val store=Store();val owner=PageSettingsPersistence(store,StandardTestDispatcher(testScheduler))
        owner.awaitLoaded();assertEquals(PageReadingMode.PAGED_LTR,owner.settings.value.mode);owner.close();owner.awaitClosed();assertTrue(store.writes.isEmpty())
    }
    @Test fun rapidModeChangesCoalesceToFinalChoice()=runTest {
        val store=Store();val owner=PageSettingsPersistence(store,StandardTestDispatcher(testScheduler))
        owner.awaitLoaded();val lease=owner.claimReader()
        owner.submit(lease,PageReaderSettings(PageReadingMode.PAGED_RTL));runCurrent();advanceTimeBy(100)
        owner.submit(lease,PageReaderSettings(PageReadingMode.VERTICAL));advanceTimeBy(100)
        owner.submit(lease,PageReaderSettings(PageReadingMode.WEBTOON));advanceTimeBy(100);runCurrent()
        assertEquals(1,store.writes.size);assertEquals(PageReadingMode.WEBTOON,store.value.settings.mode);owner.close();owner.awaitClosed()
    }
    @Test fun immediateBackAndRepeatedCloseDrainLatestChoice()=runTest {
        val store=Store();val owner=PageSettingsPersistence(store,StandardTestDispatcher(testScheduler))
        owner.awaitLoaded();owner.submit(owner.claimReader(),PageReaderSettings(PageReadingMode.VERTICAL));owner.flush();runCurrent()
        assertEquals(PageReadingMode.VERTICAL,store.value.settings.mode);assertEquals(0,testScheduler.currentTime)
        owner.close();owner.close();owner.awaitClosed()
    }
    @Test fun oldReaderLeaseCannotOverwriteNewReaderSettings()=runTest {
        val store=Store();val owner=PageSettingsPersistence(store,StandardTestDispatcher(testScheduler))
        owner.awaitLoaded();val old=owner.claimReader();owner.submit(old,PageReaderSettings(PageReadingMode.VERTICAL))
        val current=owner.claimReader();owner.submit(current,PageReaderSettings(PageReadingMode.WEBTOON));owner.submit(old,PageReaderSettings())
        owner.close();owner.awaitClosed();assertEquals(PageReadingMode.WEBTOON,store.value.settings.mode)
    }
    @Test fun slowOlderSaveCannotConsumeNewerPendingChoice()=runTest {
        val store=Store();val gate=CompletableDeferred<Unit>();store.action={gate.await();true}
        val owner=PageSettingsPersistence(store,StandardTestDispatcher(testScheduler));owner.awaitLoaded();val lease=owner.claimReader()
        owner.submit(lease,PageReaderSettings(PageReadingMode.VERTICAL));owner.flush();runCurrent()
        owner.submit(lease,PageReaderSettings(PageReadingMode.WEBTOON));gate.complete(Unit);owner.close();owner.awaitClosed()
        assertEquals(PageReadingMode.WEBTOON,store.value.settings.mode);assertEquals(2,store.writes.size)
    }
    @Test fun failedSaveCannotMasqueradeAsDurableAndLaterSuccessClearsFailure()=runTest {
        val store=Store();store.action={false};val owner=PageSettingsPersistence(store,StandardTestDispatcher(testScheduler));owner.awaitLoaded();val lease=owner.claimReader()
        owner.submit(lease,PageReaderSettings(PageReadingMode.VERTICAL));owner.flush();runCurrent();assertTrue(owner.saveFailed.value);assertEquals(PageReaderSettings(),store.value.settings)
        store.action={true};owner.submit(lease,PageReaderSettings(PageReadingMode.WEBTOON));owner.flush();runCurrent();assertFalse(owner.saveFailed.value)
        owner.close();owner.awaitClosed();assertEquals(PageReadingMode.WEBTOON,store.value.settings.mode)
    }
    @Test fun stalledSaveTimesOutAndCloseDoesNotLeakWorker()=runTest {
        val store=Store();store.action={awaitCancellation()};val owner=PageSettingsPersistence(store,StandardTestDispatcher(testScheduler))
        owner.awaitLoaded();owner.submit(owner.claimReader(),PageReaderSettings(PageReadingMode.VERTICAL));owner.close();owner.awaitClosed()
        assertTrue(owner.saveFailed.value);assertEquals(5000,testScheduler.currentTime);assertEquals(PageReaderSettings(),store.value.settings)
    }
}
