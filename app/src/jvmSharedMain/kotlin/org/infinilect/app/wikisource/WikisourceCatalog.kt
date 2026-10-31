// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.infinilect.app.discovery.*
import org.infinilect.core.*

internal fun JsonObject.wikiInt(key:String)=(this[key] as? JsonPrimitive)?.takeIf {!it.isString}?.intOrNull ?: throw WikisourceInvalid()
internal fun JsonObject.wikiText(key:String,limit:Int)=(this[key] as? JsonPrimitive)?.takeIf {it.isString}?.content?.takeIf {it.length in 1..limit && it.none {c->c.isISOControl() && c !in "\n\r\t"}} ?: throw WikisourceInvalid()
internal fun wikiBase(api:String)="$api?action=query&format=json&formatversion=2&maxlag=5"
internal fun wikiPages(root:JsonObject)=(root["query"] as? JsonObject)?.get("pages") as? JsonArray ?: throw WikisourceInvalid()
internal fun wikiContent(row:JsonObject,models:Set<String> = setOf("wikitext")):String {
    val revision=((row["revisions"] as? JsonArray)?.singleOrNull() as? JsonObject) ?: throw WikisourceInvalid()
    val slot=(revision["slots"] as? JsonObject)?.get("main") as? JsonObject ?: throw WikisourceInvalid()
    if(slot["contentmodel"]?.jsonPrimitive?.contentOrNull?.let {it !in models}==true)throw WikisourceInvalid()
    return (slot["content"] as? JsonPrimitive)?.takeIf {it.isString}?.content?.takeIf {it.length<=524288} ?: throw WikisourceInvalid()
}
private fun queryHash(query:String)=MessageDigest.getInstance("SHA-256").digest(query.encodeToByteArray()).joinToString("") {"%02x".format(it)}
/** Opaque continuation values are query AND edition bound; never interpreted as URLs. */
internal fun wikiCursor(edition:WikisourceEdition,query:String,value:String)=
    "ws2:${queryHash(edition.language+":"+query)}:"+Base64.getUrlEncoder().withoutPadding().encodeToString(value.encodeToByteArray())
internal fun wikiContinuation(edition:WikisourceEdition,query:String,token:String?):String? {
    if(token==null)return null
    if(token.length>900)throw WikisourceInvalid()
    val parts=token.split(':');if(parts.size!=3 || parts[0]!="ws2" || parts[1]!=queryHash(edition.language+":"+query))throw WikisourceInvalid()
    return try {Base64.getUrlDecoder().decode(parts[2]).decodeToString(throwOnInvalidSequence=true)}catch(_:Exception){throw WikisourceInvalid()}
        .takeIf {it.length in 1..512 && it.none(Char::isISOControl) && wikiCursor(edition,query,it)==token} ?: throw WikisourceInvalid()
}

/** Under-demand catalog. Search pages contain inexpensive metadata only; detailed
 * rights evidence and content are fetched for a selected page, never every tile. */
