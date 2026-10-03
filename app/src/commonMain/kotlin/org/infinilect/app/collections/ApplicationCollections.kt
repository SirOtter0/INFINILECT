// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.infinilect.core.*

/** Local metadata owner, separate from source/cache/progress owners. UI-thread lifecycle.
 * Captured successful-open snapshots drain independently of disposed UI sessions.
 */
internal class ApplicationCollections(
    val library: LibraryRepository,
    val history: ReadingHistoryRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val release: suspend () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private sealed interface HistoryAction {
        data class Opened(val snapshot: PublicationSnapshot, val time: Long) : HistoryAction
        data class Barrier(val done: CompletableDeferred<Unit>) : HistoryAction
    }
    private val opened = Channel<HistoryAction>(32)
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private val mutableHistoryFailed = MutableStateFlow(false)
    val historyFailed = mutableHistoryFailed.asStateFlow()
    private var closed = false
    private val worker = scope.launch {
        try {
            for (action in opened) {
                if (action is HistoryAction.Barrier) { action.done.complete(Unit); continue }
                action as HistoryAction.Opened
                val result = try { history.recordOpened(action.snapshot,action.time) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { LocalStoreResult.Unavailable }
                mutableHistoryFailed.value = result !is LocalStoreResult.Success
                if (result is LocalStoreResult.Success) changed()
            }
        } finally {
            try { release() } catch (_: Exception) { mutableHistoryFailed.value = true }
            finally { scope.cancel() }
        }
    }
    fun recordOpened(publication: Publication, time: Long) {
        if (closed) return
        val snapshot = try { PublicationSnapshot.from(publication) } catch (_: IllegalArgumentException) {
            mutableHistoryFailed.value = true; return
        }
        if (!opened.trySend(HistoryAction.Opened(snapshot,time)).isSuccess) mutableHistoryFailed.value = true
    }
    /** A clear/remove/list after Back includes all previously captured opens. */
    suspend fun flushHistory() {
        if (closed) { worker.join(); return }
        val barrier = CompletableDeferred<Unit>()
        try { opened.send(HistoryAction.Barrier(barrier)); barrier.await() }
        catch (error: CancellationException) { throw error }
        catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) { worker.join() }
    }
    fun changed() { mutableChanges.update { it + 1 } }
    fun close() { if (!closed) { closed = true; opened.close() } }
    suspend fun awaitClosed() { worker.join() }
}
