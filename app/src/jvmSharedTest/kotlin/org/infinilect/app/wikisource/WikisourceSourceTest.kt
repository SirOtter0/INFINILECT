// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.infinilect.app.discovery.*
import org.infinilect.app.downloads.*
import org.infinilect.core.*
import kotlin.test.*

/** Production adapter and transport; no live service or title approval fixtures. */
class WikisourceSourceTest {
    internal class Fixture(val edition:WikisourceEdition=WikisourceEdition.EN,val zip:ByteArray=byteArrayOf(1,2,3),val pageId:Int=101,val title:String="Original work") {
        @Volatile var revision=77
        @Volatile var metadataBody:String?=null
        @Volatile var text=if(edition==WikisourceEdition.EN)"{{header|title=$title|author=Original creator}}\n{{PD-old}}" else "<pages index=\"Original scan.djvu\" from=1 to=2 header=1/>"
        @Volatile var commonsLicense="{{PD-scan|PD-old-100-expired}}"
        @Volatile var status=200
        @Volatile var exportBytes=zip
        @Volatile var exportType="application/epub+zip"
        @Volatile var exportStatus=200
        @Volatile var exportLength:Long?=null
        @Volatile var afterExport:(()->Unit)?=null
        var beforeExport:suspend ()->Unit={}
        @Volatile var rows=listOf(pageId,pageId,99999)
        @Volatile var next:Int?=null
        @Volatile var clock=1000L
        @Volatile var requestedSearch:Url?=null
        val id=PublicationId(edition.id,pageId.toString())
        val requests=AtomicInteger();val exports=AtomicInteger();val contents=AtomicInteger();val commons=AtomicInteger()
        val engine=MockEngine {request ->
            requests.incrementAndGet()
            assertEquals(URLProtocol.HTTPS,request.url.protocol)
            assertEquals("INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)",request.headers[HttpHeaders.UserAgent])
            when(request.url.host) {
                "ws-export.wmcloud.org" -> {
                    exports.incrementAndGet();assertEquals("epub-3",request.url.parameters["format"]);assertEquals(edition.language,request.url.parameters["lang"])
                    beforeExport();afterExport?.invoke()
                    respond(exportBytes,HttpStatusCode.fromValue(exportStatus),headersOf(HttpHeaders.ContentType to listOf(exportType),
                        HttpHeaders.ContentLength to listOf((exportLength ?: exportBytes.size.toLong()).toString()),HttpHeaders.Location to listOf("https://127.0.0.1/private")))
                }
                edition.host,"commons.wikimedia.org" -> {
                    assertEquals("/w/api.php",request.url.encodedPath)
                    val parameters=request.url.parameters
                    if(parameters["generator"]=="search") {requestedSearch=request.url;assertNull(parameters["rvlimit"],"Action API forbids rvlimit on generators/multiple pages");assertFalse(parameters["prop"]!!.contains("categories"),"Property continuation must not mask search pagination") }
                    if(status!=200)respond("{}",HttpStatusCode.fromValue(status),headersOf(HttpHeaders.Location,"https://127.0.0.1/private")) else {
                        val detailed=parameters["rvprop"]?.contains("content")==true
                        if(detailed)contents.incrementAndGet()
                        val body=metadataBody ?: when {
                            request.url.host=="commons.wikimedia.org" -> {commons.incrementAndGet();response(listOf(row(500,6,"File:Original scan.djvu",true,commonsLicense)))}
                            parameters["titles"]!=null -> response(listOf(row(400,edition.indexNamespace,parameters["titles"]!!,true,"{{:MediaWiki:Proofreadpage_index_template|Title=Original scan}}")))
                            parameters["list"]=="allpages" -> buildJsonObject {putJsonObject("query") {putJsonArray("allpages") {add(buildJsonObject{put("pageid",pageId);put("ns",0);put("title",title)})}}}.toString()
                            parameters["generator"]=="search" -> response(rows.map {row(it,0,if(it==pageId)title else "Other work",false)},next)
                            else -> response(listOf(row(pageId,0,title,detailed,text)))
                        }
                        respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
                    }
                }
                else -> error("No other host is authorized")
            }
        }
        val source=WikisourceSource(edition,engine,{clock})
        fun row(id:Int,ns:Int,name:String,detailed:Boolean,content:String=""):JsonObject=buildJsonObject {
            put("pageid",id);put("ns",ns);put("lastrevid",revision);put("title",name);put("pagelanguage",edition.language);put("fullurl",edition.canonical(name))
            putJsonArray("revisions") {add(buildJsonObject {put("revid",revision);if(detailed)putJsonObject("slots"){putJsonObject("main"){put("contentmodel",if(ns==edition.indexNamespace)"proofread-index" else "wikitext");put("content",content)}}})}
        }
        fun response(rows:List<JsonObject>,next:Int?=null)=buildJsonObject {
            put("batchcomplete",true);putJsonObject("query"){putJsonArray("pages"){rows.forEach {add(it)}}}
            next?.let{putJsonObject("continue"){put("gsroffset",it);put("continue","gsroffset||")}}
        }.toString()
        suspend fun close(){source.close();source.awaitClosed()}
    }
    @Test fun unicodeGeneralAndAuthorQueriesUseOnlyTheOfficialRouteWithoutExportOrContent()=runBlocking<Unit> {
        for(edition in WikisourceEdition.entries) {
            val f=Fixture(edition)
            try {
                val query="café 世界 +&maxlag=0#?"
                f.source.search(query)
                val url=assertNotNull(f.requestedSearch)
                assertEquals(edition.host,url.host);assertEquals("5",url.parameters["maxlag"])
                assertEquals(query,url.parameters["gsrsearch"])
                assertEquals("0|${edition.authorNamespace}|${edition.indexNamespace}",url.parameters["gsrnamespace"])
                assertEquals("",url.fragment);assertEquals(0,f.exports.get());assertEquals(0,f.contents.get())
                f.source.search("Original creator");assertEquals("Original creator",f.requestedSearch!!.parameters["gsrsearch"])
            }finally{f.close()}
        }
    }
    @Test fun anyPageIdMayBeDiscoveredAndContinuationIsQueryAndEditionBound()=runBlocking<Unit> {
        val f=Fixture()
        try {
            f.next=20
            val first=f.source.search("Original")
            assertEquals(listOf("101","99999"),first.publications.map{it.id.localId})
            assertTrue(first.publications.all{it.type==PublicationType.DOCUMENT && it.resources.isEmpty()})
            val token=assertNotNull(first.nextPageToken)
            assertEquals("20",wikiContinuation(f.edition,"search:Original",token))
            f.rows=emptyList();f.next=null;assertTrue(f.source.search("Original",token).publications.isEmpty())
            val calls=f.requests.get()
            assertFails{f.source.search("Other",token)};assertEquals(calls,f.requests.get())
            assertFails{wikiContinuation(WikisourceEdition.ES,"search:Original",token)}
            assertFails{f.source.search("Original","https://example.org/private")};assertEquals(calls,f.requests.get())
            assertEquals(0,f.exports.get())
        }finally{f.close()}
    }
    @Test fun detailMetadataIsBoundedCachedAndAuthorizationIsSeparateFromTechnicalValidation()=runBlocking<Unit> {
        val f=Fixture()
        try {
            val publication=assertNotNull(f.source.getPublication(f.id))
            assertEquals(listOf("Original creator"),publication.authors);assertEquals("epub3-policy1-r77",publication.resources.single().key);assertNull(publication.resources.single().revision)
            assertEquals(CatalogEpubState.POTENTIAL,f.source.catalogInfo(f.id).epubState)
            assertEquals(1,f.contents.get());assertEquals(0,f.exports.get())
            f.source.downloadOffers(f.id);assertEquals(2,f.contents.get()) // acquiring always checks fresh evidence
            f.clock+=121000;f.source.getPublication(f.id);assertEquals(3,f.contents.get())
            f.text="{{header|title=Unknown rights}}";f.clock+=121000
            val info=f.source.catalogInfo(f.id);assertFalse(info.authorized);assertEquals(CatalogEpubState.RIGHTS_UNVERIFIED,info.epubState)
            assertEquals(DownloadFailure.RIGHTS_UNVERIFIED,assertFailsWith<DownloadException>{f.source.downloadOffers(f.id)}.failure)
            assertEquals(0,f.exports.get())
        }finally{f.close()}
    }
    @Test fun allEditionsUsePositiveRightsEvidenceAndTheSamePipeline()=runBlocking<Unit> {
        for(edition in WikisourceEdition.entries) {
            val f=Fixture(edition)
            try {
                assertTrue(f.source.catalogInfo(f.id).authorized)
                assertEquals(edition.id,f.source.downloadOffers(f.id).single().publication.id.sourceId)
                assertEquals(if(edition==WikisourceEdition.EN)0 else 2,f.commons.get())
                assertEquals(0,f.exports.get())
            }finally{f.close()}
        }
    }
    @Test fun discoveryBrowsesRealPagesAndLanguageFiltersNeverFetchAnotherProject()=runBlocking<Unit> {
        val f=Fixture()
        try {
            assertEquals(listOf(f.id),f.source.discover(DiscoveryRequest("*")).entries.map{it.publication.id})
            val calls=f.requests.get()
            assertTrue(f.source.discover(DiscoveryRequest("book",language="ca")).entries.isEmpty());assertEquals(calls,f.requests.get())
            assertEquals(0,f.exports.get());assertEquals(0,f.contents.get())
        }finally{f.close()}
    }
    @Test fun forgedCanonicalUrlRevisionAndNamespaceFailBeforeAnyExport()=runBlocking<Unit> {
        for(change in listOf<(String)->String>(
            {it.replace("\"lastrevid\":77","\"lastrevid\":78")},
            {it.replace("\"ns\":0","\"ns\":666")},
            {it.replace("https://en.wikisource.org/wiki/Original_work","https://example.org/book")},
            {it.replace("\"title\":\"Original work\"","\"title\":\"Different work\"")}
        )) {
            val f=Fixture()
            try {f.metadataBody=change(f.response(listOf(f.row(101,0,f.title,true,f.text))));assertFails{f.source.downloadOffers(f.id)};assertEquals(0,f.exports.get())}
            finally{f.close()}
        }
    }
    @Test fun malformedMissingOversizedAndDuplicateJsonFailsWithSanitizedError()=runBlocking<Unit> {
        for(body in listOf("{\"query\":{},\"query\":{}}","{\"query\":{},\"\\u0071uery\":{}}","<html>no JSON</html>","{}",
            "{"+" ".repeat(1048576)+"}","[".repeat(25)+"]".repeat(25),"{\"error\":{\"code\":\"private\",\"info\":\"private text\"}}")) {
            val f=Fixture()
            try {f.metadataBody=body;val error=assertFailsWith<CatalogSourceException>{f.source.search("book")};assertEquals(CatalogErrorKind.INVALID_JSON,error.kind);assertFalse(error.message!!.contains("private text"))}
            finally{f.close()}
        }
    }
    @Test fun redirectsForbiddenResponsesAndRateLimitingNeverFollowUntrustedLocations()=runBlocking<Unit> {
        for(status in listOf(302,403,429,500)) {
            val f=Fixture()
            try {
                f.status=status;val error=assertFailsWith<CatalogSourceException>{f.source.search("book")}
                assertEquals(when(status){302->CatalogErrorKind.REDIRECT;403->CatalogErrorKind.HTTP_FORBIDDEN;429->CatalogErrorKind.HTTP_RATE_LIMITED;else->CatalogErrorKind.HTTP_ERROR},error.kind)
                assertEquals(1,f.requests.get())
                if(status==429){assertFails{f.source.search("book")};assertEquals(1,f.requests.get());f.clock+=31000;f.status=200;assertEquals(2,f.source.search("book").publications.size)}
            }finally{f.close()}
        }
    }
    @Test fun acquisitionKeepsNumericHttpFailuresWithoutProviderResponseText()=runBlocking<Unit> {
        for(status in listOf(403,404,429,503)) {
            val f=Fixture()
            try {f.status=status;val failure=assertFailsWith<DownloadException>{f.source.downloadOffers(f.id)};assertEquals(DownloadFailure.HTTP,failure.failure);assertEquals(status,failure.httpStatus);assertEquals(0,f.exports.get())}
            finally{f.close()}
        }
    }
    @Test fun apiBusyWithHttp200UsesABoundedCooldown()=runBlocking<Unit> {
        val f=Fixture()
        try {f.metadataBody="{\"error\":{\"code\":\"maxlag\",\"info\":\"ignored\"}}";assertEquals(CatalogErrorKind.HTTP_RATE_LIMITED,assertFailsWith<CatalogSourceException>{f.source.search("book")}.kind);assertFails{f.source.search("book")};assertEquals(1,f.requests.get())}
        finally{f.close()}
    }
    @Test fun cancellationRetiresTheOwnedRequestAndCloseRejectsNewOperations()=runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val engine=MockEngine{entered.complete(Unit);try{awaitCancellation()}finally{cancelled.complete(Unit)}}
        val source=WikisourceSource(engine=engine)
        try {val job=launch{source.search("book")};withTimeout(5000){entered.await()};job.cancelAndJoin();withTimeout(5000){cancelled.await()};source.close();source.awaitClosed();assertFailsWith<IllegalStateException>{source.search("book")}}
        finally{source.close();source.awaitClosed()}
    }
    @Test fun exportRequiresFreshRightsAndRejectsHtmlOrARevisionChangeAtEof()=runBlocking<Unit> {
        for(mode in listOf("html","revision","rights")) {
            val f=Fixture()
            try {
                val offer=f.source.downloadOffers(f.id).single()
                when(mode){"html"->f.exportType="text/html";"revision"->f.afterExport={f.revision++};else->f.afterExport={f.text="Unknown rights"}}
                val error=assertFailsWith<DownloadException>{val content=f.source.openDownload(offer);try{val b=ByteArray(32);while(content.read(b,0,b.size)>=0){}}finally{content.close()}}
                assertEquals(if(mode=="html")DownloadFailure.INVALID else DownloadFailure.RIGHTS_UNVERIFIED,error.failure)
            }finally{f.close()}
        }
    }
    @Test fun exportRedirectsTruncatedAndOversizedBodiesFailClosed()=runBlocking<Unit> {
        for(mode in listOf("redirect","truncated","oversized")) {
            val f=Fixture()
            try {
                val offer=f.source.downloadOffers(f.id).single()
                when(mode){"redirect"->f.exportStatus=307;"truncated"->f.exportLength=100;else->f.exportLength=WIKISOURCE_BYTES+1}
                val error=assertFailsWith<DownloadException>{val content=f.source.openDownload(offer);try{val b=ByteArray(32);while(content.read(b,0,b.size)>=0){}}finally{content.close()}}
                assertEquals(when(mode){"redirect"->DownloadFailure.HTTP;"truncated"->DownloadFailure.INCOMPLETE;else->DownloadFailure.INVALID},error.failure)
                if(mode=="redirect")assertEquals(307,error.httpStatus)
                assertEquals(1,f.exports.get())
            }finally{f.close()}
        }
    }
}
