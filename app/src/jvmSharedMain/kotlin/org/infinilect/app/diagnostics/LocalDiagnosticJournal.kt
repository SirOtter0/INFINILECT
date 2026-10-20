// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Private opt-in debug evidence, not app settings or a crash recovery mechanism.
 * Event producers only update a tiny ring in memory. The owner coalesces IO elsewhere.
 * A crash may flush synchronously once; kills/native failures may leave only the last flush.
 */
internal class LocalDiagnosticJournal(private val directory: File, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        const val MAX_LINES = 128
        const val MAX_BYTES = 40_000L
        private val linePattern = Regex("(?:t=[0-9]{1,18} code=[A-Z_]{1,40} g=[0-9]{1,18} n=[0-9]{1,10}|uncaught depth=[0-2] class=[A-Za-z0-9_.$]{1,160}|at [A-Za-z0-9_.$]{1,160}\\.[A-Za-z0-9_<>$-]{1,100}:-?[0-9]{1,10}|exit reason=[0-9]{1,3} status=-?[0-9]{1,10} pssKb=[0-9]{1,15} rssKb=[0-9]{1,15})")
    }
    private val memory = Any()
    private val writes = Any()
    private val lines = ArrayDeque<String>()
    @Volatile var enabled = false; private set
    private val marker get() = File(directory, "enabled")
    private val report get() = File(directory, "events.txt")
    private val temporary get() = File(directory, "events.tmp")

    /** Called on IO before installing the optional handler. Ignore incomplete/corrupt snapshots. */
    fun restore() = synchronized(writes) {
        enabled = marker.isFile && marker.length() == 1L
        if (enabled && report.isFile && report.length() <= MAX_BYTES) {
            val restored = report.readLines().filter { it.length <= 300 && linePattern.matches(it) }.takeLast(MAX_LINES)
            synchronized(memory) { lines.clear(); lines.addAll(restored) }
        }
        Files.deleteIfExists(temporary.toPath())
    }
    fun enable() = synchronized(writes) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Diagnostic storage unavailable")
        marker.writeBytes(byteArrayOf(1))
        enabled = true
    }
    /** Explicit disable also removes all saved diagnostic evidence; no reader/cache files touched. */
    fun disable() = synchronized(writes) {
        enabled = false
        synchronized(memory) { lines.clear() }
        Files.deleteIfExists(marker.toPath()); Files.deleteIfExists(report.toPath()); Files.deleteIfExists(temporary.toPath())
    }
    fun record(event: TechnicalEvent, generation: Long = 0, count: Int = 0) {
        if (!enabled) return
        append("t=${now().coerceIn(0, 999_999_999_999_999_999)} code=${event.name} g=${generation.coerceIn(0, 999_999_999_999_999_999)} n=${count.coerceAtLeast(0)}")
    }
    /** Only receives the message-free formatter from SanitizedCrashDiagnostic. Reject everything else. */
    fun crashLine(line: String) {
        if (line.length > 300 || !linePattern.matches(line)) return
        synchronized(memory) {
            if (!enabled) return
            // Keep the latest bounded Java stack through startup/cover churn after reopening.
            if (line.startsWith("uncaught depth=0 ")) lines.removeAll { isCrashLine(it) }
            append(line)
        }
    }
    private fun isCrashLine(line: String) = line.startsWith("uncaught ") || line.startsWith("at ")
    fun exit(reason: Int, status: Int, pss: Long, rss: Long) {
        append("exit reason=${reason.coerceIn(0, 999)} status=$status pssKb=${pss.coerceIn(0, 999_999_999_999_999)} rssKb=${rss.coerceIn(0, 999_999_999_999_999)}")
    }
    private fun append(line: String) = synchronized(memory) {
        if (enabled) {
            while (lines.size >= MAX_LINES) {
                val oldestEvent = lines.indexOfFirst { !isCrashLine(it) }
                lines.removeAt(if (oldestEvent >= 0) oldestEvent else 0)
            }
            lines.addLast(line)
        }
    }
    fun summary(): String = synchronized(memory) { "INFINILECT local-debug v1\n" + lines.joinToString("\n") }
    /** All ordinary calls are on IO. Atomic replacement leaves the preceding good snapshot on failure. */
    fun flush() = synchronized(writes) {
        if (!enabled) return@synchronized
        val text = summary()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw IOException("Diagnostic size limit")
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), report.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
}
