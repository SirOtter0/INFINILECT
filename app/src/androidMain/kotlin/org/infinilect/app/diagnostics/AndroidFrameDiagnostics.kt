// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

/** Explicit debug-session diagnostic, never automatic telemetry. No book/profile/query data.
 * The Activity owns start/stop; reporting and PSS sampling never block its main thread. */
fun startFrameDiagnostics(window: Window, refreshRate: Float): AutoCloseable {
    val thread = HandlerThread("infinilect-frame-diagnostics").apply { start() }
    val handler = Handler(thread.looper)
    val timings = FrameTimings()
    var firstDraws = 0L
    val fallbackDeadline = (1_000_000_000L / refreshRate.coerceAtLeast(1f)).toLong()
    val listener = Window.OnFrameMetricsAvailableListener { _, metrics, drops ->
        // First draws are recorded separately: do not confuse startup/rotation with steady scrolling.
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) firstDraws++
        else timings.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION),
            if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } ?: fallbackDeadline
            else fallbackDeadline, drops)
    }
    try { window.addOnFrameMetricsAvailableListener(listener, handler) }
    catch (error: RuntimeException) {
        thread.quitSafely()
        Log.w("InfinilectPerf", "FrameMetrics unavailable: ${error.javaClass.simpleName}; use system tracing")
        return AutoCloseable {}
    }
    Log.i("InfinilectPerf", "frame diagnostics started; explicit debug opt-in; refreshHz=$refreshRate")
    var closed = false
    return AutoCloseable {
        if (!closed) {
            closed = true
            window.removeOnFrameMetricsAvailableListener(listener)
            handler.post {
                try {
                    val runtime = Runtime.getRuntime()
                    Log.i("InfinilectPerf", "frames=${timings.frames}; missedDeadline=${timings.missedDeadline}; " +
                        "p50UpperMs=${timings.percentileMs(50)}; p95UpperMs=${timings.percentileMs(95)}; p99UpperMs=${timings.percentileMs(99)}; " +
                        "over700ms=${timings.over700ms}; overflowAt1000ms=${timings.overflow}; callbackDrops=${timings.callbackDrops}; firstDraws=$firstDraws; " +
                        "pssKb=${Debug.getPss()}; javaUsedBytes=${runtime.totalMemory() - runtime.freeMemory()}; " +
                        "gcCount=${Debug.getRuntimeStat("art.gc.gc-count")}; gcTimeMs=${Debug.getRuntimeStat("art.gc.gc-time")}")
                } catch (error: RuntimeException) {
                    // A diagnostic service must never make an otherwise usable app crash.
                    Log.w("InfinilectPerf", "Frame summary unavailable: ${error.javaClass.simpleName}; use system tracing")
                } finally { thread.quitSafely() }
            }
        }
    }
}
