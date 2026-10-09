// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.covers

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.media.*
import org.infinilect.core.*

internal object CoverPolicy {
    const val WIDTH = 192
    const val HEIGHT = 288
    const val ENTRIES = 24
    const val PIXELS = WIDTH * HEIGHT
    fun sample(width: Int, height: Int): Int {
        var sample = 1
        while ((width + sample - 1) / sample > WIDTH || (height + sample - 1) / sample > HEIGHT) sample *= 2
        return sample
    }
}
internal data class CoverArtwork(val format: PublicationFormat, val raster: Raster? = null)
internal data class CoverState(val format: PublicationFormat? = null, val image: ImageBitmap? = null)
internal expect suspend fun decodeCoverThumbnail(bytes: ByteArray, mediaType: String): Raster

/** One app-owned LRU, including negative results. Visible items borrow, never copy, bitmaps.
 * Exactly one load/decode/conversion runs; pending entries and pinned artwork share the 24-slot bound.
 * If all slots are pinned, further items use their typographic fallback. No disk cache or remote fetch.
 */
internal class PublicationCovers(
    private val load: suspend (PublicationId) -> CoverArtwork?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val convert: suspend (Raster) -> ImageBitmap = ::rasterImageBitmap,
) {
    internal class Lease internal constructor(internal val id: PublicationId, internal val entry: Entry?) {
        val state: StateFlow<CoverState> = entry?.state ?: MutableStateFlow(CoverState())
    }
    internal class Entry {
        val state = MutableStateFlow(CoverState())
        var references = 0
        var ready = false
        var active: Deferred<CoverState>? = null
    }
    private val lock = Mutex()
    private val entries = linkedMapOf<PublicationId, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        try {
            for (unused in signal) {
                while (true) {
                    val next = lock.withLock {
                        entries.entries.firstOrNull { it.value.references > 0 && !it.value.ready }?.let { (id, entry) ->
                            val work = async {
                                try {
                                    withTimeout(10_000) {
                                        val artwork = load(id)
                                        val raster = artwork?.raster
                                        require(raster == null || raster.width <= CoverPolicy.WIDTH && raster.height <= CoverPolicy.HEIGHT)
                                        val image = raster?.let { convert(it) }
                                        require(image == null || image.width <= CoverPolicy.WIDTH && image.height <= CoverPolicy.HEIGHT)
                                        CoverState(artwork?.format, image)
                                    }
                                } catch (error: CancellationException) { throw error }
                                catch (_: Exception) { CoverState() }
                            }
                            entry.active = work
                            Triple(id, entry, work)
                        }
                    } ?: break
                    val result = try { next.third.await() }
                    catch (_: CancellationException) { currentCoroutineContext().ensureActive(); null }
                    lock.withLock {
                        if (entries[next.first] === next.second && next.second.active === next.third) {
                            next.second.active = null
                            // Timeout is a bounded negative result; cancellation of an unused lease is retryable.
                            if (result != null || next.second.references > 0) {
                                next.second.ready = true
                                next.second.state.value = result ?: CoverState()
                            }
                        }
                    }
                }
            }
        } finally { withContext(NonCancellable) { lock.withLock { entries.values.forEach { it.state.value = CoverState() }; entries.clear() } } }
    }
    suspend fun acquire(id: PublicationId): Lease = lock.withLock {
        if (!worker.isActive) return@withLock Lease(id, null)
        val old = entries.remove(id)
        val entry = old ?: run {
            if (entries.size >= CoverPolicy.ENTRIES) {
                val obsolete = entries.entries.firstOrNull { it.value.references == 0 }?.key
                    ?: return@withLock Lease(id, null)
                entries.remove(obsolete)?.let { it.active?.cancel(); it.state.value = CoverState() }
            }
            Entry()
        }
        entries[id] = entry
        entry.references++
        signal.trySend(Unit)
        Lease(id, entry)
    }
    suspend fun release(lease: Lease) = lock.withLock {
        val entry = lease.entry ?: return@withLock
        check(entry.references > 0)
        entry.references--
        if (entry.references == 0) { entry.active?.cancel(); entry.active = null }
    }
    suspend fun invalidate(id: PublicationId) = lock.withLock {
        entries.remove(id)?.let { it.active?.cancel(); it.state.value = CoverState() }
    }
    internal suspend fun retainedEntries() = lock.withLock { entries.size }
    fun close() { scope.cancel(); signal.close() }
    suspend fun awaitClosed() { worker.join() }
}
