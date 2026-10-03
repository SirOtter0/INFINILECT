// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*

internal const val PROGRESS_SAVE_INTERVAL_MILLIS = 2_000L

/** One reader generation. Late callbacks after leave are ignored; records capture their
 * own immutable identity. UI reports logical positions; a fixed 2s throttle (not an
 * endlessly reset debounce) saves even during continuous scrolling/process death.
 */
internal class TextReadingProgress(
    private val document: TextDocument,
    restored: ReadingProgress?,
    private val persistence: ProgressPersistence,
    private val scope: CoroutineScope,
) {
    private val mutableOffset = MutableStateFlow(document.restore(restored))
    val codePointOffset: StateFlow<Int> = mutableOffset.asStateFlow()
    private var pending: ReadingProgress? = null
    private var timer: Job? = null
    private var active = true
    private var lastTimestamp = restored?.updatedAtEpochMillis ?: 0

    fun report(codePointOffset: Int) {
        if (!active) return
        val id = document.progressId ?: return
        val offset = codePointOffset.coerceIn(0, document.codePoints)
        val locator = ReadingLocator.Text(offset.toLong(), document.codePoints.toLong())
        if (offset == mutableOffset.value) return
        mutableOffset.value = offset
        val now = persistence.clock().coerceAtLeast(0)
        lastTimestamp = maxOf(now, if (lastTimestamp == Long.MAX_VALUE) lastTimestamp else lastTimestamp + 1)
        pending = ReadingProgress(id, locator, document.progression(offset), lastTimestamp)
        if (timer?.isActive != true) timer = scope.launch {
            delay(PROGRESS_SAVE_INTERVAL_MILLIS)
            flush()
        }
    }

    fun flush() {
        timer?.cancel(); timer = null
        pending?.let(persistence::submit)
        pending = null
    }
    fun close() { if (active) { active = false; flush() } }
}
