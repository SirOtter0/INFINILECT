// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

internal const val TEXT_PREFETCH_NEIGHBORS = 2

/** UI-only geometry for ONE width/font/density. Evicting decoded text must not turn an
 * already measured multi-line slot into a one-line placeholder. Never persisted.
 * Primitive storage is bounded by the prepared document's sparse index (<= 16,384 slots).
 */
internal class TextViewportGeometry(windowCount: Int) {
    private val heights = IntArray(windowCount)
    fun measured(index: Int, height: Int) { require(height > 0); heights[index] = height }
    fun pendingHeight(index: Int, viewportHeight: Int): Int =
        heights[index].takeIf { it > 0 } ?: viewportHeight.coerceAtLeast(1)
}

/** One UI-owned reader generation; all methods run on its owning UI dispatcher.
 * One worker + one conflated wakeup, NOT one IO coroutine per lazy item. Active
 * composables retain their windows until disposal; eight additional decoded windows
 * are LRU-cached. Prefetch is two neighbors on EACH side of the composed range.
 * No background whole-document traversal; no change of list membership or keys.
 */
internal class TextWindowLoader(private val document: TextDocument, scope: CoroutineScope) {
    private val active = mutableMapOf<Int, MutableStateFlow<TextWindow?>>()
    private val cache = linkedMapOf<Int, TextWindow>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val mutableFailed = MutableStateFlow(false)
    val failed = mutableFailed.asStateFlow()
    private var generation = 0L
    private var focus = 0
    private var closed = false
    internal val cachedWindows get() = cache.size
    internal val activeWindows get() = active.size
    private val worker = scope.launch {
        for (ignored in wakeup) {
            if (closed || mutableFailed.value || active.isEmpty()) continue
            val token = generation
            val composed = active.keys.sortedBy { abs(it - focus) }
            val first = composed.min(); val last = composed.max()
            val neighbors = (1..TEXT_PREFETCH_NEIGHBORS).flatMap { distance ->
                listOf(first - distance, last + distance)
            }.filter { it in 0 until document.windowCount }
            for (index in composed + neighbors) {
                if (closed || token != generation) break
                if (active[index]?.value != null) continue
                try {
                    val window = cached(index) ?: document.window(index)
                    // Defend even against a non-cooperative cancelled platform/fake read.
                    currentCoroutineContext().ensureActive()
                    if (closed || token != generation) break
                    check(window.index == index && window.startCodePoint == document.windowStart(index))
                    cache.remove(index); cache[index] = window
                    if (cache.size > TEXT_WINDOW_CACHE_ENTRIES) cache.remove(cache.keys.first())
                    active[index]?.value = window
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    if (!closed && token == generation) mutableFailed.value = true
                    break
                }
            }
        }
    }

    private fun cached(index: Int): TextWindow? = cache.remove(index)?.also { cache[index] = it }
    // Read-only composition lookup: a warm neighbor can have real geometry in its first frame.
    fun available(index: Int): TextWindow? = cache[index]
    fun attach(index: Int, state: MutableStateFlow<TextWindow?> = MutableStateFlow(null)): StateFlow<TextWindow?> {
        check(!closed); require(index in 0 until document.windowCount)
        active[index]?.let { return it.asStateFlow() }
        state.value = cached(index) ?: state.value
        active[index] = state
        changed()
        return state.asStateFlow()
    }
    fun detach(index: Int) { if (active.remove(index) != null) changed() }
    fun focus(index: Int) {
        require(index in 0 until document.windowCount)
        if (!closed && focus != index) { focus = index; changed() }
    }
    private fun changed() { generation++; if (!closed) wakeup.trySend(Unit) }
    fun close() {
        if (closed) return
        closed = true; generation++
        worker.cancel(); wakeup.close()
        active.values.forEach { it.value = null }; active.clear(); cache.clear()
    }
    internal suspend fun awaitClosed() = worker.join()
}

/** Pending geometry is never evidence of EOF. Until the visible text and final
 * item are actually laid out, preserve the last meaningful semantic position.
 */
internal fun visibleWindowCodePoint(
    document: TextDocument, window: TextWindow?, lineStartUtf16: Int,
    atBottom: Boolean, finalWindowLaidOut: Boolean,
): Int? = window?.let {
    if (atBottom && finalWindowLaidOut) document.codePoints else it.globalOffset(lineStartUtf16)
}
