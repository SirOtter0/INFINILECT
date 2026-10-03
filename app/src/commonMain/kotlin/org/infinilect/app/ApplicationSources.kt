// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

/** One platform application owns these clients and the currently attached session.
 * UI-thread lifecycle: cancel the session before closing transport, exactly once.
 * Contains no Activity/Context, registry, persistence or automatic requests.
 */
class ApplicationSources internal constructor(
    internal val options: List<SourceOption>,
    private val releaseSources: () -> Unit,
) {
    private var session: ReadingSession? = null
    private var closed = false

    internal fun attach(value: ReadingSession) {
        check(!closed) { "Application sources are closed." }
        if (session !== value) session?.close()
        session = value
    }

    internal fun detach(value: ReadingSession) {
        value.close()
        if (session === value) session = null
    }

    fun close() {
        if (closed) return
        closed = true
        session?.close()
        session = null
        releaseSources()
    }
}

/** Opens clients, not network connections. Platform application owns closing. */
expect fun createApplicationSources(): ApplicationSources
