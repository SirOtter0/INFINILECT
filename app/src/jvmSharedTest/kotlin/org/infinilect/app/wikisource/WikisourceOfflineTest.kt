// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.file.*
import kotlinx.coroutines.*
import org.infinilect.app.downloads.*
import org.infinilect.app.epub.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import kotlin.test.*

/** Real adapter, transport and retained-file pipeline, with original synthetic books.
 * The production review list is never populated from HTTP or these fixtures. */
class WikisourceOfflineTest {
    private suspend fun fixture(root:Path):WikisourceSourceTest.Fixture {
        val original=EpubFixture().apply {opf {it.replace("urn:synthetic:foundation","https://es.wikisource.org/wiki/Original_work")
            .replace("Résumé 📚","Original work").replace("<dc:language>fr</dc:language><dc:language>en</dc:language>","<dc:language>es</dc:language>")}}
        val bytes=original.zip();val preparer=FileEpubPreparer(root.resolve("review"))
        try {
            val doc=preparer.prepare(epubPublication,epubResource,epubLoader(EpubBytes(bytes)))
            try {
                val evidence=wikisourceExportEvidence(doc)
                val approval=WikisourceApproval(101,77,"Original work","Original_work",doc.metadata.creators,doc.metadata.rights,emptySet(),evidence.spine)
                return WikisourceSourceTest.Fixture(approval,bytes)
            } finally {doc.close()}
        } finally {preparer.close();preparer.awaitClosed()}
    }
    private class Owner(val root:Path,val source:WikisourceSource) {
        val others=OfflineFixtureOwner(root,OfflineFixtureSource())
        val epub=FileEpubPreparer(root.resolve("authorized-epub"),validateDocument=source::validateDocument)
        fun store(free:((Path)->Long)?=null)=FileOfflineDownloads(root.resolve("retained"),listOf(source),
            downloadValidator(others.text,epub,others.pages,others.pdf),freeSpace=free ?: {Long.MAX_VALUE})
        suspend fun close(){epub.close();epub.awaitClosed();others.close();source.close();source.awaitClosed();assertTrue(payloads(root).isEmpty())}
    }
    @Test fun restartAndClosedNetworkSourceStillOpenTheSameRetainedBookAndPreserveBytes()=runBlocking<Unit> {
        val root=Files.createTempDirectory("wiki-retained");val f=fixture(root);val owner=Owner(root,f.source);var store=owner.store()
        val id=PublicationId(WIKISOURCE_ID,"101")
        try {
            store.load();val offer=store.offers(id).single();assertNull(offer.size)
            val done=store.download(store.pending(offer),{},{})
            assertEquals(DownloadStatus.DOWNLOADED,done.status);assertEquals(f.zip.size.toLong(),done.size)
            assertEquals(1,f.exports.get());assertNotNull(done.sha256)
            store.close();f.source.close();f.source.awaitClosed();val requests=f.requests.get();store=owner.store()
            assertEquals(DownloadStatus.DOWNLOADED,store.load().entries.single().status)
            val copy=assertNotNull(store.acquire(id))
            try {
                val resource=copy.publication.resources.single();assertEquals(offer.resource,resource)
                assertEquals(id,copy.publication.id)
                val doc=owner.epub.prepare(copy.publication,resource,copy.loader)
                try {assertTrue(defaultEpubParser().chapter(doc,doc.manifest.single {it.id==doc.spine.first().itemId}.path).codePoints>0)}finally{doc.close()}
                val content=copy.loader.load(resource)
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                try {while(true){val n=content.read(buffer,0,buffer.size);if(n<0)break;out.write(buffer,0,n)}}finally{content.close()}
                assertContentEquals(f.zip,out.toByteArray())
                assertEquals(requests,f.requests.get())
                assertEquals(DownloadFailure.BUSY,assertFailsWith<DownloadException>{store.remove(id)}.failure)
            }finally{copy.close()}
            store.remove(id);assertNull(store.acquire(id));assertTrue(store.snapshot().entries.isEmpty())
        }finally{store.close();owner.close();root.toFile().deleteRecursively()}
    }
    @Test fun corruptAndStructurallyValidUnreviewedContentNeverPublishARetainedCopy()=runBlocking<Unit> {
        for(mode in listOf("corrupt","changed-text","changed-attribution","changed-revision")) {
            val root=Files.createTempDirectory("wiki-reject");val f=fixture(root);val owner=Owner(root,f.source);val store=owner.store();val id=PublicationId(WIKISOURCE_ID,"101")
            try {
                store.load();val row=store.pending(store.offers(id).single())
                when(mode) {
                    "corrupt"->f.exportBytes="<html>not EPUB</html>".toByteArray()
                    "changed-revision"->f.afterExport={f.revision++}
                    else->{
                        // Modify a valid original fixture with identical reviewed metadata.
                        val zip=EpubFixture().apply {opf {it.replace("urn:synthetic:foundation",f.approval.identifier)
                            .replace("Résumé 📚",f.approval.title).replace("<dc:language>fr</dc:language><dc:language>en</dc:language>","<dc:language>es</dc:language>")}}
                        if(mode=="changed-text")zip.change("OPS/chapter.xhtml"){it.replace("Hello, 世界 📖","Unreviewed replacement text")}
                        else zip.opf {it.replace("Original test fixture","Different unreviewed attribution")}
                        f.exportBytes=zip.zip()
                    }
                }
                val error=assertFailsWith<DownloadException>{store.download(row,{},{})}
                assertEquals(if(mode=="changed-revision")DownloadFailure.RIGHTS_UNVERIFIED else DownloadFailure.INVALID,error.failure)
                store.settle(id,DownloadStatus.ERROR,error.failure);assertNull(store.acquire(id))
                Files.list(root.resolve("retained")).use {files->assertTrue(files.noneMatch {it.fileName.toString().endsWith(".part") || it.fileName.toString().endsWith(".payload")})}
            }finally{store.close();owner.close();root.toFile().deleteRecursively()}
        }
    }
    @Test fun cancellationAndRestartRequireExplicitRetryWithoutDuplicateExportOrPartialPublication()=runBlocking<Unit> {
        val root=Files.createTempDirectory("wiki-cancel");val f=fixture(root);val owner=Owner(root,f.source);var store=owner.store();val id=PublicationId(WIKISOURCE_ID,"101")
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        f.beforeExport={entered.complete(Unit);try{awaitCancellation()}finally{cancelled.complete(Unit)}}
        try {
            store.load();val row=store.pending(store.offers(id).single())
            val active=launch {store.download(row,{},{})};withTimeout(5000){entered.await()}
            active.cancelAndJoin();withTimeout(5000){cancelled.await()};assertNull(store.acquire(id))
            store.close();store=owner.store()
            assertEquals(DownloadStatus.CANCELLED,store.load().entries.single().status)
            assertEquals(1,f.exports.get());assertNull(store.acquire(id))
            f.beforeExport={}
            assertEquals(DownloadStatus.DOWNLOADED,store.download(store.pending(store.offers(id).single()),{},{}).status)
            assertEquals(2,f.exports.get());assertNotNull(store.acquire(id)).close()
        }finally{store.close();owner.close();root.toFile().deleteRecursively()}
    }
    @Test fun insufficientSpaceRejectsBeforeExportAndRetainsNoPayload()=runBlocking<Unit> {
        val root=Files.createTempDirectory("wiki-space");val f=fixture(root);val owner=Owner(root,f.source);val store=owner.store {0};val id=PublicationId(WIKISOURCE_ID,"101")
        try {
            store.load();val row=store.pending(store.offers(id).single())
            assertEquals(DownloadFailure.SPACE,assertFailsWith<DownloadException>{store.download(row,{},{})}.failure)
            assertEquals(0,f.exports.get());assertNull(store.acquire(id))
        }finally{store.close();owner.close();root.toFile().deleteRecursively()}
    }
}
