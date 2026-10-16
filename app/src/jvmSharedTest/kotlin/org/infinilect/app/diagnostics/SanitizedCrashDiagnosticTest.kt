// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

import kotlin.test.*

class SanitizedCrashDiagnosticTest {
    @Test fun recordsBoundedCodeLocationsWithoutPrivateMessagesAndDelegatesTheSameFailure() {
        val privateText = "private book query and filesystem path"
        val error = IllegalStateException(privateText, IllegalArgumentException(privateText))
        error.stackTrace = Array(100) { StackTraceElement("org.infinilect.Code", "operation", privateText, 42) }
        val lines = mutableListOf<String>()
        var delegated: Throwable? = null
        val handler = SanitizedCrashDiagnostic({ thread, failure ->
            assertSame(Thread.currentThread(), thread); delegated = failure
        }, lines::add)
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, delegated); assertTrue(lines.size <= 51)
        assertTrue(lines.any { "IllegalStateException" in it }); assertTrue(lines.any { "Code.operation:42" in it })
        assertTrue(lines.none { privateText in it })
    }
    @Test fun failureInDiagnosticSinkStillReachesTheOriginalHandler() {
        val original = OutOfMemoryError("Do not conceal the original VM error")
        var delegated: Throwable? = null
        val diagnosticError = IllegalStateException("controlled diagnostic failure")
        val handler = SanitizedCrashDiagnostic({ _, failure -> delegated = failure }, { throw diagnosticError })
        assertSame(diagnosticError, assertFailsWith<IllegalStateException> { handler.uncaughtException(Thread.currentThread(), original) })
        assertSame(original, delegated)
    }
}
