// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import java.time.Instant
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.infinilect.app.ReadingSession
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.reader.*
import org.infinilect.app.search.SearchState

/** Opt-in, at most two explicit known books. No automatic retry/prefetch/content output. */
object GutenbergTextReadingCheck {
    private fun safeRedirect(value: String?): String? = value?.let {
        runCatching {
            val uri=URI(it)
            if(uri.rawQuery!=null || uri.rawUserInfo!=null || uri.rawFragment!=null ||
                (uri.isAbsolute && (uri.scheme!="https" || uri.host!="www.gutenberg.org"))) "<rejected/redacted>" else it
        }.getOrDefault("<rejected/redacted>")
    }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size in 1..2)
        val books=args.map {
            val pair=it.split(':',limit=2)
            require(pair.size==2)
            val id=GutenbergAcquisition.identifier(pair[0])
            val query=pair[1].trim();GutenbergUrls.search(query)
            id to query
        }
        require(books.map { it.first }.distinct().size==books.size)
        println("Gutenberg full TEXT check UTC=${Instant.now()}")
        val evidence = mutableListOf<GutenbergHttpEvidence>()
        val preparer=FileTextPreparer(desktopTextDirectory())
        try {
            GutenbergSource(observe={synchronized(evidence){evidence+=it}}).use { source ->
                for((id,query) in books) {
                    // Known identifiers are discovery queries, never a guessed download URL.
                    val session=ReadingSession(source,this,true,DirectResourceLoader(source),preparer=preparer)
                    try {
                        session.editQuery(query);session.submitSearch()
                        val result=session.search.state.first { it is SearchState.Results || it is SearchState.Empty || it is SearchState.Error }
                        check(result is SearchState.Results) { "Official search did not produce results (${result::class.simpleName}); no retries." }
                        val publication=result.result.page.publications.single { it.id.localId == id }
                        session.open(publication)
                        val opened=session.opening.state.first { it is OpenPublicationState.Ready || it is OpenPublicationState.Error }
                        check(opened is OpenPublicationState.Ready) { "Gutenberg open failed; no retries." }
                        val resource=opened.publication!!.resources.single()
                        println("ebook=$id; format=${resource.format}; MIME=${resource.mediaType}; revision=${resource.revision}; codePoints=${opened.document.codePoints}; windows=${opened.document.windowCount}")
                        check(opened.document.window(0).text.isNotBlank())
                        session.back();check(session.search.state.value === result && session.query.value == query)
                        println("Strict complete UTF-8 Ready; Back retained query/results; no content logged.")
                    } finally {session.close()}
                }
            }
        } finally {
            preparer.close();preparer.awaitClosed()
            synchronized(evidence) {
                evidence.forEach {println("GET ${it.url}; HTTP=${it.status}; redirect=${safeRedirect(it.location)}; consumed=${it.consumedBytes.get()}")}
                println("requests=${evidence.size}; consumed=${evidence.sumOf{it.consumedBytes.get()}}; no retries; no graphical/device verification")
            }
        }
    }
}
