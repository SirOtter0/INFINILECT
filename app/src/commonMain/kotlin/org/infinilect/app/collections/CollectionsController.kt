// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.core.*

internal data class CollectionList<T>(val entries: List<T> = emptyList(), val loading: Boolean = false, val failed: Boolean = false)
internal data class ReaderLibraryState(val publication: PublicationSnapshot? = null, val inLibrary: Boolean? = null,
    val busy: Boolean = false, val failed: Boolean = false, val unavailable: Boolean = false)
internal data class LibraryActionState(val inLibrary: Boolean? = null, val busy: Boolean = false,
    val unavailable: Boolean = false) {
    val enabled: Boolean get() = inLibrary != null && !busy && !unavailable
    val label: String get() = when {
        busy -> "Saving…"
        inLibrary == true -> "Remove from Library"
        inLibrary == false -> "Add to Library"
        unavailable -> "Library unavailable"
        else -> "Checking Library…"
    }
}
internal data class CatalogLibraryState(val saved: Set<PublicationId> = emptySet(), val known: Boolean = false,
    val loading: Boolean = true, val unavailable: Boolean = false, val mutating: Set<PublicationId> = emptySet()) {
    fun forPublication(id: PublicationId) = LibraryActionState(
        if (known) id in saved else null, id in mutating, unavailable)
}

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
    private val mutableMembership = MutableStateFlow(CatalogLibraryState())
    val membership = mutableMembership.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableConfirm = MutableStateFlow(false)
    val confirmClear = mutableConfirm.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var libraryRequest: Job? = null
    private var historyRequest: Job? = null
    private var libraryGeneration = 0L
    private val libraryMutations = mutableMapOf<PublicationId, Job>()
    private var readerMutationFailed = false
    private var closed = false
    init {
        refreshLibrary()
        owner?.let { this.scope.launch { it.changes.collect { change ->
        // A commit may precede this collector's first dispatch. Do not drop it.
        if (change > 0) { refreshLibrary(); refreshHistory() }
    } } } }

    private fun updateMembership(value: CatalogLibraryState) {
        mutableMembership.value = value
        val snapshot = reader.value.publication ?: return
        val action = value.forPublication(snapshot.id)
        mutableReader.value = reader.value.copy(inLibrary=action.inLibrary, busy=action.busy,
            failed=action.unavailable || readerMutationFailed, unavailable=action.unavailable)
    }

    private suspend fun <T> safe(action: suspend () -> LocalStoreResult<T>): LocalStoreResult<T> = try { action() }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { LocalStoreResult.Unavailable }

    fun refreshLibrary() {
        if (closed) return
        val generation = ++libraryGeneration
        libraryRequest?.cancel(); mutableLibrary.value = mutableLibrary.value.copy(loading=true)
        updateMembership(membership.value.copy(loading=true))
        libraryRequest = scope.launch {
            try {
                val result = safe { owner?.library?.list() ?: LocalStoreResult.Unavailable }
                currentCoroutineContext().ensureActive()
                if (generation != libraryGeneration) return@launch
                mutableLibrary.value = when (result) {
                    is LocalStoreResult.Success -> CollectionList(result.value)
                    else -> mutableLibrary.value.copy(loading=false,failed=true)
                }
                updateMembership(when (result) {
                    is LocalStoreResult.Success -> membership.value.copy(saved=result.value.map { it.publication.id }.toSet(),
                        known=true,loading=false,unavailable=false)
                    else -> membership.value.copy(loading=false,unavailable=true)
                })
            } finally {
                finishLibraryRefresh(generation)
            }
        }.also { request -> request.invokeOnCompletion { finishLibraryRefresh(generation) } }
    }
    private fun finishLibraryRefresh(generation: Long) {
        // The caller's scope may already be cancelled, so launch/finally may never run.
        if (generation == libraryGeneration && membership.value.loading) {
            mutableLibrary.value=mutableLibrary.value.copy(loading=false)
            updateMembership(membership.value.copy(loading=false,unavailable=true))
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
        readerMutationFailed=false
        mutableReader.value = try { ReaderLibraryState(PublicationSnapshot.from(publication)) }
        catch (_: IllegalArgumentException) { ReaderLibraryState(failed=true) }
        updateMembership(membership.value)
        refreshLibrary()
    }
    fun leftReader() { readerMutationFailed=false; mutableReader.value=ReaderLibraryState() }
    fun toggleLibrary() {
        val state = reader.value
        val snapshot = state.publication ?: return
        val action = membership.value.forPublication(snapshot.id)
        if (action.enabled) mutateLibrary(snapshot.id, snapshot, action.inLibrary == true)
    }
    /** A catalog action stores only a bounded metadata snapshot. It never opens a source. */
    fun toggleCatalogLibrary(publication: Publication): Job? {
        val action=membership.value.forPublication(publication.id)
        if (closed || !action.enabled) return null
        val snapshot=try { PublicationSnapshot.from(publication) } catch (_: IllegalArgumentException) {
            mutableError.value="The library change could not be saved on this device.";return null
        }
        return mutateLibrary(snapshot.id,snapshot,action.inLibrary == true)
    }
    fun removeLibrary(id: PublicationId): Job? {
        if (!membership.value.forPublication(id).enabled) return null
        return mutateLibrary(id,null,remove=true)
    }
    private fun mutateLibrary(id: PublicationId, snapshot: PublicationSnapshot?, remove: Boolean): Job? {
        if (closed || id in membership.value.mutating) return null
        if (reader.value.publication?.id == id) readerMutationFailed=false
        updateMembership(membership.value.copy(mutating=membership.value.mutating+id))
        // Install the job before starting it, including with an unconfined caller scope.
        val request=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=safe { if (remove) owner?.library?.remove(id) ?: LocalStoreResult.Unavailable
                    else owner?.library?.put(checkNotNull(snapshot),clock()) ?: LocalStoreResult.Unavailable }
                currentCoroutineContext().ensureActive()
                if (closed) return@launch
                if (result is LocalStoreResult.Success) {
                    // Ignore a list captured before this commit. Membership changes only on
                    // durable success; the bounded list is then re-read from the repository.
                    libraryGeneration++;libraryRequest?.cancel()
                    updateMembership(membership.value.copy(saved=if(remove) membership.value.saved-id else membership.value.saved+id,
                        loading=false,unavailable=false))
                    mutableError.value=null;owner?.changed()
                } else {
                    if (reader.value.publication?.id == id) readerMutationFailed=true
                    mutableError.value="The library change could not be saved on this device."
                }
            } finally {
                libraryMutations.remove(id)
                updateMembership(membership.value.copy(mutating=membership.value.mutating-id))
                // Cancellation may race a commit. Reconcile with disk rather than claiming
                // either durable success or failure from a cancelled return value.
                if (!closed) refreshLibrary()
            }
        }
        libraryMutations[id]=request
        request.invokeOnCompletion {
            // A cancelled scope can prevent the body/finally from starting.
            if (libraryMutations[id] === request) {
                libraryMutations.remove(id)
                updateMembership(membership.value.copy(mutating=membership.value.mutating-id))
            }
        }
        request.start()
        return request
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
    fun close() { if (!closed) {
        closed=true; leftReader(); mutableConfirm.value=false; job.cancel()
        mutableLibrary.value=mutableLibrary.value.copy(loading=false)
        updateMembership(membership.value.copy(loading=false,mutating=emptySet()))
    } }
}
