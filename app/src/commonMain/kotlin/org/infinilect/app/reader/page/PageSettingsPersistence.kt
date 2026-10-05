// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.time.Clock

internal const val PAGE_SETTINGS_WRITE_INTERVAL_MILLIS = 300L

/** Application-owned single-record writer, independent of reader/Compose cancellation.
 * UI-thread commands; one IO worker, one pending value. Coalesces for 300ms (not an
 * indefinitely restarted debounce). Back/onStop/close wake it immediately and close drains.
 * Only the current reader lease may submit, so late old-reader events cannot overwrite.
 */
internal class PageSettingsPersistence(
    private val store: PageReaderSettingsStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val pending = MutableStateFlow<PageReaderPreferences?>(null)
    private val mutableSettings = MutableStateFlow(PageReaderSettings())
    val settings = mutableSettings.asStateFlow()
    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed = mutableSaveFailed.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()
    private val closing = MutableStateFlow(false)
    private var lease = 0L
    private var lastTimestamp = 0L
    private val worker = scope.launch {
        try {
            val stored = try { withTimeout(5_000) { store.load() } }
            catch (_: TimeoutCancellationException) { PageReaderPreferences() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { PageReaderPreferences() }
            // load completes before a reader can claim/submit. No late load overwrites edits.
            mutableSettings.value = stored.settings
            lastTimestamp = stored.updatedAtEpochMillis
            loaded.complete(Unit)
            for (ignored in signal) {
                while (pending.value != null) {
                    if (!closing.value) withTimeoutOrNull(PAGE_SETTINGS_WRITE_INTERVAL_MILLIS) { flushSignal.receive() }
                    val record = pending.value ?: break
                    val saved = try { withTimeout(5_000) { store.save(record) } }
                    catch (_: TimeoutCancellationException) { false }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { false }
                    mutableSaveFailed.value = !saved
                    // A concurrent newer choice survives completion of this older write.
                    pending.compareAndSet(record, null)
                }
            }
        } finally { loaded.complete(Unit); scope.cancel() }
    }

    suspend fun awaitLoaded() { loaded.await() }
    fun claimReader(): Long { check(loaded.isCompleted && !closing.value); return ++lease }
    fun submit(readerLease: Long, settings: PageReaderSettings) {
        if (closing.value || readerLease != lease || settings == mutableSettings.value) return
        mutableSettings.value = settings
        lastTimestamp = maxOf(clock().coerceAtLeast(0), if (lastTimestamp == Long.MAX_VALUE) lastTimestamp else lastTimestamp + 1)
        pending.value = PageReaderPreferences(settings, lastTimestamp)
        signal.trySend(Unit)
    }
    fun flush() { flushSignal.trySend(Unit) }
    fun close() { if (!closing.value) { closing.value = true; flush(); signal.close() } }
    suspend fun awaitClosed() { worker.join() }
}
