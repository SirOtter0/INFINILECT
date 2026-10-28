// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.downloads

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.archive.*
import org.infinilect.core.*
import org.infinilect.app.diagnostics.TechnicalDiagnostics

/** Explicit live check, never part of test/check/build. No book text is printed. */
object ArchiveOfflineDownloadCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.isEmpty())
        val root = Files.createTempDirectory("archive-offline-check")
        val preparers = OfflineFixtureOwner(root, OfflineFixtureSource(), realPdf = true)
        try {
            for(format in listOf(PublicationFormat.TEXT,PublicationFormat.PDF)) {
                InternetArchiveSource(observe = { println("HTTP status=${it.status}") }).use { source ->
                val directory=root.resolve("retained-${format.name}")
                var store = FileOfflineDownloads(directory, listOf(source), preparers.validator)
                store.setDiagnostics(TechnicalDiagnostics {event,stage,count ->println("code=$event g=$stage n=$count")})
                try {
                    store.load()
                    val id = PublicationId(ARCHIVE_ID, "RepublicanismMarxism")
                    val offers = store.offers(id)
                    val offer=offers.single {it.resource.format==format}
                    println("Offer format=${offer.resource.format} size=${offer.size}")
                    val pending = store.pending(offer)
                    val done = store.download(pending, { println("State=${it.status}") }, {})
                    println("State=${done.status} bytes=${done.size}")
                    check(done.status == DownloadStatus.DOWNLOADED)
                    store.close();source.close()
                    // A fresh store/reader preparation with a closed source cannot use
                    // network cache or refresh metadata. Native PDFBox is used on Desktop.
                    store=FileOfflineDownloads(directory,listOf(source),preparers.validator)
                    check(store.load().entries.single().status==DownloadStatus.DOWNLOADED)
                    val copy=checkNotNull(store.acquire(id))
                    try {preparers.validator(offer,copy.loader)} finally {copy.close()}
                    println("Restart/offline validation=$format; source closed; original identity retained")
                  } finally { store.close() }
                }
            }
        } finally { preparers.close(); root.toFile().deleteRecursively() }
    }
}
