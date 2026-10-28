// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.downloads

import kotlinx.coroutines.*
import org.infinilect.core.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.page.PagePreparer
import org.infinilect.app.reader.pdf.PdfPreparer

/** Real preparers retain their ZIP/XML/image/security budgets and own temporary copies. */
internal fun downloadValidator(text: TextPreparer,epub: EpubPreparer,pages: PagePreparer,pdf: PdfPreparer): suspend (DownloadOffer,ResourceLoader)->Unit = {offer,loader ->
    val resource=offer.resource;val publication=offer.publication.copy(resources=listOf(resource))
    val content=loader.load(resource);val prefix=ByteArray(16);var count=0
    try {while(count<prefix.size) {val n=content.read(prefix,count,prefix.size-count);if(n<0) break;count+=n}} finally {content.close()}
    fun reject(): Nothing=throw DownloadException(DownloadFailure.INVALID)
    val zip=count>=4 && prefix[0]==80.toByte() && prefix[1]==75.toByte()
    val isPdf=count>=5 && prefix.copyOf(5).contentEquals("%PDF-".toByteArray())
    when(resource.format) {
        PublicationFormat.TEXT -> {
            val start=prefix.copyOf(count).toString(Charsets.ISO_8859_1).trimStart()
            if(zip || isPdf || prefix.take(count).any {it==0.toByte()} || start.startsWith("<!DOCTYPE",true) || start.startsWith("<html",true)) reject()
            val doc=text.prepare(publication,resource,loader,Dispatchers.IO)
            try {for(i in 0 until doc.windowCount) {currentCoroutineContext().ensureActive();if(doc.window(i).text.any {(it.code in 0..31 && it !in "\t\n\r\u000c") || it.code in 127..159}) reject()}} finally {doc.close()}
        }
        PublicationFormat.EPUB -> {if(!zip) reject();epub.prepare(publication,resource,loader).close()}
        PublicationFormat.CBZ -> {if(!zip) reject();pages.prepare(publication,loader).close()}
        PublicationFormat.PDF -> {if(!isPdf) reject();pdf.prepare(publication,resource,loader).close()}
        else -> reject()
    }
}
