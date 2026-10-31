// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.infinilect.app.discovery.*
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

internal enum class WikisourceEdition(val language:String,val authorNamespace:Int,val indexNamespace:Int) {
    ES("es",106,104), EN("en",102,106), CA("ca",106,104);
    val id get()=SourceId("wikisource-$language")
    val displayName get()="Wikisource ($language)"
    val host get()="$language.wikisource.org"
    val api get()="https://$host/w/api.php"
    fun canonical(title:String):String=URI("https",host,"/wiki/"+title.replace(' ','_'),null).toASCIIString()
}
internal const val WIKISOURCE_EXPORT="https://ws-export.wmcloud.org/"
internal const val WIKISOURCE_COMMONS_API="https://commons.wikimedia.org/w/api.php"
internal const val WIKISOURCE_BYTES=32L*1024*1024
internal class WikisourceInvalid:Exception()
internal class WikisourceReviewRequired:SearchException("This page does not provide enough evidence for authorized EPUB acquisition.")
internal class WikisourceApiBusy:Exception()
internal fun wikiParameter(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
internal fun wikiTitle(value:String)=Normalizer.normalize(value.replace('_',' '),Normalizer.Form.NFC)
private fun wikiDecodedPath(uri:URI):String {
    val raw=uri.rawPath;val out=ByteArrayOutputStream();var i=0
    while(i<raw.length) {
        if(raw[i]=='%') {out.write(raw.substring(i+1,i+3).toInt(16));i+=3}
        else {
            val end=raw.indexOf('%',i).let {if(it<0)raw.length else it}
            out.write(raw.substring(i,end).encodeToByteArray());i=end
        }
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString()
}
/** Official project URL as identity evidence only, never a resource URL or opener. */
internal fun wikiTitleFromUrl(value:String,edition:WikisourceEdition):String?=try {
    val uri=URI(value)
    if(value.length>2048 || uri.scheme!="https" || uri.host!=edition.host || uri.rawUserInfo!=null || uri.port!=-1 || uri.rawQuery!=null || uri.rawFragment!=null || !uri.path.startsWith("/wiki/"))null
    else wikiTitle(wikiDecodedPath(uri).removePrefix("/wiki/")).takeIf {it.isNotBlank() && it.length<=512 && it.none {c->c.isISOControl() || c in "\\#[]{}<>|"}}
}catch(_:Exception){null}
internal fun wikiResourceKey(revision:Int)="epub3-policy1-r$revision"
internal fun wikiRevision(key:String):Int?=key.removePrefix("epub3-policy1-r").toIntOrNull()?.takeIf {it>0 && wikiResourceKey(it)==key}
internal data class WikiPage(val edition:WikisourceEdition,val pageId:Int,val revision:Int,val namespace:Int,val title:String,
    val kind:CatalogPageKind,val wikitext:String="",val authors:List<String> = emptyList(),val description:String?=null,val language:String=edition.language)
internal data class WikiGrant(val authorized:Boolean=false,val statements:List<String> = emptyList())
internal data class WikiRecord(val page:WikiPage,val publication:Publication,val grant:WikiGrant)
