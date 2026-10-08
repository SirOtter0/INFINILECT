// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.media.Raster

// Serialize native conversion across Back/reopen owners as well as within one canvas.
private val pageConversionLock = Mutex()

internal data class PageConversion<T>(val stamp: Long, val bitmap: T?)

/** One converter lifetime, at most four cached identities and one conversion in flight.
 * collectLatest cancels AND joins a retiring native call before starting replacement work.
 * Its retiring input can borrow at most four old rasters, never one map per request. */
internal suspend fun <T> convertPageFrames(
    changes: Flow<*>,
    frames: () -> Map<Int, PageFrame>,
    convert: suspend (Raster) -> T,
    publish: (Map<Int, PageConversion<T>>) -> Unit,
) {
    var cache = emptyMap<Int, PageConversion<T>>()
    changes.collectLatest {
        // Upstream signals contain identities only. Read the latest pixels AFTER the
        // retiring converter joins, so a pending signal cannot retain a third raster map.
        val input = frames()
        check(input.size <= PagePolicy.SPREAD_RETAINED)
        cache = cache.filter { (index, old) -> (input[index] as? PageFrame.Ready)?.stamp == old.stamp }
        publish(cache)
        for ((index, frame) in input) {
            if (frame !is PageFrame.Ready || index in cache) continue
            val bitmap = try { pageConversionLock.withLock { currentCoroutineContext().ensureActive(); convert(frame.raster) } }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            cache = cache + (index to PageConversion(frame.stamp, bitmap))
            check(cache.size <= PagePolicy.SPREAD_RETAINED)
            publish(cache)
        }
    }
}
