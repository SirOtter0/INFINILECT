// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ModelRetention(val pinned: Boolean = true, val lastUsed: Long = 0)
internal interface TranslationModelRecords {
    suspend fun load(): Map<TranslationPair, ModelRetention>
    suspend fun save(records: Map<TranslationPair, ModelRetention>): Boolean
}
internal data class ManagedTranslationModel(val model: TranslationModel, val installed: Boolean = false,
    val retention: ModelRetention = ModelRetention(), val inUse: Boolean = false)
internal data class TranslationModelsState(val models: List<ManagedTranslationModel>, val loaded: Boolean = false,
    val busy: TranslationPair? = null, val working: Boolean = false, val storageBytes: Long? = null,
    val downloaded: Long = 0, val total: Long = 0, val error: String? = null)

/** Application owner: one accepted operation, deduplicated downloads and no reader-owned job. */
internal class TranslationModels(
    private val engine: EpubTranslationEngine, private val records: TranslationModelRecords? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default, private val clock: () -> Long = { translationEpochMillis() },
) {
    companion object {
        const val TEMPORARY_AGE = 30L * 24 * 60 * 60 * 1000
        const val USAGE_WRITE_INTERVAL = 60L * 60 * 1000
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ownership = Mutex()
    private var loaded = false
    private var retained = emptyMap<TranslationPair, ModelRetention>()
    private val leases = mutableMapOf<TranslationPair, Int>()
    private val deleting = mutableSetOf<TranslationPair>()
    private val mutable = MutableStateFlow(TranslationModelsState(engine.models.map { ManagedTranslationModel(it) }))
    val state = mutable.asStateFlow()
    private class Command(val pair: TranslationPair?, val kind: String, val completion: CompletableDeferred<Boolean> = CompletableDeferred()) {
        var job: Job? = null
    }
    private val pending = MutableStateFlow<Command?>(null)
    private val closed = MutableStateFlow(false)
    private var retryOperation: (() -> Unit)? = null
    private suspend fun load() {
        if (loaded) return
        retained = records?.load().orEmpty().filterKeys { pair -> engine.models.any { it.pair == pair } }
        loaded = true
    }
    private fun rows() { mutable.update { current -> current.copy(models = current.models.map {
        it.copy(retention = retained[it.model.pair] ?: ModelRetention(), inUse = (leases[it.model.pair] ?: 0) > 0)
    }) } }
    private suspend fun persist(next: Map<TranslationPair, ModelRetention>) {
        if (records != null && !records.save(next)) throw EpubTranslationException("Model preferences could not be saved. Models remain protected; check private storage and retry.")
        retained = next; rows()
    }
    private fun command(pair: TranslationPair?, kind: String, retry: () -> Unit, action: suspend (Command) -> Unit): Deferred<Boolean> {
        if (closed.value) return CompletableDeferred(false)
        val request = Command(pair, kind)
        if (!pending.compareAndSet(null, request)) {
            val current = pending.value
            return if (kind == "install" && current?.kind == kind && current.pair == pair) current.completion else CompletableDeferred(false)
        }
        retryOperation = retry
        mutable.update { it.copy(busy = pair, working = true, error = null, downloaded = 0, total = 0) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var ok = false
            try {
                ownership.withLock { load() }
                action(request); ok = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = if (e is EpubTranslationException) e.message else "Model operation could not finish. Original reading remains available; retry.") } }
            finally {
                if (pending.compareAndSet(request, null)) mutable.update { if (pending.value == null) it.copy(busy = null, working = false, downloaded = 0, total = 0) else it }
                request.completion.complete(ok)
            }
        }
        request.job = job
        job.invokeOnCompletion {
            if (pending.compareAndSet(request, null)) mutable.update { if (pending.value == null) it.copy(busy = null, working = false, downloaded = 0, total = 0) else it }
            request.completion.complete(false)
        }
        job.start()
        return request.completion
    }
    fun refresh(): Deferred<Boolean> {
        return command(null, "refresh", { refresh() }) {
        if (engine.unavailable != null) { mutable.update { it.copy(loaded = true) }; return@command }
        for (model in engine.models) {
            val installed = engine.installed(model)
            ownership.withLock {
                // Existing installations without retention metadata are conservatively pinned.
                if (installed && model.pair !in retained) persist(retained + (model.pair to ModelRetention()))
                mutable.update { it.copy(models = it.models.map { row -> if (row.model == model) row.copy(installed = installed) else row }) }
                rows()
            }
        }
        val bytes = engine.storageBytes(); mutable.update { it.copy(storageBytes = bytes, loaded = true) }
    }
    }
    fun install(model: TranslationModel, pinned: Boolean): Deferred<Boolean> {
        return command(model.pair, "install", { install(model, pinned) }) { command ->
        require(model in engine.models)
        engine.unavailable?.let { throw EpubTranslationException(it) }
        var previousPercent = -1L
        engine.install(model) { done, total ->
            val percent = if (total > 0) done * 100 / total else 0
            if (percent != previousPercent && !closed.value && pending.value === command) {
                previousPercent = percent
                mutable.update { if (!closed.value && pending.value === command) it.copy(downloaded = done, total = total) else it }
            }
        }
        withContext(NonCancellable) { ownership.withLock {
            // A repeated contextual download never unpins an expressly retained model.
            mutable.update { it.copy(models = it.models.map { row -> if (row.model == model) row.copy(installed = true) else row }) }
            persist(retained + (model.pair to ModelRetention(pinned || retained[model.pair]?.pinned == true, clock())))
            rows()
        } }
        val bytes = engine.storageBytes(); mutable.update { it.copy(storageBytes = bytes) }
    }
    }
    fun pin(model: TranslationModel, pinned: Boolean): Deferred<Boolean> {
        return command(model.pair, "pin", { pin(model, pinned) }) {
        require(model in engine.models)
        ownership.withLock { persist(retained + (model.pair to (retained[model.pair] ?: ModelRetention()).copy(pinned = pinned))) }
    }
    }
    private suspend fun delete(model: TranslationModel, temporaryOnly: Boolean) {
        ownership.withLock {
            if (temporaryOnly) {
                val record = retained[model.pair] ?: return
                // One extra write interval protects usage not persisted before an unexpected close.
                if (record.pinned || record.lastUsed <= 0 || clock() - record.lastUsed < TEMPORARY_AGE + USAGE_WRITE_INTERVAL) return
                if (leases.values.any { it > 0 }) return
            }
            check(leases.values.none { it > 0 }) { "Model is in use" }
            deleting.add(model.pair)
        }
        try {
            engine.remove(model) // Adapter drains/unloads before deleting native-owned files.
            withContext(NonCancellable) { ownership.withLock {
                mutable.update { it.copy(models = it.models.map { row -> if (row.model == model) row.copy(installed = false) else row }) }
                persist(retained - model.pair)
            } }
        } finally { ownership.withLock { deleting.remove(model.pair); rows() } }
        val bytes = engine.storageBytes(); mutable.update { it.copy(storageBytes = bytes) }
    }
    fun remove(model: TranslationModel): Deferred<Boolean> {
        return command(model.pair, "remove", { remove(model) }) { require(model in engine.models); delete(model, false) }
    }
    /** Explicit confirmed cleanup only. Closing a book never evicts a model. */
    fun cleanTemporary(): Deferred<Boolean> {
        return command(null, "clean", { cleanTemporary() }) { engine.models.forEach { delete(it, true) } }
    }
    fun retry() { if (!closed.value) retryOperation?.invoke() }
    fun cancel() { pending.value?.job?.cancel() }
    suspend fun <T> use(model: TranslationModel, action: suspend () -> T): T {
        ownership.withLock {
            check(!closed.value && model.pair !in deleting)
            load()
            leases[model.pair] = (leases[model.pair] ?: 0) + 1; rows()
        }
        try { return action() }
        finally { withContext(NonCancellable) { ownership.withLock {
            leases[model.pair] = (leases[model.pair] ?: 1) - 1
            val previous = retained[model.pair] ?: ModelRetention()
            // Usage timestamps are organizational; a failed write must never weaken pin protection.
            val now = clock()
            if (previous.lastUsed == 0L || now - previous.lastUsed >= USAGE_WRITE_INTERVAL) {
                val next = retained + (model.pair to previous.copy(lastUsed = now))
                if (records == null || records.save(next)) retained = next
            }
            rows()
        } } }
    }
    fun close() { if (closed.compareAndSet(false, true)) scope.cancel() }
    suspend fun awaitClosed() { scope.coroutineContext[Job]?.join() }
}

internal expect fun translationEpochMillis(): Long
