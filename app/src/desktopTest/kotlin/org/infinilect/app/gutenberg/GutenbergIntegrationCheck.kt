// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import kotlinx.coroutines.runBlocking

/** Explicit manual task, never invoked by normal tests/build. Fetches exactly one page. */
object GutenbergIntegrationCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val query = args.singleOrNull() ?: "shakespeare"
        GutenbergSource().use { source ->
            val page = source.search(query)
            check(page.publications.isNotEmpty()) { "No book results; this manual query cannot verify mapping." }
            check(page.publications.all { it.id.sourceId == source.id })
            println("Real Gutenberg search '$query': ${page.publications.size} books; next token present: ${page.nextPageToken != null}")
            page.publications.take(3).forEach { println("${it.id.localId}: ${it.title} — ${it.authors.joinToString("; ")}") }
            println("One page fetched; next page, details and resources were not requested. No graphical UI was run.")
        }
    }
}
