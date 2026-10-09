// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal enum class ApplicationThemeMode { SYSTEM, LIGHT, DARK;
    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
}
internal data class ApplicationAppearanceState(val mode: ApplicationThemeMode = ApplicationThemeMode.SYSTEM, val edited: Boolean = false)
internal interface ApplicationAppearanceStore {
    suspend fun load(): ApplicationThemeMode
    suspend fun save(mode: ApplicationThemeMode): Boolean
}

/** One application-owned worker and one conflated pending preference, never reader state.
 * An edit made during initial loading wins over disk. Closing drains the last choice. */
internal class ApplicationAppearancePreferences(
    private val store: ApplicationAppearanceStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val pending = MutableStateFlow<ApplicationThemeMode?>(null)
    private val mutableState = MutableStateFlow(ApplicationAppearanceState())
    val state = mutableState.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val saveFailed = mutableFailed.asStateFlow()
    private var closed = false
    private val worker = scope.launch {
        try {
            val loaded = try { withTimeout(5_000) { store.load() } }
            catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else ApplicationThemeMode.SYSTEM }
            catch (_: Exception) { ApplicationThemeMode.SYSTEM }
            mutableState.update { if (it.edited) it else it.copy(mode = loaded) }
            for (ignored in signal) {
                while (true) {
                    val choice = pending.value ?: break
                    val saved = try { withTimeout(5_000) { store.save(choice) } }
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
        mutableState.value = ApplicationAppearanceState(mode, edited = true); pending.value = mode; signal.trySend(Unit)
    }
    fun close() { if (!closed) { closed = true; signal.close() } }
    suspend fun awaitClosed() { worker.join() }
}
