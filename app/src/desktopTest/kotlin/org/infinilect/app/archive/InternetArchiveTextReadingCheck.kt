// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.infinilect.app.ReadingSession
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.search.SearchState
import org.infinilect.core.PublicationResource
import org.infinilect.core.ResourceContent
import org.infinilect.core.ResourceLoader

/** Opt-in: the UI's actual search/open/session path, complete bounded text, no content logging. */
object InternetArchiveTextReadingCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size <= 1)
        val identifier = args.singleOrNull() ?: "gmb-2015-93040"
        ArchiveUrls.identifier(identifier)
        println("Internet Archive full text reading check at ${Instant.now()}")
        val observations = mutableListOf<ArchiveHttpEvidence>()
        var declared: Long? = null
        var consumed = 0L
        var handleClosed = false
        try {
            InternetArchiveSource(observe = { synchronized(observations) { observations += it } }).use { source ->
                val direct = DirectResourceLoader(source)
                val measured = object : ResourceLoader {
                    override suspend fun load(resource: PublicationResource): ResourceContent {
                        val content = direct.load(resource)
                        declared = content.sizeBytes
                        return object : ResourceContent by content {
                            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                                content.read(buffer, offset, length).also { if (it > 0) consumed += it }
                            override fun close() { content.close(); handleClosed = true }
                        }
                    }
                }
                val session = ReadingSession(source, this, textReadingEnabled = true, loader = measured)
                try {
                    session.editQuery("identifier:$identifier")
                    session.submitSearch()
                    val search = session.search.state.first { it is SearchState.Results || it is SearchState.Empty || it is SearchState.Error }
                    check(search is SearchState.Results) { "Real search did not return the test item." }
                    val publication = search.result.page.publications.single { it.id.localId == identifier }
                    session.open(publication)
                    val opened = session.opening.state.first { it is OpenPublicationState.Ready || it is OpenPublicationState.Error }
                    check(opened is OpenPublicationState.Ready) { "Full text acquisition/validation failed." }
                    check(opened.document.publicationId == publication.id && handleClosed && consumed == declared)
                    println("Item=${publication.id.localId}; format=TEXT; declared bytes=$declared; consumed bytes=$consumed")
                    println("Decoded code points=${opened.document.codePoints}; indexed windows=${opened.document.windowCount}; strict UTF-8 document ready; handle closed")
                    session.back()
                    check(session.opening.state.value is OpenPublicationState.Idle && session.search.state.value === search)
                    check(session.query.value == "identifier:$identifier")
                    println("Back retained query/results. No publication text logged. Graphical smoke test not performed.")
                } finally { session.close() }
            }
        } finally {
            synchronized(observations) {
                observations.forEach { println("GET ${it.url} HTTP ${it.status}; consumed=${it.consumedBytes.get()} bytes; redirect=${it.location}") }
                println("Requests=${observations.size}; total application-consumed bytes=${observations.sumOf { it.consumedBytes.get() }}; no retries.")
            }
        }
    }
}
