// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal enum class ApplicationThemeMode { SYSTEM, LIGHT, DARK;
    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
}
internal data class ApplicationAppearanceState(val mode: ApplicationThemeMode = ApplicationThemeMode.SYSTEM, val edited: Boolean = false, val profile: LocalProfile = LocalProfile(), val profileEdited: Boolean = false, val loaded: Boolean = false, val discovery: org.infinilect.app.discovery.DiscoveryPreferences = org.infinilect.app.discovery.DiscoveryPreferences(), val discoveryEdited: Boolean = false)
internal interface ApplicationAppearanceStore {
    suspend fun load(): ApplicationThemeMode
    suspend fun save(mode: ApplicationThemeMode): Boolean
    suspend fun loadPreferences(): ApplicationPreferences = ApplicationPreferences(load())
    suspend fun savePreferences(value: ApplicationPreferences): Boolean = save(value.mode)
}

/** One application-owned worker and one conflated pending preference, never reader state.
 * An edit made during initial loading wins over disk. Closing drains the last choice. */
internal class ApplicationAppearancePreferences(
    private val store: ApplicationAppearanceStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val pending = MutableStateFlow<ApplicationPreferences?>(null)
    private val mutableState = MutableStateFlow(ApplicationAppearanceState())
    val state = mutableState.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val saveFailed = mutableFailed.asStateFlow()
    private var closed = false
    private val worker = scope.launch {
        try {
            val loaded = try { withTimeout(5_000) { store.loadPreferences() } }
            catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else ApplicationPreferences() }
            catch (_: Exception) { ApplicationPreferences() }
            mutableState.update { it.copy(mode = if (it.edited) it.mode else loaded.mode,
                profile = if (it.profileEdited) it.profile else loaded.profile, discovery = if (it.discoveryEdited) it.discovery else loaded.discovery, loaded = true) }
            if (pending.value != null) pending.value = preferences()
            for (ignored in signal) {
                while (true) {
                    val choice = pending.value ?: break
                    val saved = try { withTimeout(5_000) { store.savePreferences(choice) } }
                    catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else false }
                    catch (_: Exception) { false }
                    mutableFailed.value = !saved
                    pending.compareAndSet(choice, null)
                }
            }
        } finally { scope.cancel() }
    }
    fun change(mode: ApplicationThemeMode) {
        if (closed) return
        mutableState.value = state.value.copy(mode = mode, edited = true); enqueue()
    }
    private fun preferences() = ApplicationPreferences(state.value.mode, state.value.profile, state.value.discovery)
    private fun enqueue() { pending.value = preferences(); signal.trySend(Unit) }
    fun changeProfile(profile: LocalProfile) {
        if (closed) return
        mutableState.value = state.value.copy(profile = profile, profileEdited = true); enqueue()
    }
    fun changeDiscovery(value: org.infinilect.app.discovery.DiscoveryPreferences) {
        if (closed) return
        mutableState.value = state.value.copy(discovery = value, discoveryEdited = true); enqueue()
    }
    fun retry() { if (!closed) enqueue() }
    fun close() { if (!closed) { closed = true; signal.close() } }
    suspend fun awaitClosed() { worker.join() }
}
