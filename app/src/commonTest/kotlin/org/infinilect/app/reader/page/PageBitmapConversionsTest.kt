// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.infinilect.app.media.Raster
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PageBitmapConversionsTest {
    private fun frames(start: Int, stamp: Long) = (start until start + 4).associateWith { PageFrame.Ready(Raster(1, 1, intArrayOf(it)), stamp, true) as PageFrame }
    private class Input(initial: Map<Int, PageFrame>) {
        private val signal = MutableStateFlow(0L)
        var value = initial
            set(next) { field = next; signal.value++ }
        val changes get() = signal
    }
    @Test fun rapidReplacementsJoinNoncooperativeConversionAndDiscardRetiringResult() = runTest {
        val input = Input(frames(0, 1))
        val gate = CompletableDeferred<Unit>()
        var calls = 0; var active = 0; var peak = 0
        var cache = emptyMap<Int, PageConversion<Int>>()
        val worker = backgroundScope.launch {
            convertPageFrames(input.changes, { input.value }, { raster ->
                calls++; active++; peak = maxOf(peak, active)
                try { if (calls == 1) withContext(NonCancellable) { gate.await() }; raster.argb.single() }
                finally { active-- }
            }) { cache = it; assertTrue(it.size <= 4) }
        }
        runCurrent(); assertEquals(1, calls)
        repeat(100) { input.value = frames(4 + it * 4, it + 2L); runCurrent(); assertEquals(1, calls) }
        gate.complete(Unit); runCurrent()
        assertEquals(1, peak); assertEquals(5, calls); assertEquals(input.value.keys, cache.keys)
        assertTrue(cache.values.all { it.stamp == 101L }); assertFalse(0 in cache)
        worker.cancelAndJoin(); assertEquals(0, active)
    }
    @Test fun retainedIdentityIsBorrowedOnceAndOldStampCannotReplaceFreshConversion() = runTest {
        val input = Input(frames(0, 1)); var calls = 0
        var cache = emptyMap<Int, PageConversion<Int>>()
        val worker = backgroundScope.launch { convertPageFrames(input.changes, { input.value }, { calls++; it.argb.single() }) { cache = it } }
        runCurrent(); assertEquals(4, calls)
        input.value = input.value.filterKeys { it >= 2 } + frames(4, 2).filterKeys { it <= 5 }
        runCurrent(); assertEquals(6, calls); assertEquals(setOf(2, 3, 4, 5), cache.keys)
        input.value = frames(2, 3); runCurrent(); assertEquals(10, calls)
        assertTrue(cache.values.all { it.stamp == 3L })
        input.value = emptyMap(); runCurrent(); assertTrue(cache.isEmpty())
        worker.cancelAndJoin()
    }
    @Test fun conversionFailureHasNoBitmapAndCannotAuthorizePresentation() = runTest {
        val input = Input(frames(0, 1)); var cache = emptyMap<Int, PageConversion<Int>>()
        val worker = backgroundScope.launch { convertPageFrames<Int>(input.changes, { input.value }, { if (it.argb.single() == 2) error("Controlled conversion failure"); it.argb.single() }) { cache = it } }
        runCurrent(); assertNull(cache.getValue(2).bitmap); assertNotNull(cache.getValue(1).bitmap)
        worker.cancelAndJoin()
    }
    @Test fun backAndReopenOwnersShareOneNativeConversionPermit() = runTest {
        val gate = CompletableDeferred<Unit>(); var active = 0; var peak = 0; var calls = 0
        val convert: suspend (Raster) -> Int = {
            active++; peak = maxOf(peak, active); calls++
            try { if (calls == 1) withContext(NonCancellable) { gate.await() }; it.argb.single() }
            finally { active-- }
        }
        val oldInput = Input(frames(0, 1))
        val old = backgroundScope.launch { convertPageFrames(oldInput.changes, { oldInput.value }, convert) {} }
        runCurrent(); old.cancel()
        var newest = emptyMap<Int, PageConversion<Int>>()
        val nextInput = Input(frames(40, 2))
        val next = backgroundScope.launch { convertPageFrames(nextInput.changes, { nextInput.value }, convert) { newest = it } }
        runCurrent(); assertEquals(1, calls); assertTrue(newest.isEmpty())
        gate.complete(Unit); runCurrent(); assertEquals(1, peak); assertEquals(setOf(40, 41, 42, 43), newest.keys)
        old.join(); next.cancelAndJoin(); assertEquals(0, active)
    }
    @Test fun oversizedConversionInputCannotBypassEncodedCacheLimit() = runTest {
        assertFailsWith<IllegalStateException> {
            convertPageFrames(kotlinx.coroutines.flow.flowOf(Unit), { frames(0, 1) + frames(4, 2) }, { it.argb.single() }) {}
        }
    }

}
