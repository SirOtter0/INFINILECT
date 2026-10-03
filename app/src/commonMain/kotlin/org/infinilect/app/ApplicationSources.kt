// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.core.PublicationSource
import org.infinilect.core.ResourceLoader

/** One platform application owns these clients and the currently attached session.
 * UI-thread lifecycle: cancel the session before closing transport, exactly once.
 * Contains no Activity/Context, filesystem or automatic requests.
 */
class ApplicationSources internal constructor(
    internal val options: List<SourceOption>,
    private val createLoader: (PublicationSource) -> ResourceLoader = { DirectResourceLoader(it) },
    internal val progress: ProgressPersistence? = null,
    private val releaseSources: () -> Unit,
) {
    private var session: ReadingSession? = null
    private var closed = false

    internal fun loaderFor(source: PublicationSource): ResourceLoader {
        check(!closed && options.any { it.source === source })
        return createLoader(source)
    }

    internal fun attach(value: ReadingSession) {
        check(!closed) { "Application sources are closed." }
        if (session !== value) session?.close()
        session = value
    }

    internal fun detach(value: ReadingSession) {
        value.close()
        if (session === value) session = null
    }

    fun flushProgress() { session?.opening?.flushProgress() }

    suspend fun awaitProgressClosed() { progress?.awaitClosed() }

    fun close() {
        if (closed) return
        closed = true
        session?.close()
        session = null
        progress?.close()
        releaseSources()
    }
}

// Platform factories choose private storage; no Context/filesystem type enters common UI.
