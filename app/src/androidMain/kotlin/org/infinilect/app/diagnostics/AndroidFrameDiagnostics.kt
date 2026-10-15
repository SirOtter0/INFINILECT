// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

import android.app.ActivityManager
import android.content.Context
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
    val context = window.context.applicationContext
    val thread = HandlerThread("infinilect-frame-diagnostics").apply { start() }
    val handler = Handler(thread.looper)
    val timings = FrameTimings()
    val layout = FrameTimings(); val draw = FrameTimings(); val sync = FrameTimings(); val gpu = FrameTimings()
    var firstDraws = 0L
    val rate = refreshRate.takeIf { it.isFinite() && it >= 1f } ?: 60f
    val fallbackDeadline = (1_000_000_000L / rate).toLong()
    val listener = Window.OnFrameMetricsAvailableListener { _, metrics, drops ->
        // First draws are recorded separately: do not confuse startup/rotation with steady scrolling.
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) firstDraws++
        else {
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } ?: fallbackDeadline else fallbackDeadline
            timings.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION), deadline, drops)
            layout.add(metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), deadline, 0)
            draw.add(metrics.getMetric(FrameMetrics.DRAW_DURATION), deadline, 0)
            sync.add(metrics.getMetric(FrameMetrics.SYNC_DURATION), deadline, 0)
            if (Build.VERSION.SDK_INT >= 31) gpu.add(metrics.getMetric(FrameMetrics.GPU_DURATION), deadline, 0)
        }
    }
    val registered = try { window.addOnFrameMetricsAvailableListener(listener, handler); true }
    catch (error: RuntimeException) {
        // Crash/exit and memory diagnostics remain useful if an OEM rejects FrameMetrics.
        Log.w("InfinilectPerf", "FrameMetrics unavailable: ${error.javaClass.simpleName}; use system tracing")
        false
    }
    AndroidPerformanceTrace.enabled = true
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    val crash = previous?.let { SanitizedCrashDiagnostic(it) { line -> Log.e("InfinilectCrash", line); Unit } }
    if (crash != null) Thread.setDefaultUncaughtExceptionHandler(crash)
    handler.post { reportPreviousExit(context) }
    Log.i("InfinilectPerf", "frame diagnostics started; explicit debug opt-in; refreshHz=$rate; framesAvailable=$registered; cumulative session counters")
    fun report() {
        try {
            val runtime = Runtime.getRuntime()
            Log.i("InfinilectPerf", "frames=${timings.frames}; missedDeadline=${timings.missedDeadline}; " +
                "p50UpperMs=${timings.percentileMs(50)}; p95UpperMs=${timings.percentileMs(95)}; p99UpperMs=${timings.percentileMs(99)}; " +
                "layoutP95Ms=${layout.percentileMs(95)}; drawP95Ms=${draw.percentileMs(95)}; syncP95Ms=${sync.percentileMs(95)}; gpuP95Ms=${gpu.percentileMs(95)}; gpuSamples=${gpu.frames}; " +
                "over700ms=${timings.over700ms}; overflowAt1000ms=${timings.overflow}; callbackDrops=${timings.callbackDrops}; firstDraws=$firstDraws; " +
                "pssKb=${Debug.getPss()}; javaUsedBytes=${runtime.totalMemory() - runtime.freeMemory()}; " +
                "gcCount=${Debug.getRuntimeStat("art.gc.gc-count")}; gcTimeMs=${Debug.getRuntimeStat("art.gc.gc-time")}")
        } catch (error: RuntimeException) {
            // Only diagnostics fail here: the original application failure is never swallowed.
            Log.w("InfinilectPerf", "Frame summary unavailable: ${error.javaClass.simpleName}; use system tracing")
        }
    }
    // A hard kill may never call Activity.onStop. Preserve aggregate samples beforehand,
    // without logging every frame. Five fixed histograms, no growing frame/result history.
    val periodic = object : Runnable {
        override fun run() { report(); handler.postDelayed(this, 5_000) }
    }
    handler.postDelayed(periodic, 5_000)
    var closed = false
    return AutoCloseable {
        if (!closed) {
            closed = true
            AndroidPerformanceTrace.enabled = false
            if (crash != null && Thread.getDefaultUncaughtExceptionHandler() === crash) Thread.setDefaultUncaughtExceptionHandler(previous)
            if (registered) window.removeOnFrameMetricsAvailableListener(listener)
            handler.removeCallbacks(periodic)
            handler.post { try { report() } finally { thread.quitSafely() } }
        }
    }
}

private fun reportPreviousExit(context: Context) {
    if (Build.VERSION.SDK_INT < 30) { Log.i("InfinilectExit", "Historical exit reasons require Android 11+"); return }
    try {
        val manager = context.getSystemService(ActivityManager::class.java)
        // Own package only; never read description, traceInputStream or process names.
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        exits.forEach { exit ->
            Log.i("InfinilectExit", "reason=${exit.reason}; status=${exit.status}; importance=${exit.importance}; " +
                "pssKb=${exit.pss}; rssKb=${exit.rss}; ageSeconds=${((System.currentTimeMillis() - exit.timestamp) / 1000).coerceAtLeast(0)}")
        }
        if (exits.isEmpty()) Log.i("InfinilectExit", "No historical exit reason recorded")
    } catch (error: RuntimeException) {
        Log.w("InfinilectExit", "Exit reasons unavailable: ${error.javaClass.simpleName}")
    }
}
