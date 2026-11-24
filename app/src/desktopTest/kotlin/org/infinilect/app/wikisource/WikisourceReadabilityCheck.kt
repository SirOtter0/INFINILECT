// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.infinilect.app.SourceOption
import org.infinilect.app.discovery.*
import org.infinilect.app.epub.FileEpubPreparer
import org.infinilect.app.epub.payloads
import org.infinilect.core.PublicationId

object WikisourceReadabilityCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        require(args.size in 2..11)
        val edition = WikisourceEdition.entries.single { it.language == args[0] }
        if (args[1] == "--query") {
            require(args.size in 3..4)
            samplePublication(edition, args.getOrNull(3), args[2])
        } else for (page in args.drop(1)) samplePublication(edition, page, null)
    }

    private suspend fun samplePublication(edition: WikisourceEdition, page: String?, search: String?) = coroutineScope {
        val id = page?.let { PublicationId(edition.id, it.toInt().also { n -> require(n > 0) }.toString()) }
        val source = WikisourceSource(edition)
        val root = Files.createTempDirectory("infinilect-readable-check")
        val preparer = FileEpubPreparer(root, validateDocument = source::validateDocument)
        source.epubInspector { publication, resource, loader -> preparer.prepare(publication, resource, loader).close() }
        val catalog = DiscoveryCatalog(listOf(source), this) { System.currentTimeMillis() }
        try {
            val query = search ?: checkNotNull(source.getPublication(checkNotNull(id))).title.substringAfter(':')
            suspend fun sample(label: String, mode: ReadabilityMode): DiscoveryState = coroutineScope {
                val before = source.metadataTraffic()
                val exports = source.automaticTraffic()
                val details = source.assessedDetailCount()
                val controller = DiscoveryController(listOf(SourceOption(edition.displayName, source, epubReadingEnabled = true)), catalog, this)
                controller.readabilityMode(mode)
                val start = System.nanoTime()
                fun elapsed() = (System.nanoTime() - start) / 1_000_000
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    controller.state.first { it.entries.isNotEmpty() }
                    elapsed()
                }
                val runtime = Runtime.getRuntime()
                fun usedHeap() = runtime.totalMemory() - runtime.freeMemory()
                val initialHeap = usedHeap()
                var peakHeap = initialHeap
                var peakArchiveBytes = 0L
                val sampler = launch {
                    while (isActive) {
                        peakHeap = maxOf(peakHeap, usedHeap())
                        val currentArchiveBytes = Files.walk(root).use { paths ->
                            paths.filter { it.fileName.toString().endsWith(".zip") }
                                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
                        }
                        peakArchiveBytes = maxOf(peakArchiveBytes, currentArchiveBytes)
                        delay(20)
                    }
                }
                try {
                    controller.edit(query)
                    controller.submit()
                    val state = withTimeout(25_000) {
                        controller.state.first { it.request != null && !it.loading && !it.checkingReadability }
                    }
                    val duration = elapsed()
                    val firstMillis = if (state.entries.isNotEmpty()) first.await() else null
                    val (requests, bytes) = source.metadataTraffic()
                    val (exportRequests, exportBytes) = source.automaticTraffic()
                    val rows = state.catalogs
                    val listed = state.entries.any { it.publication.id == id }
                    val pending = rows.flatMap { it.candidates }
                    val authorized = state.entries.count { it.readability == CatalogReadability.READABLE_CONFIRMED } + pending.size
                    println("WIKI_AUTO edition=${edition.language} id=${id?.localId} query=$query mode=$label " +
                        "requests=${requests-before.first} bytes=${bytes-before.second} exportRequests=${exportRequests-exports.first} " +
                        "exportBytes=${exportBytes-exports.second} assessed=${source.assessedDetailCount()-details} " +
                        "firstMs=$firstMillis ms=$duration heapDeltaSampleBytes=${peakHeap-initialHeap} sampledArchiveBytes=$peakArchiveBytes " +
                        "listed=$listed confirmedOrPending=$authorized confirmed=${state.entries.size} pending=${pending.size} " +
                        "temporary=${pending.count { it.readability == CatalogReadability.TEMPORARY_ERROR }} " +
                        "limited=${rows.any { it.assessmentLimited }} matches=${rows.any { it.hadCatalogMatches }} " +
                        "detailErrors=${rows.any { it.assessmentFailed }} providerErrors=${rows.mapNotNull { it.failure }}")
                    state
                } finally {
                    first.cancel()
                    sampler.cancelAndJoin()
                    controller.close()
                }
            }
            val cold = sample("cold", ReadabilityMode.READABLE)
            if (search == "A Venus" && edition == WikisourceEdition.ES) {
                val expected = id ?: PublicationId(edition.id, "1927")
                check(cold.entries.any { it.publication.id == expected && it.readability == CatalogReadability.READABLE_CONFIRMED }) {
                    "The cold A Venus search did not confirm the expected EPUB"
                }
            }
            sample("warm", ReadabilityMode.READABLE)
            sample("all", ReadabilityMode.ALL)
        } finally {
            catalog.close()
            catalog.awaitClosed()
            source.close()
            source.awaitClosed()
            preparer.close()
            preparer.awaitClosed()
            check(payloads(root).isEmpty())
            root.toFile().deleteRecursively()
        }
    }
}
