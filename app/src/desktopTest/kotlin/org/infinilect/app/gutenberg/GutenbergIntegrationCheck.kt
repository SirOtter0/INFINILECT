// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import java.time.Instant
import kotlinx.coroutines.runBlocking

/** Opt-in development evidence only; no next-page/image/acquisition request. */
object GutenbergIntegrationCheck {
    @JvmStatic fun main(args: Array<String>)=runBlocking {
        val query=args.singleOrNull() ?: "Frankenstein"
        val evidence=mutableListOf<GutenbergHttpEvidence>()
        println("EXPERIMENTAL OPDS2 catalog check UTC=${Instant.now()}; root=$GUTENBERG_ROOT")
        try {
            GutenbergSource(observe={synchronized(evidence){evidence+=it}}).use {source->
                val page=source.search(query);check(page.publications.isNotEmpty())
                check(page.publications.all { it.resources.isEmpty() })
                println("publications=${page.publications.size}; next=${page.nextPageToken!=null}; resources=0; acquisitionRequests=0")
                page.publications.take(3).forEach {
                    val title=it.title.take(120).map { char -> if (char.isISOControl()) ' ' else char }.joinToString("")
                    println("ebook=${it.id.localId}; title=$title")
                }
                println("Root discovery + one search validated. Optional delivery metadata is inert; no details/content/download/redirect/UI/device test.")
            }
        } finally {
            synchronized(evidence) {
                evidence.forEach{println("GET ${it.url}; HTTP=${it.status}; consumed=${it.consumedBytes.get()}")}
                println("requests=${evidence.size}; consumed=${evidence.sumOf{it.consumedBytes.get()}}; no retries; no next-page prefetch")
            }
        }
    }
}
