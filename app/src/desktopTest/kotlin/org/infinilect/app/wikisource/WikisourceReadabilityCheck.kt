// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.discovery.*
import org.infinilect.app.epub.FileEpubPreparer
import org.infinilect.core.PublicationId

object WikisourceReadabilityCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size in 2..11)
        val edition = WikisourceEdition.entries.single { it.language == args[0] }
        for (page in args.drop(1)) samplePublication(edition, page)
    }

    private suspend fun samplePublication(edition: WikisourceEdition, page: String) {
        val id = PublicationId(edition.id, page.toInt().also { require(it > 0) }.toString())
        val source = WikisourceSource(edition)
        val root = Files.createTempDirectory("infinilect-readable-check")
        val preparer = FileEpubPreparer(root, validateDocument = source::validateDocument)
        var exports = 0
        source.epubInspector { publication, resource ->
            exports++
            preparer.prepare(publication, resource, DirectResourceLoader(source)).close()
        }
        try {
            val publication = checkNotNull(source.getPublication(id))
            val title = publication.title.substringAfter(':').replace("\"", "")
            val query = DiscoveryRequest("intitle:\"$title\"")
            suspend fun sample(label: String, request: DiscoveryRequest) {
                val before = source.metadataTraffic()
                val start = System.nanoTime()
                val previousExports = exports
                val page = source.discoverReadable(request, null)
                val (requests, bytes) = source.metadataTraffic()
                check(exports == previousExports)
                println("WIKI_FILTER edition=${edition.language} id=${id.localId} mode=$label requests=${requests-before.first} bytes=${bytes-before.second} ms=${(System.nanoTime()-start)/1_000_000} listed=${page.entries.any { it.publication.id == id }} candidate=${page.candidates.any { it.publication.id == id }} limited=${page.assessmentLimited}")
            }
            sample("readable-before", query)
            val info = source.catalogInfo(id)
            val checked = if (info.authorized) source.checkEpub(id) else info
            println("WIKI_CHECK id=${id.localId} availability=${checked.readability()} explicitChecks=$exports")
            sample("readable-after", query)
            sample("all", query.copy(readabilityMode = ReadabilityMode.ALL))
        } finally {
            source.close()
            source.awaitClosed()
            preparer.close()
            preparer.awaitClosed()
            root.toFile().deleteRecursively()
        }
    }
}
