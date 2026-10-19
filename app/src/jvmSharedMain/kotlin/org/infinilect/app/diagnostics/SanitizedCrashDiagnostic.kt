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
            var cause: Throwable? = error
            repeat(3) { depth ->
                val current = cause ?: return@repeat
                report("uncaught depth=$depth class=${current.javaClass.name.take(160)}")
                current.stackTrace.take(16).forEach { frame ->
                    report("at ${frame.className.take(160)}.${frame.methodName.take(100)}:${frame.lineNumber}")
                }
                cause = current.cause?.takeUnless { it === current }
            }
        } finally {
            // Even a failing diagnostic sink or allocation must reach Android's normal handler.
            previous.uncaughtException(thread, error)
        }
    }
}
