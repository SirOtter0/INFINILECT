// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.downloads

import java.nio.file.*
import java.io.IOException
import kotlinx.coroutines.*
import org.infinilect.core.*
import org.infinilect.app.epub.*
import org.infinilect.app.page.developmentCbzBytes
import org.infinilect.app.reader.*
import org.infinilect.app.reader.page.CbzPagePreparer
import org.infinilect.app.reader.pdf.*

/** Original synthetic authority, never added to the production registry. */
internal class OfflineFixtureSource : PublicationSource,OfflineDownloadProvider {
    override val id=SourceId("original-offline-fixtures")
    override val downloadSourceId get()=id
    val data=linkedMapOf<PublicationId,Pair<Publication,ByteArray>>()
    var online=true;var metadataCalls=0;var opens=0;var known=true;var declared: Long?=null;var failAfter: Int?=null
    var beforeRead: suspend ()->Unit = {}
    var offerTransform: (List<DownloadOffer>)->List<DownloadOffer> = {it}
    suspend fun add(format: PublicationFormat,local: String=format.name,bytes: ByteArray?=null): Publication {
        val actualBytes=bytes ?: when(format) {
        PublicationFormat.TEXT -> "Original offline text 🦦 with enough logical reading positions.".toByteArray()
        PublicationFormat.EPUB -> EpubFixture().zip()
        PublicationFormat.CBZ -> developmentCbzBytes()
        PublicationFormat.PDF -> smallPdf()
        else -> error("unsupported fixture")
    }
        val id=PublicationId(id,local)
        val r=PublicationResource(id,"original-resource-$local",format,when(format) {PublicationFormat.EPUB->"application/epub+zip";PublicationFormat.CBZ->"application/vnd.comicbook+zip";PublicationFormat.PDF->"application/pdf";else->"text/plain"})
        return Publication(id,"Same original title",if(format==PublicationFormat.CBZ) PublicationType.COMIC else PublicationType.BOOK,listOf("Original author"),listOf(r),listOf("en"),"https://example.invalid/original","CC0 synthetic test only").also {data[id]=it to actualBytes}
    }
    override suspend fun search(query: String,pageToken: String?)=error("No search during offline reading")
    override suspend fun getPublication(publicationId: PublicationId): Publication? {metadataCalls++;check(online);return data[publicationId]?.first}
    override suspend fun downloadOffers(id: PublicationId): List<DownloadOffer> {
        val publication=getPublication(id) ?: return emptyList()
        return offerTransform(listOf(DownloadOffer(publication,publication.resources.single(),if(known) data.getValue(id).second.size.toLong() else null)))
    }
    override suspend fun openDownload(offer: DownloadOffer)=loadResource(offer.resource)
    override suspend fun loadResource(resource: PublicationResource): ResourceContent {
        check(online);opens++;val bytes=data.getValue(resource.publicationId).second
        return object: ResourceContent {
            override val sizeBytes=declared ?: if(known) bytes.size.toLong() else null
            var offset=0;var closed=false
            override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                check(!closed);beforeRead();currentCoroutineContext().ensureActive()
                if(failAfter?.let {this.offset>=it}==true) throw IOException("Synthetic interrupted transfer")
                if(length==0) return 0
                if(this.offset==bytes.size) return -1
                val n=minOf(length,11,bytes.size-this.offset);bytes.copyInto(buffer,offset,this.offset,this.offset+n);this.offset+=n;return n
            }
            override fun close() {closed=true}
        }
    }
}
internal class OfflineFixtureOwner(val root: Path,val source: OfflineFixtureSource,realPdf: Boolean=false) {
    val text=FileTextPreparer(root.resolve("cache/text"))
    val epub=FileEpubPreparer(root.resolve("cache/epub"))
    val pages=CbzPagePreparer(root.resolve("cache/cbz"))
    val pdf=FilePdfPreparer(root.resolve("cache/pdf"),if(realPdf) platformPdfEngine() else PdfEngine {object: PdfEngineDocument {
        override val pages=listOf(PdfPageGeometry(612.0,792.0))
        override fun render(index: Int,size: PdfRenderSize)=PdfRaster(size,IntArray(size.width*size.height))
        override fun close()=Unit
    }})
    val validator=downloadValidator(text,epub,pages,pdf)
    fun store(free: ((Path)->Long)?=null)=if(free==null) FileOfflineDownloads(root.resolve("files/$OFFLINE_DIRECTORY"),listOf(source),validator) else FileOfflineDownloads(root.resolve("files/$OFFLINE_DIRECTORY"),listOf(source),validator,freeSpace=free)
    suspend fun close() {text.close();epub.close();pages.close();pdf.close();text.awaitClosed();epub.awaitClosed();pages.awaitClosed();pdf.awaitClosed()}
}
