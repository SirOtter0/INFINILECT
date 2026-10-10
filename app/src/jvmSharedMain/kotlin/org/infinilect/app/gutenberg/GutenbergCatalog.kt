// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import kotlinx.coroutines.*
import org.infinilect.core.*
import org.infinilect.app.discovery.*

/** Experimental OPDS2 only. Caller serializes requests; no network before explicit action. */
internal class GutenbergCatalog(private val fetch: suspend (String, Boolean) -> ByteArray?) {
    private var discovered = false
    suspend fun search(query: String, token: String?, collect: (DiscoveryEntry) -> Unit = {}): SearchPage {
        val normalized = query.trim()
        val first = GutenbergUrls.search(normalized)
        // Validate untrusted tokens before discovery performs any I/O.
        val url = token?.let { GutenbergUrls.pageUrl(it, normalized) } ?: first
        if (!discovered) {
            val bytes = fetch(GUTENBERG_ROOT, false) ?: throw InvalidOpdsException()
            parse { GutenbergOpds2Parser.root(bytes, it) }
            discovered = true
        }
        val bytes = fetch(url, false) ?: throw InvalidOpdsException()
        val page = if (token == null) 1 else GutenbergUrls.page(url, normalized)
        return parse { GutenbergOpds2Parser.search(bytes, normalized, page, collect, it) }
    }
    suspend fun getPublication(id: PublicationId): Publication? {
        val bytes = fetch(GutenbergUrls.publication(id.localId), true) ?: return null
        return parse { GutenbergOpds2Parser.detail(bytes, id, it) }
    }
    private suspend fun <T> parse(block: (() -> Unit) -> T): T = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext(); block { context.ensureActive() }
    }
}
