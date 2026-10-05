// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EpubSettingsPersistenceTest {
    private class Store : EpubReaderSettingsStore {
        var value = EpubReaderPreferences()
        val writes = mutableListOf<EpubReaderPreferences>()
        var action: suspend () -> Boolean = { true }
        var loadAction: suspend () -> Unit = {}
        override suspend fun load(): EpubReaderPreferences { loadAction(); return value }
        override suspend fun save(preferences: EpubReaderPreferences): Boolean {
            writes.add(preferences)
            if (!action()) return false
            value = preferences; return true
        }
    }
    @Test fun absentStateUsesCurrentDefaultsWithoutWriting() = runTest {
        val store = Store(); val owner = EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        owner.awaitLoaded(); assertEquals(EpubReaderSettings(), owner.settings.value)
        owner.close(); owner.awaitClosed(); assertTrue(store.writes.isEmpty())
    }
    @Test fun rapidChangesCoalesceAndPersistFinalValueWhileReaderRemainsOpen() = runTest {
        val store = Store(); val owner = EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        owner.awaitLoaded(); val lease = owner.claimReader()
        owner.submit(lease, EpubReaderSettings(fontSize = 20)); runCurrent()
        advanceTimeBy(100); owner.submit(lease, EpubReaderSettings(fontSize = 22))
        advanceTimeBy(100); owner.submit(lease, EpubReaderSettings(fontSize = 30, margin = 40, lineSpacingPercent = 200, theme = EpubReadingTheme.DARK))
        assertTrue(store.writes.isEmpty()); advanceTimeBy(100); runCurrent()
        assertEquals(1, store.writes.size); assertEquals(owner.settings.value, store.value.settings)
        owner.close(); owner.awaitClosed()
    }
    @Test fun flushBypassesCoalescingDelayForQuickBackOrOnStop() = runTest {
        val store = Store(); val owner = EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        owner.awaitLoaded(); owner.submit(owner.claimReader(), EpubReaderSettings(fontSize = 26)); runCurrent()
        owner.flush(); runCurrent(); assertEquals(26, store.value.settings.fontSize)
        assertEquals(0, testScheduler.currentTime); owner.close(); owner.awaitClosed()
    }
    @Test fun closeDrainsFinalChoiceAndIsIdempotent() = runTest {
        val store = Store(); val owner = EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        owner.awaitLoaded(); val lease = owner.claimReader()
        owner.submit(lease, EpubReaderSettings(margin = 32)); owner.close(); owner.close(); owner.awaitClosed()
        owner.submit(lease, EpubReaderSettings(margin = 8)); assertEquals(32, store.value.settings.margin)
        assertEquals(1, store.writes.size)
    }
    @Test fun newerPendingValueSurvivesCompletionOfSlowOlderWrite() = runTest {
        val store = Store(); val gate = CompletableDeferred<Unit>()
        store.action = { gate.await(); true }
        val owner = EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        owner.awaitLoaded(); val lease = owner.claimReader()
        owner.submit(lease, EpubReaderSettings(fontSize = 20)); owner.flush(); runCurrent()
        owner.submit(lease, EpubReaderSettings(fontSize = 28)); gate.complete(Unit)
        owner.close(); owner.awaitClosed(); assertEquals(28, store.value.settings.fontSize)
        assertEquals(2, store.writes.size)
    }
    @Test fun oldReaderLeaseCannotOverwriteNewReaderPreferences() = runTest {
        val store = Store(); val owner = EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        owner.awaitLoaded(); val old = owner.claimReader()
        owner.submit(old, EpubReaderSettings(margin = 32)); val current = owner.claimReader()
        assertEquals(32, owner.settings.value.margin)
        owner.submit(current, EpubReaderSettings(margin = 40)); owner.submit(old, EpubReaderSettings(margin = 8))
        owner.close(); owner.awaitClosed(); assertEquals(40, store.value.settings.margin)
    }
    @Test fun failedDurableSaveReportsFailureUntilLaterSuccessfulChoice() = runTest {
        val store = Store(); store.action = { false }
        val owner = EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        owner.awaitLoaded(); val lease = owner.claimReader()
        owner.submit(lease, EpubReaderSettings(fontSize = 20)); owner.flush(); runCurrent()
        assertTrue(owner.saveFailed.value); assertEquals(EpubReaderSettings(), store.value.settings)
        store.action = { true }; owner.submit(lease, EpubReaderSettings(fontSize = 22)); owner.flush(); runCurrent()
        assertFalse(owner.saveFailed.value); assertEquals(22, store.value.settings.fontSize)
        owner.close(); owner.awaitClosed()
    }
    @Test fun loadFailureUsesDefaultsAndDoesNotCrashReaderOwner() = runTest {
        val store = Store(); store.loadAction = { error("private path") }
        val owner = EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        owner.awaitLoaded(); assertEquals(EpubReaderSettings(), owner.settings.value)
        owner.close(); owner.awaitClosed()
    }
    @Test fun saveTimeoutIsBoundedAndCloseCompletesWithoutClaimingDurability() = runTest {
        val store = Store(); store.action = { awaitCancellation() }
        val owner = EpubSettingsPersistence(store, StandardTestDispatcher(testScheduler))
        owner.awaitLoaded(); owner.submit(owner.claimReader(), EpubReaderSettings(fontSize = 24))
        owner.close(); owner.awaitClosed(); assertTrue(owner.saveFailed.value)
        assertEquals(EpubReaderSettings(), store.value.settings)
        assertEquals(5000, testScheduler.currentTime)
    }
}
