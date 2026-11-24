// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.infinilect.app.epub.BoundedEpubParser
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import org.infinilect.app.reader.EpubException
import org.infinilect.app.reader.EpubFailure

/** Evidence for opt-in probes only. Digests are not an admission list or a rights
 * decision. No whole chapter/text buffer, URL fetch or opener. */
internal data class WikisourceSpineEvidence(val codePoints:Int,val sha256:String)
internal data class WikisourceExportEvidence(val spine:List<WikisourceSpineEvidence>,val references:Set<String>)
/** Runs AFTER the ordinary preparer's complete ZIP/XML/resource checks. Binds the
 * source-owned request and official OPF identifier, then parses EVERY spine item
 * through bounded production windows. No live API is required to reopen a copy. */
internal suspend fun validateWikisourceExport(document:EpubDocument,resource:PublicationResource,publication:Publication,edition:WikisourceEdition) {
    fun invalid():Nothing=throw EpubException(EpubFailure.INVALID)
    if(document.publicationId!=publication.id || resource.publicationId!=publication.id || publication.id.sourceId!=edition.id || resource !in publication.resources ||
        publication.id.localId.toIntOrNull()?.let {it>0 && it.toString()==publication.id.localId}!=true ||
        wikiRevision(resource.key)==null || resource.revision!=null || resource.format!=PublicationFormat.EPUB || resource.mediaType!="application/epub+zip")invalid()
    val requested=publication.sourceUrl?.let {wikiTitleFromUrl(it,edition)} ?: invalid()
    val actual=wikiTitleFromUrl(document.metadata.identifier,edition) ?: invalid()
    // Old WS Export aliases may differ in first-letter case. Accept that only
    // with an independent exact bibliographic title match, never for other paths.
    if(actual!=requested && !(actual.equals(requested,true) && document.metadata.title==publication.title))invalid()
    if(publication.languages.isEmpty() || document.metadata.languages.none {actualLang->publication.languages.any {expected->actualLang.equals(expected,true)}})invalid()
    val parser=BoundedEpubParser();var nonempty=false
    for(item in document.spine) {
        val path=document.manifest.single {it.id==item.itemId}.path
        var start=0
        do {
            currentCoroutineContext().ensureActive()
            val window=parser.window(document,path,EpubWindowRequest.Block(start))
            if(window.startBlock!=start || window.endBlock<=start)invalid()
            if(window.blocks.any {it.codePoints>0})nonempty=true
            start=window.endBlock
        }while(start<window.totalBlocks)
    }
    if(!nonempty)invalid()
}
internal suspend fun wikisourceExportEvidence(document:EpubDocument):WikisourceExportEvidence {
    val parser=BoundedEpubParser();val references=linkedSetOf<String>()
    val spine=document.spine.map {item ->
        val path=document.manifest.single {it.id==item.itemId}.path
        val digest=MessageDigest.getInstance("SHA-256");var points=0;var start=0
        do {
            currentCoroutineContext().ensureActive()
            val window=parser.window(document,path,EpubWindowRequest.Block(start))
            check(window.startBlock==start)
            for(block in window.blocks) {
                val text=block.text.encodeToByteArray()
                digest.update(ByteBuffer.allocate(4).putInt(text.size).array());digest.update(text)
                points+=block.codePoints
                for(run in block.runs) run.externalReference?.let {
                    check(run.target==null);references+=it.uri
                    check(references.size<=512)
                }
            }
            check(window.endBlock>start);start=window.endBlock
        } while(start<window.totalBlocks)
        WikisourceSpineEvidence(points,digest.digest().joinToString("") {"%02x".format(it)})
    }
    return WikisourceExportEvidence(spine,references)
}
