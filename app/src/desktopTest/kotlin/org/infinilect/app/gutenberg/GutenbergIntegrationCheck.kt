// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.infinilect.core.PublicationFormat

/** Opt-in development evidence only; no next-page/image/acquisition request. */
object GutenbergIntegrationCheck {
    @JvmStatic fun main(args: Array<String>)=runBlocking {
        val query=args.singleOrNull() ?: "Frankenstein"
        val evidence=mutableListOf<GutenbergHttpEvidence>()
        println("EXPERIMENTAL OPDS2 catalog check UTC=${Instant.now()}; root=$GUTENBERG_ROOT")
        try {
            GutenbergSource(observe={synchronized(evidence){evidence+=it}}).use {source->
                val page=source.search(query);check(page.publications.isNotEmpty())
                val selected=page.publications.first()
                val current=source.getPublication(selected.id) ?: error("Selected publication unavailable")
                check(current.id==selected.id);check(current.resources.none{it.format==PublicationFormat.TEXT})
                println("publications=${page.publications.size}; next=${page.nextPageToken!=null}; ebook=${current.id.localId}; formats=${current.resources.map{it.format}}; revisions=${current.resources.map{it.revision}}")
                println("Root search template + current publication self/open-access EPUB links validated; no TEXT acquisition contract. No content/download/redirect/UI/device test.")
            }
        } finally {
            synchronized(evidence) {
                evidence.forEach{println("GET ${it.url}; HTTP=${it.status}; consumed=${it.consumedBytes.get()}")}
                println("requests=${evidence.size}; consumed=${evidence.sumOf{it.consumedBytes.get()}}; no retries; no next-page prefetch")
            }
        }
    }
}
