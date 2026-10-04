// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import kotlinx.coroutines.*
import org.infinilect.core.SearchPage

/** XML OPDS lives here; OPDS2 replaces this catalog boundary, not acquisition/readers. */
internal class GutenbergCatalog(
    private val fetch: suspend (String, String) -> ByteArray?,
    private val openXml: (ByteArray) -> OpdsXmlReader,
) {
    suspend fun search(query: String, pageToken: String?): SearchPage {
        val normalized = query.trim()
        val first = GutenbergUrls.search(normalized)
        val url = if (pageToken == null) first else GutenbergUrls.pageUrl(pageToken, normalized)
        val bytes = fetch(url, "application/atom+xml") ?: throw InvalidOpdsException()
        return withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            GutenbergOpdsParser(openXml).parse(bytes, url, normalized) { context.ensureActive() }
        }
    }
}
