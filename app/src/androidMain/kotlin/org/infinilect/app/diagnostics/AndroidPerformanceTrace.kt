// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

import android.os.Trace

/** Only enabled by the Activity's explicit debug diagnostic session. Static stage names only.
 * Synchronous CPU sections begin/end on the same thread, never across a suspension. */
internal object AndroidPerformanceTrace {
    @Volatile var enabled = false
    inline fun <T> section(name: String, block: () -> T): T {
        if (!enabled) return block()
        Trace.beginSection(name)
        try { return block() } finally { Trace.endSection() }
    }
}
