// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.json.*
import org.infinilect.app.discovery.*
import org.infinilect.app.downloads.*
import org.infinilect.app.reader.epub.*
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

internal val WIKISOURCE_ID=SourceId("wikisource-es")
internal const val WIKISOURCE_NAME="Wikisource (es)"
internal const val WIKISOURCE_API="https://es.wikisource.org/w/api.php"
internal const val WIKISOURCE_EXPORT="https://ws-export.wmcloud.org/"
internal const val WIKISOURCE_BYTES=32L*1024*1024
internal class WikisourceInvalid:Exception()
internal class WikisourceReviewRequired:SearchException("Wikisource has no reviewed complete export for this revision.")
internal class WikisourceApiBusy:Exception()

/** Versioned, manually reviewed full works, never a heuristic over search titles.
 * See docs/WIKISOURCE.md for provenance, rights assessment and extension procedure.
 * These are verified bibliographic facts/bindings, not hardcoded search results or bytes.
 */
internal data class WikisourceApproval(val pageId:Int,val revision:Int,val title:String,val exportTitle:String,
    val creators:List<String>,val rights:List<EpubRightsStatement>,val attributionReferences:Set<String>,val evidence:List<WikisourceSpineEvidence>) {
    val resourceKey="epub3-policy1-r$revision"
    val sourceUrl="https://es.wikisource.org/wiki/"+title.replace(' ','_')
    val identifier="https://es.wikisource.org/wiki/$exportTitle"
}
internal val wikisourceApprovals=listOf(WikisourceApproval(1425,1393104,"El Matadero","El_matadero",listOf("Esteban Echeverría"),
    listOf(EpubRightsStatement("Creative Commons BY-SA 3.0","en"),EpubRightsStatement("GNU Free Documentation License","en")),
    setOf("https://es.wikisource.org","https://creativecommons.org/licenses/by-sa/3.0/deed.es","https://www.gnu.org/copyleft/fdl.html","https://es.wikisource.org/wiki/Ayuda:Informar_de_un_error"),listOf(
    WikisourceSpineEvidence(101,"378a38858d29af68f63617832163f65137cca383e2872e18dd50d2c767cddd66"),
    WikisourceSpineEvidence(35733,"6b2a0c65598a2edc042daf755470ad0d8e54a927b6951797810c057e5a6bcf94"),
    WikisourceSpineEvidence(1215,"8734fdc7c932e3e6db0cc197d63000c534050463473c373a513bd597bc5196f0"),
)))
// The Charset overload is newer than Android API26. The named UTF-8 overload
// has identical encoding semantics and is available at the application's minSdk.
internal fun wikiParameter(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
private fun queryHash(query:String)=MessageDigest.getInstance("SHA-256").digest(query.encodeToByteArray()).joinToString("") {"%02x".format(it)}
internal fun wikiCursor(query:String,offset:Int)="ws1:$offset:${queryHash(query)}"
internal fun wikiOffset(query:String,token:String?):Int {
    if(token==null)return 0
    val parts=token.split(':');if(parts.size!=3 || parts[0]!="ws1" || parts[2]!=queryHash(query))throw WikisourceInvalid()
    return parts[1].toIntOrNull()?.takeIf {it in 1..1000 && it.toString()==parts[1]} ?: throw WikisourceInvalid()
}
internal fun wikiJson(bytes:ByteArray):JsonObject {
    if(bytes.size>131072)throw WikisourceInvalid()
    val text=try {bytes.decodeToString(throwOnInvalidSequence=true)}catch(_:Exception){throw WikisourceInvalid()}
    var depth=0;var quoted=false;var escaped=false;var tokens=0
    for(c in text) if(quoted) {if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
        else when(c) {'"'->quoted=true;'[','{'->{if(++depth>24 || ++tokens>10000)throw WikisourceInvalid()};']','}'->{if(--depth<0)throw WikisourceInvalid()};',',':'->{if(++tokens>10000)throw WikisourceInvalid()}}
    if(depth!=0 || quoted)throw WikisourceInvalid()
    // Reject duplicate/escaped-alias object keys before the tree parser can apply
    // last-value-wins semantics. Strings are bounded independently of the response.
    val objects=mutableListOf<MutableSet<String>?>();var i=0
    while(i<text.length) {
        when(text[i]) {
            '{','['->objects.add(if(text[i]=='{')mutableSetOf() else null)
            '}',']'->{if(objects.isEmpty())throw WikisourceInvalid();objects.removeAt(objects.lastIndex)}
            '"'->{
                val begin=i++;var escape=false
                while(i<text.length) {
                    if(i-begin>16384)throw WikisourceInvalid()
                    if(!escape && text[i]=='"')break
                    escape=!escape && text[i]=='\\';i++
                }
                if(i==text.length)throw WikisourceInvalid()
                var next=i+1;while(next<text.length && text[next] in " \t\r\n")next++
                if(next<text.length && text[next]==':') {
                    val key=try {(Json.parseToJsonElement(text.substring(begin,i+1)) as JsonPrimitive).content}catch(_:Exception){throw WikisourceInvalid()}
                    val keys=objects.lastOrNull() ?: throw WikisourceInvalid()
                    if(!keys.add(key) || keys.size>64)throw WikisourceInvalid()
                }
            }
        }
        i++
    }
    if(objects.isNotEmpty())throw WikisourceInvalid()
    val root=try {Json.parseToJsonElement(text) as? JsonObject}catch(_:Exception){null} ?: throw WikisourceInvalid()
    root["error"]?.let {error ->
        val code=(error as? JsonObject)?.get("code").let {it as? JsonPrimitive}?.contentOrNull
        if(code in setOf("maxlag","ratelimited","cirrussearch-too-busy-error"))throw WikisourceApiBusy()
        throw WikisourceInvalid()
    }
    return root
}
private fun JsonObject.number(key:String)=(this[key] as? JsonPrimitive)?.takeIf {!it.isString}?.intOrNull ?: throw WikisourceInvalid()
private fun JsonObject.text(key:String,limit:Int)=(this[key] as? JsonPrimitive)?.takeIf {it.isString}?.content?.takeIf {it.length in 1..limit && it.none(Char::isISOControl)} ?: throw WikisourceInvalid()
private fun normalizedAuthor(value:String)=Normalizer.normalize(value.lowercase(),Normalizer.Form.NFD).filterNot {Character.getType(it)==Character.NON_SPACING_MARK.toInt()}

internal class WikisourceCatalog(private val bytes:suspend(String)->ByteArray,reviewed:List<WikisourceApproval> = wikisourceApprovals) {
    private val approvals=reviewed.associateBy {it.pageId}
    init {require(reviewed.size in 1..25 && approvals.size==reviewed.size)}
    private fun base()="$WIKISOURCE_API?action=query&format=json&formatversion=2&maxlag=5"
    fun approval(id:PublicationId):WikisourceApproval {
        if(id.sourceId!=WIKISOURCE_ID || id.localId.toIntOrNull()?.toString()!=id.localId)throw WikisourceInvalid()
        return approvals[id.localId.toInt()] ?: throw WikisourceReviewRequired()
    }
    suspend fun publication(id:PublicationId):Publication {
        val approval=approval(id)
        val url=base()+"&titles=${wikiParameter(approval.exportTitle)}&redirects=1&prop=info%7Cpageprops%7Crevisions&inprop=url&rvprop=ids"
        val root=wikiJson(bytes(url));val query=root["query"] as? JsonObject ?: throw WikisourceInvalid()
        val pages=query["pages"] as? JsonArray ?: throw WikisourceInvalid()
        if(pages.size!=1)throw WikisourceInvalid()
        val page=pages.single() as? JsonObject ?: throw WikisourceInvalid()
        if(page["missing"]!=null || page["invalid"]!=null || page.number("ns")!=0 || page.number("pageid")!=approval.pageId ||
            page.number("lastrevid")!=approval.revision || page.text("title",512)!=approval.title || page.text("pagelanguage",16)!="es" ||
            page.text("fullurl",2048)!=approval.sourceUrl || (page["pageprops"] as? JsonObject)?.containsKey("disambiguation")==true)throw WikisourceReviewRequired()
        val revision=((page["revisions"] as? JsonArray)?.singleOrNull() as? JsonObject)?.number("revid") ?: throw WikisourceInvalid()
        if(revision!=approval.revision)throw WikisourceReviewRequired()
        val resource=PublicationResource(id,approval.resourceKey,PublicationFormat.EPUB,"application/epub+zip",revision=null)
        return Publication(id,page.text("title",512),PublicationType.BOOK,approval.creators,listOf(resource),listOf("es"),approval.sourceUrl,
            approval.rights.joinToString("\n") {it.text})
    }
    suspend fun featured()=approvals.values.map {publication(PublicationId(WIKISOURCE_ID,it.pageId.toString()))}
    suspend fun search(raw:String,token:String?):SearchPage {
        val query=normalizedQuery(raw);if(query.isEmpty())return SearchPage(emptyList())
        val offset=wikiOffset(query,token)
        // Author lookup is explicitly limited to reviewed records; no global author-search claim.
        val authorMatches=approvals.values.filter {query.length>=3 && it.creators.any {name->normalizedAuthor(name).contains(normalizedAuthor(query))}}
        if(authorMatches.isNotEmpty()) {
            if(offset!=0)throw WikisourceInvalid()
            return SearchPage(authorMatches.map {publication(PublicationId(WIKISOURCE_ID,it.pageId.toString()))})
        }
        val literal=query.replace("\\","\\\\").replace("\"","\\\"")
        val root=wikiJson(bytes(base()+"&list=search&srnamespace=0&srlimit=25&sroffset=$offset&srsearch=${wikiParameter("intitle:\"$literal\"")}"))
        val rows=(root["query"] as? JsonObject)?.get("search") as? JsonArray ?: throw WikisourceInvalid()
        if(rows.size>25)throw WikisourceInvalid()
        val ids=rows.map {row ->val item=row as? JsonObject ?: throw WikisourceInvalid();if(item.number("ns")!=0)throw WikisourceInvalid();item.number("pageid")}.distinct()
        val publications=ids.filter {it in approvals}.map {publication(PublicationId(WIKISOURCE_ID,it.toString()))}
        val next=(root["continue"] as? JsonObject)?.number("sroffset")?.also {if(it !in offset+1..1000)throw WikisourceInvalid()}
        return SearchPage(publications,next?.let {wikiCursor(query,it)})
    }
    suspend fun verify(document:EpubDocument,resource:PublicationResource) {
        val approval=approval(document.publicationId)
        if(resource.publicationId!=document.publicationId || resource.key!=approval.resourceKey || resource.revision!=null || resource.format!=PublicationFormat.EPUB || resource.mediaType!="application/epub+zip")throw WikisourceReviewRequired()
        val meta=document.metadata
        if(meta.identifier!=approval.identifier || meta.title!=approval.title || meta.creators!=approval.creators || meta.languages!=listOf("es") ||
            meta.rights!=approval.rights)throw WikisourceReviewRequired()
        val evidence=wikisourceExportEvidence(document)
        if(evidence.spine!=approval.evidence || evidence.references!=approval.attributionReferences)throw WikisourceReviewRequired()
    }
}
