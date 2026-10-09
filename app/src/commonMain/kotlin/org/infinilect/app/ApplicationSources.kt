// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.core.PublicationSource
import org.infinilect.core.ResourceLoader
import org.infinilect.app.collections.ApplicationCollections

/** One platform application owns these clients and the currently attached session.
 * UI-thread lifecycle: cancel the session before closing transport, exactly once.
 * Contains no Activity/Context, filesystem or automatic requests.
 */
class ApplicationSources internal constructor(
    internal val options: List<SourceOption>,
    private val createLoader: (PublicationSource) -> ResourceLoader = { DirectResourceLoader(it) },
    internal val progress: ProgressPersistence? = null,
    internal val collections: ApplicationCollections? = null,
    internal val textPreparer: org.infinilect.app.reader.TextPreparer = org.infinilect.app.reader.defaultTextPreparer(),
    internal val epubPreparer: org.infinilect.app.reader.EpubPreparer? = null,
    internal val epubSettings: org.infinilect.app.reader.epub.EpubSettingsPersistence? = null,
    internal val pagePreparer: org.infinilect.app.reader.page.PagePreparer? = null,
    internal val pageSettings: org.infinilect.app.reader.page.PageSettingsPersistence? = null,
    internal val localImports: org.infinilect.app.imports.LocalPublicationImporter? = null,
    internal val appearance: org.infinilect.app.ui.ApplicationAppearancePreferences? = null,
    internal val pdfPreparer: org.infinilect.app.reader.pdf.PdfPreparer? = null,
    private val sessionDispatcher: CoroutineDispatcher? = null,
    private val releaseSources: () -> Unit,
) {
    private var session: ApplicationSessionLifetime? = null
    private var closed = false
    private var sessionScope: CoroutineScope? = null

    /** The platform owner, not a composition/viewport, owns the active session.
     * Created lazily; one scope/session, released by close(), never an Activity. */
    internal fun applicationSession(): ApplicationSession {
        check(!closed) { "Application sources are closed." }
        (session as? ApplicationSession)?.let { return it }
        val scope = sessionScope ?: CoroutineScope(SupervisorJob() + (sessionDispatcher ?: Dispatchers.Main.immediate)).also { sessionScope = it }
        return ApplicationSession(this, scope).also(::attach)
    }

    internal fun loaderFor(source: PublicationSource): ResourceLoader {
        check(!closed && options.any { it.source === source })
        return createLoader(source)
    }

    internal fun attach(value: ApplicationSessionLifetime) {
        check(!closed) { "Application sources are closed." }
        if (session !== value) session?.close()
        session = value
    }

    internal fun detach(value: ApplicationSessionLifetime) {
        value.close()
        if (session === value) session = null
    }

    fun flushProgress() { session?.flushProgress(); epubSettings?.flush(); pageSettings?.flush() }

    /** Join the independent preference writer before a Desktop process intentionally exits. */
    suspend fun awaitPreferencesClosed() { epubSettings?.awaitClosed(); pageSettings?.awaitClosed(); appearance?.awaitClosed() }

    suspend fun awaitProgressClosed() { awaitPreferencesClosed(); progress?.awaitClosed(); collections?.awaitClosed(); localImports?.awaitClosed(); textPreparer.awaitClosed(); epubPreparer?.awaitClosed(); pagePreparer?.awaitClosed(); pdfPreparer?.awaitClosed() }

    fun close() {
        if (closed) return
        closed = true
        session?.close()
        session = null
        sessionScope?.cancel(); sessionScope = null
        localImports?.close()
        progress?.close()
        appearance?.close()
        epubSettings?.close()
        pageSettings?.close()
        collections?.close()
        textPreparer.close()
        epubPreparer?.close()
        pagePreparer?.close()
        pdfPreparer?.close()
        releaseSources()
    }
}

internal interface ApplicationSessionLifetime {
    fun flushProgress()
    fun close()
}

// Platform factories choose private storage; no Context/filesystem type enters common UI.
