// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.infinilect.app.discovery.*
import org.infinilect.core.Publication
import org.infinilect.core.PublicationId

internal class WikiBudgetReached : Exception()

internal class WikiAssessmentBudget {
    private var requests = 0
    private var bytes = 0L
    fun reset() {
        requests = 0
        bytes = 0
    }
    fun request() {
        if (requests >= 24 || bytes >= 8L * 1024 * 1024) throw WikiBudgetReached()
        requests++
    }
    fun consume(count: Int) {
        bytes += count
        if (bytes > 8L * 1024 * 1024) throw WikiBudgetReached()
    }
}

/** Metadata-only discovery. Explicit checks stay in the existing details dialog. */
internal class WikisourceReadableCatalog(
    private val edition: WikisourceEdition,
    private val now: () -> Long,
    private val raw: suspend (String, String?) -> DiscoveryPage,
    private val info: suspend (PublicationId) -> CatalogPublicationInfo,
    private val known: (Publication) -> CatalogReadability,
) {
    private data class Cached(val page: DiscoveryPage, val at: Long)
    private val pages = linkedMapOf<Pair<String, String?>, Cached>()

    private suspend fun page(query: String, token: String?): DiscoveryPage {
        val key = query to token
        pages.remove(key)?.takeIf { now() - it.at in 0..120_000 }?.let {
            pages[key] = it
            return it.page
        }
        return raw(query, token).also {
            pages[key] = Cached(it, now())
            if (pages.size > 8) pages.remove(pages.keys.first())
        }
    }

    suspend fun load(request: DiscoveryRequest, token: String?): DiscoveryPage {
        if (request.readabilityMode == ReadabilityMode.ALL || !request.assessCandidates) {
            val result = page(request.query, token)
            return result.copy(entries = result.entries.map { it.copy(readability = known(it.publication)) }
                .filter { request.readabilityMode == ReadabilityMode.ALL || it.readability == CatalogReadability.READABLE_CONFIRMED })
        }

        val binding = "readable-v1:${request.query}"
        val cursor = wikiContinuation(edition, binding, token, maximum = 1500, payloadMaximum = 1000)
        var offset = if (cursor == null) 0 else cursor.substringBefore('|').toIntOrNull() ?: throw WikisourceInvalid()
        if (offset !in 0..20 || cursor != null && !cursor.contains('|')) throw WikisourceInvalid()
        var rawToken = cursor?.substringAfter('|')?.ifEmpty { null }
        var finished = false
        var visited = 0
        var assessed = 0
        var matched = false
        var failed = false
        val seenTokens = mutableSetOf<String?>()
        val confirmed = mutableListOf<DiscoveryEntry>()
        val candidates = mutableListOf<DiscoveryEntry>()
        try {
            withTimeoutOrNull(8_000) {
                while (!finished && visited < 2 && assessed < 8) {
                    if (!seenTokens.add(rawToken)) throw WikisourceInvalid()
                    val batch = page(request.query, rawToken)
                    visited++
                    matched = matched || batch.entries.isNotEmpty()
                    if (offset > batch.entries.size) throw WikisourceInvalid()
                    while (offset < batch.entries.size && assessed < 8) {
                        val entry = batch.entries[offset]
                        if (entry.publication.resources.isNotEmpty()) {
                            assessed++
                            val details = try {
                                info(entry.publication.id)
                            } catch (_: WikisourceInvalid) {
                                // A malformed detail cannot authorize this item or discard earlier results.
                                failed = true
                                offset++
                                continue
                            }
                            currentCoroutineContext().ensureActive()
                            val result = entry.copy(publication = details.metadata ?: entry.publication, readability = details.readability())
                            when (result.readability) {
                                CatalogReadability.READABLE_CONFIRMED -> confirmed += result
                                CatalogReadability.READABLE_CANDIDATE -> candidates += result
                                else -> Unit
                            }
                        }
                        offset++
                    }
                    if (offset == batch.entries.size) {
                        val next = batch.nextToken
                        if (next == null) {
                            finished = true
                        } else {
                            if (next in seenTokens) throw WikisourceInvalid()
                            rawToken = next
                            offset = 0
                        }
                    }
                }
            }
        } catch (_: WikiBudgetReached) {
            // Preserve the unfinished slice for a subsequent explicit page request.
        }
        currentCoroutineContext().ensureActive()
        return DiscoveryPage(confirmed.distinctBy { it.publication.id },
            if (finished) null else wikiCursor(edition, binding, "$offset|${rawToken.orEmpty()}"),
            candidates.distinctBy { it.publication.id }, assessmentLimited = !finished, hadCatalogMatches = matched, assessmentFailed = failed)
    }
}
