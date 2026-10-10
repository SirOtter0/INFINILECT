// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import kotlinx.coroutines.*
import org.infinilect.core.*
import org.infinilect.app.discovery.*

/** Experimental OPDS2 only. Caller serializes requests; no network before explicit action. */
internal class GutenbergCatalog(private val fetch: suspend (String, Boolean) -> ByteArray?,
    private val diagnostics: (GutenbergDiagnostic) -> Unit = {}) {
    private var discovered = false
    suspend fun search(query: String, token: String?, collect: (DiscoveryEntry) -> Unit = {}): SearchPage {
        val normalized = query.trim()
        val first = GutenbergUrls.search(normalized)
        // Validate untrusted tokens before discovery performs any I/O.
        val url = token?.let { GutenbergUrls.pageUrl(it, normalized) } ?: first
        if (!discovered) {
            val bytes = fetch(GUTENBERG_ROOT, false) ?: throw InvalidOpdsException()
            parse(GutenbergStage.ROOT) { GutenbergOpds2Parser.root(bytes, it) }
            discovered = true
        }
        val bytes = fetch(url, false) ?: throw InvalidOpdsException()
        val page = if (token == null) 1 else GutenbergUrls.page(url, normalized)
        return parse(if (token == null) GutenbergStage.SEARCH else GutenbergStage.PAGINATION) { GutenbergOpds2Parser.search(bytes, normalized, page, collect, it) }
    }
    suspend fun getPublication(id: PublicationId): Publication? {
        val bytes = fetch(GutenbergUrls.publication(id.localId), true) ?: return null
        return parse(GutenbergStage.DETAIL) { GutenbergOpds2Parser.detail(bytes, id, it) }
    }
    private suspend fun <T> parse(stage: GutenbergStage, block: (() -> Unit) -> T): T {
        val started = System.nanoTime()
        try {
            val result = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext(); block { context.ensureActive() }
            }
            reportGutenberg(diagnostics, gutenbergDiagnostic(stage, GutenbergParsingStage.COMPLETE, null, started))
            return result
        } catch (error: Exception) {
            val failure = if (error is CancellationException || error is CatalogSourceException) error else CatalogSourceException(CatalogErrorKind.INTERNAL, error)
            val parsing = when ((failure as? CatalogSourceException)?.kind) {
                CatalogErrorKind.INVALID_JSON -> GutenbergParsingStage.JSON
                CatalogErrorKind.SEARCH_LINK -> GutenbergParsingStage.SEARCH_LINK
                else -> GutenbergParsingStage.DOCUMENT
            }
            reportGutenberg(diagnostics, gutenbergDiagnostic(stage, parsing, null, started, failure))
            throw failure
        }
    }
}
