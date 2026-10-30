// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.infinilect.app.downloads.*
import org.infinilect.app.epub.*
import org.infinilect.app.progress.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*

/** One deliberate real export, not a fixture-based availability claim or a normal test.
 * Library identity and a durable reader locator survive reopening without a live source. */
object WikisourceOfflineCheck {
    @JvmStatic fun main(args:Array<String>)=runBlocking<Unit> {
        require(args.isEmpty())
        val root=Files.createTempDirectory("wiki-live-offline")
        val source=WikisourceSource()
        val otherFormats=OfflineFixtureOwner(root,OfflineFixtureSource())
        val epub=FileEpubPreparer(root.resolve("validated-epub"),validateDocument={doc,resource->
            val approval=wikisourceApprovals.single()
            val evidence=wikisourceExportEvidence(doc)
            println("Wikisource live approval identifiers=${doc.metadata.identifier==approval.identifier}; title=${doc.metadata.title==approval.title}; authors=${doc.metadata.creators==approval.creators}; language=${doc.metadata.languages==listOf("es")}; rights=${doc.metadata.rights==approval.rights}; references=${evidence.references==approval.attributionReferences}")
            println("Wikisource live semantic="+evidence.spine.joinToString {"${it.codePoints}:${it.sha256}"})
            source.validateDocument(doc,resource)
        })
        fun newStore()=FileOfflineDownloads(root.resolve("retained"),listOf(source),downloadValidator(otherFormats.text,epub,otherFormats.pages,otherFormats.pdf))
        var store=newStore()
        val progressDirectory=root.resolve("progress")
        val readerScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val results=source.search("El Matadero").publications
            check(results.any {it.id==PublicationId(WIKISOURCE_ID,"1425")})
            println("Wikisource live phase=search; reviewedMatches=${results.size}")
            store.load();val offer=store.offers(PublicationId(WIKISOURCE_ID,"1425")).single()
            val done=store.download(store.pending(offer),{},{})
            check(done.status==DownloadStatus.DOWNLOADED && done.sha256!=null && done.size!=null)
            println("Wikisource live phase=download; bytes=${done.size}; sha256=${done.sha256}; date=${java.time.Instant.now()}")
            store.close();source.close();source.awaitClosed()
            // No in-memory backend snapshot, catalog or HTTP client is reused for opening.
            store=newStore();check(store.load().entries.single().status==DownloadStatus.DOWNLOADED)
            val copy=checkNotNull(store.acquire(offer.publication.id))
            val progressId=ReadingProgressId(copy.publication.id,offer.resource.key,PublicationFormat.EPUB)
            try {
                check(copy.publication.id==offer.publication.id && copy.publication.resources.single()==offer.resource)
                val document=epub.prepare(copy.publication,offer.resource,copy.loader)
                val writer=ProgressPersistence(FileReadingProgressStore(progressDirectory))
                val reader=EpubReaderController(document,progressId,readerScope,persistence=writer)
                try {
                    reader.initialize(null)
                    for(index in document.spine.indices) {
                        reader.chapter(index)
                        val state=withTimeout(10000){reader.state.first {it is EpubReaderState.Error || it is EpubReaderState.Ready && (it.spineIndex==index || it.error!=null)}}
                        check(state is EpubReaderState.Ready && state.spineIndex==index && state.error==null)
                        reader.presented(state.ticket)
                    }
                    val last=reader.state.value as EpubReaderState.Ready
                    reader.report(last.ticket,0,1);reader.flush()
                }finally{reader.close();writer.close();writer.awaitClosed();document.close()}
                check(!writer.saveFailed.value)
                val restored=checkNotNull(FileReadingProgressStore(progressDirectory).get(progressId))
                val second=epub.prepare(copy.publication,offer.resource,copy.loader)
                val readerAgain=EpubReaderController(second,progressId,readerScope)
                try {
                    readerAgain.initialize(restored)
                    val ready=readerAgain.state.value as EpubReaderState.Ready
                    check(ready.spineIndex==second.spine.lastIndex)
                    check(ready.chapter.locator(ready.initialPosition.first,ready.initialPosition.second)==restored.locator)
                    check(second.metadata.rights.size==2)
                    println("Wikisource live phase=offline; networkSource=closed; chapters=${second.spine.size}; rights=2; navigation=passed; durableLocator=restored")
                }finally{readerAgain.close();second.close()}
            }finally{copy.close()}
            store.remove(offer.publication.id);check(store.acquire(offer.publication.id)==null)
            check(FileReadingProgressStore(progressDirectory).get(progressId)!=null)
            println("Wikisource live phase=remove; retainedBytes=removed; readerProgress=preserved")
        }finally{
            readerScope.cancel();store.close();source.close();source.awaitClosed()
            epub.close();epub.awaitClosed();otherFormats.close();root.toFile().deleteRecursively()
            check(!Files.exists(root));println("Wikisource live cleanup=complete")
        }
    }
}
