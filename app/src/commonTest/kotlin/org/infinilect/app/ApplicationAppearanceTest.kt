// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.ui.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationAppearanceTest {
    private class Store : ApplicationAppearanceStore {
        var value = ApplicationThemeMode.SYSTEM
        var loadGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var success = true
        val writes = mutableListOf<ApplicationThemeMode>()
        override suspend fun load(): ApplicationThemeMode { val snapshot = value; loadGate?.await(); return snapshot }
        override suspend fun save(mode: ApplicationThemeMode): Boolean { saveGate?.await(); if (success) { value = mode; writes += mode }; return success }
    }
    @Test fun systemLightDarkAreIndependentOfReaderAppearance() {
        assertFalse(ApplicationThemeMode.SYSTEM.isDark(false)); assertTrue(ApplicationThemeMode.SYSTEM.isDark(true))
        assertFalse(ApplicationThemeMode.LIGHT.isDark(true)); assertTrue(ApplicationThemeMode.DARK.isDark(false))
    }
    @Test fun savedPreferenceLoadsAndCloseDrainsTheLastChoice() = runTest {
        val store = Store().apply { value = ApplicationThemeMode.DARK }
        val owner = ApplicationAppearancePreferences(store, StandardTestDispatcher(testScheduler)); runCurrent()
        assertEquals(ApplicationThemeMode.DARK, owner.state.value.mode)
        owner.change(ApplicationThemeMode.LIGHT); owner.close(); advanceUntilIdle(); owner.awaitClosed()
        val reopened = ApplicationAppearancePreferences(store, StandardTestDispatcher(testScheduler)); runCurrent()
        assertEquals(ApplicationThemeMode.LIGHT, reopened.state.value.mode)
        reopened.close(); advanceUntilIdle(); reopened.awaitClosed()
    }
    @Test fun editDuringInitialLoadCannotBeOverwrittenEvenWhenChoosingSystem() = runTest {
        for (choice in ApplicationThemeMode.entries) {
            val gate = CompletableDeferred<Unit>()
            val store = Store().apply { value = ApplicationThemeMode.DARK; loadGate = gate }
            val owner = ApplicationAppearancePreferences(store, StandardTestDispatcher(testScheduler)); runCurrent()
            owner.change(choice); gate.complete(Unit); advanceUntilIdle()
            assertEquals(choice, owner.state.value.mode); assertEquals(choice, store.value)
            owner.close(); advanceUntilIdle(); owner.awaitClosed()
        }
    }
    @Test fun rapidEditsUseOnePendingValueAndDoNotLoseNewerChoicesDuringSave() = runTest {
        val gate = CompletableDeferred<Unit>(); val store = Store().apply { saveGate = gate }
        val owner = ApplicationAppearancePreferences(store, StandardTestDispatcher(testScheduler)); runCurrent()
        owner.change(ApplicationThemeMode.DARK); runCurrent()
        repeat(100) { owner.change(ApplicationThemeMode.LIGHT); owner.change(ApplicationThemeMode.SYSTEM) }
        owner.change(ApplicationThemeMode.LIGHT); gate.complete(Unit); owner.close(); advanceUntilIdle(); owner.awaitClosed()
        assertEquals(listOf(ApplicationThemeMode.DARK, ApplicationThemeMode.LIGHT), store.writes)
    }
    @Test fun failedSaveIsVisibleRetryWorksAndClosedOwnerCannotWrite() = runTest {
        val store = Store().apply { success = false }; val owner = ApplicationAppearancePreferences(store, StandardTestDispatcher(testScheduler))
        owner.change(ApplicationThemeMode.DARK); advanceUntilIdle(); assertTrue(owner.saveFailed.value)
        store.success = true; owner.change(ApplicationThemeMode.DARK); advanceUntilIdle(); assertFalse(owner.saveFailed.value)
        owner.close(); advanceUntilIdle(); owner.awaitClosed(); owner.change(ApplicationThemeMode.LIGHT)
        assertEquals(ApplicationThemeMode.DARK, store.value)
    }
}