internal class WikisourceCatalog(private val edition:WikisourceEdition,private val bytes:suspend(String)->ByteArray,
    private val now:()->Long=System::currentTimeMillis,private val authorize:suspend(WikiPage)->WikiGrant) {
    private data class Cached(val value:WikiRecord,val at:Long)
    private val records=linkedMapOf<Int,Cached>() // caller serializes access with the source metadata gate
    private fun base()=wikiBase(edition.api)
    private fun page(row:JsonObject,detailed:Boolean):WikiPage {
        if(row["missing"]!=null || row["invalid"]!=null)throw WikisourceInvalid()
        val id=row.wikiInt("pageid");val ns=row.wikiInt("ns");val revision=row.wikiInt("lastrevid");val title=row.wikiText("title",512)
        if(id<=0 || revision<=0 || ns !in setOf(0,edition.authorNamespace,edition.indexNamespace))throw WikisourceInvalid()
        val language=row.wikiText("pagelanguage",32)
        if(!Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*").matches(language) || wikiTitleFromUrl(row.wikiText("fullurl",2048),edition)!=wikiTitle(title))throw WikisourceInvalid()
        val revid=((row["revisions"] as? JsonArray)?.singleOrNull() as? JsonObject)?.wikiInt("revid") ?: throw WikisourceInvalid()
        if(revid!=revision)throw WikisourceInvalid()
        val text=if(detailed)wikiContent(row,if(ns==edition.indexNamespace)setOf("wikitext","proofread-index") else setOf("wikitext")) else ""
        val props=row["pageprops"] as? JsonObject
        val categories=(row["categories"] as? JsonArray)?.map {it as? JsonObject ?: throw WikisourceInvalid()} ?: emptyList()
        if(categories.size>50)throw WikisourceInvalid()
        val versions=categories.any {wikiTitle(it.wikiText("title",512)).substringAfter(':').lowercase() in setOf("versiones","versions","versions pages","versions de textos")}
        val header=if(detailed)try {wikiDeclarations(text).singleOrNull {it.parent==null && it.name in setOf("header","encabezado","encapçalament")}}catch(_:WikisourceInvalid){null} else null
        val kind=when {
            ns==edition.authorNamespace->CatalogPageKind.AUTHOR
            ns==edition.indexNamespace->CatalogPageKind.INDEX
            props?.containsKey("disambiguation")==true->CatalogPageKind.DISAMBIGUATION
            versions->CatalogPageKind.VERSIONS
            header?.field("section")?.isNotBlank()==true || Regex("<pages\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)->CatalogPageKind.CHAPTER
            else->CatalogPageKind.UNKNOWN // no assertion of completeness from namespace, title, header or export alone
        }
        val description=props?.get("wikibase-shortdesc")?.let {it as? JsonPrimitive}?.contentOrNull?.let(::catalogPlainText)
        return WikiPage(edition,id,revision,ns,title,kind,text,if(detailed)try {wikiAuthors(text)}catch(_:WikisourceInvalid){emptyList()} else emptyList(),description,language)
    }
    private fun publication(page:WikiPage,grant:WikiGrant=WikiGrant()):Publication {
        val id=PublicationId(edition.id,page.pageId.toString())
        val candidate=page.kind !in setOf(CatalogPageKind.AUTHOR,CatalogPageKind.INDEX,CatalogPageKind.VERSIONS,CatalogPageKind.DISAMBIGUATION)
        val resources=if(grant.authorized && candidate)listOf(PublicationResource(id,wikiResourceKey(page.revision),PublicationFormat.EPUB,"application/epub+zip")) else emptyList()
        return Publication(id,page.title,if(page.kind==CatalogPageKind.WORK)PublicationType.BOOK else PublicationType.DOCUMENT,
            page.authors,resources,listOf(page.language),edition.canonical(page.title),grant.statements.takeIf {it.isNotEmpty()}?.joinToString("\n"))
    }
    suspend fun record(id:PublicationId,fresh:Boolean=false):WikiRecord {
        val number=id.localId.toIntOrNull()?.takeIf {it>0 && it.toString()==id.localId && id.sourceId==edition.id} ?: throw WikisourceInvalid()
        if(!fresh)records[number]?.takeIf {now()-it.at in 0..120000}?.let {return it.value}
        val url=base()+"&pageids=$number&prop=info%7Cpageprops%7Crevisions%7Ccategories&inprop=url&rvprop=ids%7Ccontent&rvslots=main&rvlimit=1&cllimit=50"
        val rows=wikiPages(wikiJson(bytes(url)));if(rows.size!=1)throw WikisourceInvalid()
        val page=page(rows.single() as? JsonObject ?: throw WikisourceInvalid(),true)
        if(page.pageId!=number)throw WikisourceInvalid()
        val grant=authorize(page);currentCoroutineContext().ensureActive()
        val record=WikiRecord(page.copy(wikitext=""),publication(page,grant),grant)
        records.remove(number);records[number]=Cached(record,now());while(records.size>32)records.remove(records.keys.first())
        return record
    }
    suspend fun info(id:PublicationId):CatalogPublicationInfo {
        val row=record(id);val candidate=row.publication.resources.isNotEmpty()
        return CatalogPublicationInfo(row.page.kind,when {
            row.page.kind in setOf(CatalogPageKind.AUTHOR,CatalogPageKind.INDEX,CatalogPageKind.VERSIONS,CatalogPageKind.DISAMBIGUATION)->CatalogEpubState.WEB_ONLY
            !row.grant.authorized->CatalogEpubState.RIGHTS_UNVERIFIED
            candidate->CatalogEpubState.POTENTIAL
            else->CatalogEpubState.UNAVAILABLE
        },row.page.description,row.publication.sourceUrl,row.grant.authorized,row.publication)
    }
    suspend fun search(raw:String,token:String?):SearchPage {
        val query=normalizedQuery(raw);if(query.isEmpty())return SearchPage(emptyList())
        val continuation=wikiContinuation(edition,"search:$query",token)
        val offset=continuation?.toIntOrNull()?.takeIf {it in 1..1000 && it.toString()==continuation} ?: if(continuation==null)0 else throw WikisourceInvalid()
        val url=base()+"&generator=search&gsrsearch=${wikiParameter(query)}&gsrnamespace=0%7C${edition.authorNamespace}%7C${edition.indexNamespace}&gsrlimit=20&gsroffset=$offset&prop=info%7Cpageprops%7Crevisions&inprop=url&rvprop=ids"
        val root=wikiJson(bytes(url));val rows=if(root["query"]==null && root["batchcomplete"]!=null)JsonArray(emptyList()) else wikiPages(root)
        if(rows.size>20)throw WikisourceInvalid()
        val publications=rows.map {it as? JsonObject ?: throw WikisourceInvalid()}.sortedBy {(it["index"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE}
            .map {publication(page(it,false))}.distinctBy {it.id}
        val next=(root["continue"] as? JsonObject)?.get("gsroffset")?.jsonPrimitive?.intOrNull
        if(next!=null && next<=offset)throw WikisourceInvalid()
        return SearchPage(publications,next?.takeIf {it<=1000}?.let {wikiCursor(edition,"search:$query",it.toString())})
    }
    suspend fun discover(query:String,token:String?):DiscoveryPage {
        if(query !in setOf("*","classics")) {
            val results=search(query,token);return DiscoveryPage(results.publications.map {DiscoveryEntry(it)},results.nextPageToken)
        }
        val continuation=wikiContinuation(edition,"browse",token)
        val listing=wikiJson(bytes(base()+"&list=allpages&apnamespace=0&apfilterredir=nonredirects&aplimit=20"+(continuation?.let {"&apcontinue=${wikiParameter(it)}"} ?: "")))
        val items=(listing["query"] as? JsonObject)?.get("allpages") as? JsonArray ?: throw WikisourceInvalid()
        if(items.size>20)throw WikisourceInvalid()
        val ids=items.map {(it as? JsonObject)?.wikiInt("pageid")?.takeIf {id->id>0} ?: throw WikisourceInvalid()}.distinct()
        val pages=if(ids.isEmpty())emptyList() else {
            val rows=wikiPages(wikiJson(bytes(base()+"&pageids=${ids.joinToString("%7C")}&prop=info%7Cpageprops%7Crevisions&inprop=url&rvprop=ids")))
            if(rows.size>ids.size)throw WikisourceInvalid()
            rows.map {page(it as? JsonObject ?: throw WikisourceInvalid(),false)}.also {if(it.any {p->p.pageId !in ids})throw WikisourceInvalid()}
        }
        val next=(listing["continue"] as? JsonObject)?.get("apcontinue")?.jsonPrimitive?.contentOrNull?.takeIf {it.length in 1..512 && it.none(Char::isISOControl)}
        if(next!=null && next==continuation)throw WikisourceInvalid()
        return DiscoveryPage(pages.sortedBy {ids.indexOf(it.pageId)}.map {DiscoveryEntry(publication(it))},next?.let {wikiCursor(edition,"browse",it)})
    }
}
