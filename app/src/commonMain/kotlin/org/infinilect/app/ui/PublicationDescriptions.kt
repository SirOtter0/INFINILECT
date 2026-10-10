// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.core.PublicationId

internal object DescriptionPolicy {
    const val CHARACTERS = 16_384
    const val VALUES = 8
    const val CACHE_ENTRIES = 8
    const val EXCERPT = 600
}

/** Optional, read-only local metadata. One cancellable load, eight LRU results, no disk cache.
 * Cached absence is safe because local import identities are immutable content digests.
 * Failures never replace a good result; no source/network acquisition or progress writes. */
internal class PublicationDescriptions(private val load: suspend (PublicationId) -> String?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Mutex()
    private val entries = linkedMapOf<PublicationId, String?>()
    private val cleanup = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { lock.withLock { entries.clear() } } }
    }

    suspend fun get(id: PublicationId): String? = lock.withLock {
        scope.coroutineContext.ensureActive()
        if (entries.containsKey(id)) return@withLock entries.remove(id).also { entries[id] = it }
        val task = scope.async { withTimeout(10_000) { load(id) } }
        val value = try { task.await() } finally { withContext(NonCancellable) { task.cancelAndJoin() } }
        currentCoroutineContext().ensureActive()
        scope.coroutineContext.ensureActive()
        require(value == null || value.isNotBlank() && value.length <= DescriptionPolicy.CHARACTERS)
        if (entries.size == DescriptionPolicy.CACHE_ENTRIES) entries.remove(entries.keys.first())
        entries[id] = value
        value
    }
    internal suspend fun retainedEntries() = lock.withLock { entries.size }
    fun close() { scope.cancel() }
    suspend fun awaitClosed() {
        cleanup.join()
        scope.coroutineContext[Job]?.join()
    }
}

internal fun descriptionExcerpt(text: String): String {
    if (text.length <= DescriptionPolicy.EXCERPT) return text
    var end = DescriptionPolicy.EXCERPT
    if (text[end - 1].isHighSurrogate()) end--
    val space = text.lastIndexOf(' ', end - 1)
    if (space >= end - 80) end = space
    return text.take(end).trimEnd() + "…"
}

internal fun readingAction(progress: org.infinilect.core.ReadingProgress?) =
    if (progress == null) "Start reading" else "Continue reading"
