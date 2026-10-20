// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

/** Explicit diagnostic session only. Never log Throwable.message/toString, thread names,
 * filenames, queries or metadata. Delegate the original failure; this is not recovery. */
internal class SanitizedCrashDiagnostic(
    private val previous: Thread.UncaughtExceptionHandler,
    private val report: (String) -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            // CompletionHandlerException can wrap several other completion-handler failures.
            // Keep the initial call site and deepest available cause within the SAME 51-line cap.
            val causes = ArrayList<Throwable>(12)
            var cause: Throwable? = error
            while (causes.size < 12) {
                val current = cause ?: break
                if (causes.any { it === current }) break
                causes.add(current)
                cause = current.cause
            }
            var framesLeft = 51 - causes.size
            causes.forEachIndexed { depth, current ->
                report("uncaught depth=$depth class=${current.javaClass.name.take(160)}")
                val maximum = when (depth) {
                    causes.lastIndex -> minOf(16, framesLeft)
                    0 -> minOf(8, framesLeft)
                    else -> minOf(2, framesLeft)
                }
                current.stackTrace.take(maximum).forEach { frame ->
                    report("at ${frame.className.take(160)}.${frame.methodName.take(100)}:${frame.lineNumber}")
                    framesLeft--
                }
            }
        } finally {
            // Even a failing diagnostic sink or allocation must reach Android's normal handler.
            previous.uncaughtException(thread, error)
        }
    }
}
