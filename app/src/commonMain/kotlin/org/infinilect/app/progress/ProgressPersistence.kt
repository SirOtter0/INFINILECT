// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.infinilect.core.*
import kotlin.time.Clock

/** Application-owned bounded writer, independent of the disposed Compose/session scope.
 * Calls submit/close on the UI thread. Background jobs retain records/store only, no UI
 * or Context. Every IO operation is bounded to 5s; no network or contents are saved.
 */
internal class ProgressPersistence(
    private val store: ReadingProgressStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val pending = MutableStateFlow<Map<ReadingProgressId, ReadingProgress>>(emptyMap())
    private val recent = MutableStateFlow<Map<ReadingProgressId, ReadingProgress>>(emptyMap())
    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = mutableSaveFailed.asStateFlow()
    private var closed = false
    private val worker = scope.launch {
        try {
            for (ignored in signal) {
                while (true) {
                    val entry = pending.value.entries.firstOrNull() ?: break
                    val progress = entry.value
                    val saved = try { withTimeout(5_000) { store.save(progress) } }
                    catch (_: TimeoutCancellationException) { false }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { false }
                    mutableSaveFailed.value = !saved
                    pending.update { if (it[entry.key] == progress) it - entry.key else it }
                }
            }
        } finally { scope.cancel() }
    }

    suspend fun get(id: ReadingProgressId): ReadingProgress? {
        recent.value[id]?.let { return it }
        return try { withTimeout(5_000) { store.get(id) }?.takeIf { it.id == id } }
        catch (_: TimeoutCancellationException) { null }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
    }

    fun submit(progress: ReadingProgress) {
        if (closed || (recent.value[progress.id]?.updatedAtEpochMillis ?: -1) > progress.updatedAtEpochMillis) return
        if (pending.value.size >= 32 && progress.id !in pending.value) {
            mutableSaveFailed.value = true; return
        }
        recent.update {
            val map = it.toMutableMap()
            map.remove(progress.id); map[progress.id] = progress
            if (map.size > 64) map.remove(map.keys.first())
            map
        }
        pending.update { it + (progress.id to progress) }
        signal.trySend(Unit)
    }

    /** Drain already captured records asynchronously. Never block Android's main thread. */
    fun close() { if (!closed) { closed = true; signal.close() } }
    suspend fun awaitClosed() { worker.join() }
}
