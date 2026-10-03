// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.core.*

internal data class CollectionList<T>(val entries: List<T> = emptyList(), val loading: Boolean = false, val failed: Boolean = false)
internal data class ReaderLibraryState(val publication: PublicationSnapshot? = null, val inLibrary: Boolean? = null,
    val busy: Boolean = false, val failed: Boolean = false)

/** UI-thread state. No optimistic mutation success; refreshes always query repositories. */
internal class CollectionsController(private val owner: ApplicationCollections?, scope: CoroutineScope, private val clock: () -> Long) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job)
    private val mutableLibrary = MutableStateFlow(CollectionList<LibraryEntry>())
    val library = mutableLibrary.asStateFlow()
    private val mutableHistory = MutableStateFlow(CollectionList<HistoryEntry>())
    val history = mutableHistory.asStateFlow()
    private val mutableReader = MutableStateFlow(ReaderLibraryState())
    val reader = mutableReader.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableConfirm = MutableStateFlow(false)
    val confirmClear = mutableConfirm.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var libraryRequest: Job? = null
    private var historyRequest: Job? = null
    private var membershipRequest: Job? = null
    private var readerGeneration = 0L
    private var closed = false
    init { owner?.let { this.scope.launch { it.changes.collect { change ->
        // A commit may precede this collector's first dispatch. Do not drop it.
        if (change > 0) { refreshLibrary(); refreshHistory(); refreshMembership() }
    } } } }

    private suspend fun <T> safe(action: suspend () -> LocalStoreResult<T>): LocalStoreResult<T> = try { action() }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { LocalStoreResult.Unavailable }

    fun refreshLibrary() {
        if (closed) return
        libraryRequest?.cancel(); mutableLibrary.value = mutableLibrary.value.copy(loading=true)
        libraryRequest = scope.launch {
            val result = safe { owner?.library?.list() ?: LocalStoreResult.Unavailable }
            currentCoroutineContext().ensureActive()
            mutableLibrary.value = when (result) {
                is LocalStoreResult.Success -> CollectionList(result.value)
                else -> mutableLibrary.value.copy(loading=false,failed=true)
            }
        }
    }
    fun refreshHistory() {
        if (closed) return
        historyRequest?.cancel(); mutableHistory.value = mutableHistory.value.copy(loading=true)
        historyRequest = scope.launch {
            val result = safe { owner?.flushHistory(); owner?.history?.listRecent(50) ?: LocalStoreResult.Unavailable }
            currentCoroutineContext().ensureActive()
            mutableHistory.value = when (result) {
                is LocalStoreResult.Success -> CollectionList(result.value)
                else -> mutableHistory.value.copy(loading=false,failed=true)
            }
        }
    }
    fun enteredReader(publication: Publication) {
        if (closed) return
        readerGeneration++
        mutableReader.value = try { ReaderLibraryState(PublicationSnapshot.from(publication)) }
        catch (_: IllegalArgumentException) { ReaderLibraryState(failed=true) }
        refreshMembership()
    }
    fun leftReader() { readerGeneration++; membershipRequest?.cancel(); mutableReader.value=ReaderLibraryState() }
    private fun refreshMembership() {
        val snapshot = reader.value.publication ?: return
        val generation = readerGeneration
        membershipRequest?.cancel()
        membershipRequest = scope.launch {
            val result = safe { owner?.library?.contains(snapshot.id) ?: LocalStoreResult.Unavailable }
            currentCoroutineContext().ensureActive()
            if (generation == readerGeneration) mutableReader.value = mutableReader.value.copy(
                inLibrary = (result as? LocalStoreResult.Success)?.value, failed = result !is LocalStoreResult.Success)
        }
    }
    fun toggleLibrary() {
        val state = reader.value
        val snapshot = state.publication ?: return
        val present = state.inLibrary ?: return
        if (closed || state.busy) return
        val generation = readerGeneration
        mutableReader.value=state.copy(busy=true)
        scope.launch {
            val result = safe { if (present) owner?.library?.remove(snapshot.id) ?: LocalStoreResult.Unavailable
                else owner?.library?.put(snapshot,clock()) ?: LocalStoreResult.Unavailable }
            currentCoroutineContext().ensureActive()
            if (generation == readerGeneration) {
                mutableReader.value = mutableReader.value.copy(busy=false,failed=result !is LocalStoreResult.Success,
                    inLibrary=if(result is LocalStoreResult.Success) !present else present)
            }
            if (result is LocalStoreResult.Success) { mutableError.value=null; owner?.changed() }
            else mutableError.value="The library change could not be saved on this device."
        }
    }
    fun removeLibrary(id: PublicationId) = mutate("The library change could not be saved on this device.") {
        owner?.library?.remove(id) ?: LocalStoreResult.Unavailable
    }
    fun removeHistory(id: PublicationId) = mutate("The history change could not be saved on this device.") {
        owner?.flushHistory()
        owner?.history?.remove(id) ?: LocalStoreResult.Unavailable
    }
    fun requestClearHistory() { if (!closed && !busy.value) mutableConfirm.value=true }
    fun dismissClearHistory() { mutableConfirm.value=false }
    fun confirmClearHistory() {
        if (!confirmClear.value) return
        mutableConfirm.value=false
        mutate("History could not be cleared on this device.") { owner?.flushHistory(); owner?.history?.clear() ?: LocalStoreResult.Unavailable }
    }
    private fun mutate(message: String, action: suspend () -> LocalStoreResult<Unit>) {
        if (closed || busy.value) return
        mutableBusy.value=true
        scope.launch {
            try {
                val result=safe(action); currentCoroutineContext().ensureActive()
                if(result is LocalStoreResult.Success) { mutableError.value=null; owner?.changed() }
                else mutableError.value=message
            } finally { mutableBusy.value=false }
        }
    }
    fun close() { if (!closed) { closed=true; leftReader(); mutableConfirm.value=false; job.cancel() } }
}
