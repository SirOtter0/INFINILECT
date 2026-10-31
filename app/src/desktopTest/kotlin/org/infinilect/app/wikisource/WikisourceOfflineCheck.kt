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

/** One deliberate dynamically authorized real export, not a universal availability claim or a normal test.
 * Library identity and a durable reader locator survive reopening without a live source. */
object WikisourceOfflineCheck {
    @JvmStatic fun main(args:Array<String>)=runBlocking<Unit> {
        require(args.size==2) {"Usage: <es|en|ca> <positive page ID>"}
        val edition=WikisourceEdition.entries.single {it.language==args[0]}
        val pageId=args[1].toInt().also {require(it>0)}
        val id=PublicationId(edition.id,pageId.toString())
        val root=Files.createTempDirectory("wiki-live-offline")
        val source=WikisourceSource(edition)
        val otherFormats=OfflineFixtureOwner(root,OfflineFixtureSource())
        val epub=FileEpubPreparer(root.resolve("validated-epub"),validateDocument=source::validateDocument)
        val validator=downloadValidator(otherFormats.text,epub,otherFormats.pages,otherFormats.pdf)
        fun newStore()=FileOfflineDownloads(root.resolve("retained"),listOf(source),{offer,loader->
            try {validator(offer,loader)}catch(e:org.infinilect.app.reader.EpubException) {
                println("Wikisource live phase=validation-failed; reason=${e.failure}; frames="+e.stackTrace.filter{it.className.startsWith("org.infinilect.app")}.take(6).joinToString{"${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"})
                throw e
            }
        })
        var store=newStore()
        val progressDirectory=root.resolve("progress")
        val readerScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val publication=checkNotNull(source.getPublication(id))
            val info=source.catalogInfo(id)
            println("Wikisource live edition=${edition.language}; pageId=$pageId; kind=${info.kind}; epub=${info.epubState}; authorized=${info.authorized}")
            if(!info.authorized)return@runBlocking
            store.load();val offer=store.offers(id).single()
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
                    check(second.metadata.rights.isNotEmpty())
                    println("Wikisource live phase=offline; networkSource=closed; chapters=${second.spine.size}; rights=${second.metadata.rights.size}; navigation=passed; durableLocator=restored")
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
