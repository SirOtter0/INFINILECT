// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.ApplicationSources
import java.io.File
import java.io.IOException

/** Retained debug-runtime owner, never an Activity. Disabled by default; no background polling.
 * Journal in noBackupFilesDir is private and excluded from backup. No raw exception messages.
 */
class AndroidLocalDiagnostics(context: Context, private val sources: ApplicationSources) : AutoCloseable {
    private val context = context.applicationContext
    val available = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    // Resolve/create Android storage only on the IO initializer, never during Activity startup.
    private val journal by lazy {
        val directory = this.context.noBackupFilesDir ?: throw IOException("Diagnostic storage unavailable")
        LocalDiagnosticJournal(File(directory, "local-debug"))
    }
    private val mutable = MutableStateFlow(LocalDiagnosticState())
    internal val state = mutable.asStateFlow()
    private val handlerLock = Any()
    @Volatile private var closed = false
    private var previous: Thread.UncaughtExceptionHandler? = null
    private var handler: Thread.UncaughtExceptionHandler? = null
    private val sink = TechnicalDiagnostics { event, generation, count ->
        if (!closed && mutable.value.enabled) { journal.record(event, generation, count); signal.trySend(Unit) }
    }
    init {
        if (available) {
            sources.technicalDiagnostics = sink; sources.covers?.technicalDiagnostics = sink
            sources.downloads?.technicalDiagnostics = sink
            scope.launch { operations.withLock {
                io {
                    journal.restore()
                    if (journal.enabled) { installHandler(); journal.record(TechnicalEvent.SESSION); previousExit(); journal.flush() }
                    mutable.value = LocalDiagnosticState(ready = true, enabled = journal.enabled)
                }
            } }
            scope.launch {
                for (unused in signal) {
                    // At most four small atomic writes/sec, not per card/frame. One pending signal.
                    delay(250)
                    operations.withLock { io { journal.flush() } }
                }
            }
        }
    }
    internal fun setEnabled(enabled: Boolean) {
        if (closed || !available || !mutable.value.ready || mutable.value.busy) return
        mutable.update { it.copy(busy = true, failed = false) }
        scope.launch { operations.withLock {
            io {
                if (enabled) { journal.enable(); installHandler(); journal.record(TechnicalEvent.SESSION); previousExit(); journal.flush() }
                else { journal.disable(); uninstallHandler() }
            }
            mutable.update { it.copy(enabled = journal.enabled, busy = false, summary = "") }
        } }
    }
    internal fun readSummary() {
        scope.launch { operations.withLock { io {
            journal.flush()
            mutable.update { it.copy(summary = journal.summary(), failed = false) }
        } } }
    }
    fun foreground() { sink.record(TechnicalEvent.FOREGROUND, 0, 0) }
    fun background() { sink.record(TechnicalEvent.BACKGROUND, 0, 0) }
    private fun installHandler() = synchronized(handlerLock) {
        if (closed || handler != null) return@synchronized
        val delegate = Thread.getDefaultUncaughtExceptionHandler() ?: return@synchronized
        previous = delegate
        // Formatter bounds cause depth/frames and excludes messages, filenames and thread names.
        handler = SanitizedCrashDiagnostic({ thread, error ->
            try { if (!closed && journal.enabled) journal.flush() }
            catch (_: IOException) { /* Diagnostic IO must never replace the original failure. */ }
            finally { delegate.uncaughtException(thread, error) }
        }, { line -> if (!closed) journal.crashLine(line) }).also(Thread::setDefaultUncaughtExceptionHandler)
    }
    private fun uninstallHandler() = synchronized(handlerLock) {
        val installed = handler
        if (installed != null && Thread.getDefaultUncaughtExceptionHandler() === installed) {
            Thread.setDefaultUncaughtExceptionHandler(previous); handler = null; previous = null
        }
        // If another diagnostic wraps us, leave its chain intact; a disabled journal records nothing.
    }
    private fun previousExit() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 3).forEach {
                    journal.exit(it.reason, it.status, it.pss, it.rss)
                }
        } catch (error: RuntimeException) {
            // System diagnostic API only; no application failure is intercepted here.
            Log.w("InfinilectExit", "Local exit reason unavailable: ${error.javaClass.simpleName}")
        }
    }
    private inline fun io(block: () -> Unit) {
        try { block() }
        catch (_: IOException) { mutable.update { it.copy(ready = true, failed = true) } }
        catch (_: SecurityException) { mutable.update { it.copy(ready = true, failed = true) } }
    }
    override fun close() {
        synchronized(handlerLock) { closed = true; uninstallHandler() }
        if (sources.technicalDiagnostics === sink) sources.technicalDiagnostics = null
        if (sources.covers?.technicalDiagnostics === sink) sources.covers.technicalDiagnostics = null
        if (sources.downloads?.technicalDiagnostics === sink) sources.downloads.technicalDiagnostics = null
        signal.close(); scope.cancel()
    }
}
internal data class LocalDiagnosticState(val ready: Boolean = false, val enabled: Boolean = false, val busy: Boolean = false,
    val failed: Boolean = false, val summary: String = "")
