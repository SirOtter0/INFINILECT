// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.nio.file.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.infinilect.app.discovery.*
import org.infinilect.app.downloads.*
import org.infinilect.app.epub.*
import org.infinilect.app.reader.*
import org.infinilect.core.*
import kotlin.test.*

/** Production adapter/transport with synthetic HTTP and original book fixtures.
 * Only the explicit live task validates the real reviewed Wikisource book. */
class WikisourceSourceTest {
    internal class Fixture(val approval:WikisourceApproval,val zip:ByteArray) {
        @Volatile var revision=approval.revision
        @Volatile var metadataBody:String?=null
        @Volatile var status=200
        @Volatile var exportBytes=zip
        @Volatile var exportType="application/epub+zip"
        @Volatile var exportStatus=200
        @Volatile var exportLength:Long?=null
        @Volatile var afterExport:(()->Unit)?=null
        var beforeExport:suspend ()->Unit={}
        @Volatile var rows=listOf(approval.pageId,approval.pageId,99999)
        @Volatile var next:Int?=null
        @Volatile var clock=1000L
        @Volatile var requestedSearch:Url?=null
        val requests=AtomicInteger();val exports=AtomicInteger()
        val engine=MockEngine {request ->
            requests.incrementAndGet()
            assertEquals(URLProtocol.HTTPS,request.url.protocol)
            assertEquals("INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)",request.headers[HttpHeaders.UserAgent])
            when(request.url.host) {
                "ws-export.wmcloud.org" -> {
                    exports.incrementAndGet();assertEquals("epub-3",request.url.parameters["format"])
                    beforeExport()
                    afterExport?.invoke()
                    respond(exportBytes,HttpStatusCode.fromValue(exportStatus),headersOf(HttpHeaders.ContentType to listOf(exportType),
                        HttpHeaders.ContentLength to listOf((exportLength ?: exportBytes.size.toLong()).toString()),HttpHeaders.Location to listOf("https://127.0.0.1/private")))
                }
                "es.wikisource.org" -> {
                    assertEquals("/w/api.php",request.url.encodedPath)
                    if(request.url.parameters["list"]=="search")requestedSearch=request.url
                    if(status!=200)respond("{}",HttpStatusCode.fromValue(status),headersOf(HttpHeaders.Location,"https://127.0.0.1/private"))
                    else {
                        val json=metadataBody ?: if(request.url.parameters["list"]=="search") buildJsonObject {
                            putJsonObject("query") {putJsonArray("search") {rows.forEach {id->add(buildJsonObject {put("pageid",id);put("ns",0);put("title","ignored untrusted search text")})}}}
                            next?.let {putJsonObject("continue") {put("sroffset",it);put("continue","-||")}}
                        }.toString() else metadata(approval,revision)
                        respond(json,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
                    }
                }
                else -> error("No other host is authorized")
            }
        }
        val source=WikisourceSource(engine,{clock},listOf(approval))
    }
    companion object {
        internal fun metadata(approval:WikisourceApproval,revision:Int=approval.revision)=buildJsonObject {
            put("batchcomplete",true)
            putJsonObject("query") {putJsonArray("pages") {add(buildJsonObject {
                put("pageid",approval.pageId);put("ns",0);put("lastrevid",revision);put("title",approval.title);put("pagelanguage","es");put("fullurl",approval.sourceUrl)
                putJsonArray("revisions") {add(buildJsonObject {put("revid",revision)})}
            })}}
        }.toString()
    }
    private fun realFixture()=Fixture(wikisourceApprovals.single(),byteArrayOf(1,2,3))
    @Test fun unicodeQueriesCannotChangeTheOfficialRouteOrInjectParameters()=runBlocking<Unit> {
        val f=realFixture()
        try {
            val query="café 世界 +&maxlag=0#?"
            f.source.search(query)
            val url=assertNotNull(f.requestedSearch)
            assertEquals("/w/api.php",url.encodedPath)
            assertEquals("es.wikisource.org",url.host)
            assertEquals("5",url.parameters["maxlag"])
            assertEquals("intitle:\"$query\"",url.parameters["srsearch"])
            assertEquals(setOf("action","format","formatversion","maxlag","list","srnamespace","srlimit","sroffset","srsearch"),url.parameters.names())
            assertEquals("",url.fragment)
        }finally{f.source.close();f.source.awaitClosed()}
    }
    @Test fun officialTitleResultsAreFilteredByReviewedIdentityDeduplicatedAndPaginated()=runBlocking<Unit> {
        val f=realFixture()
        try {
            f.next=25
            val first=f.source.search("El Matadero")
            assertEquals(listOf("1425"),first.publications.map {it.id.localId})
            assertEquals(listOf("Esteban Echeverría"),first.publications.single().authors)
            assertNull(first.publications.single().resources.single().revision)
            val token=assertNotNull(first.nextPageToken);assertEquals(25,wikiOffset("El Matadero",token))
            f.rows=emptyList();f.next=null
            assertTrue(f.source.search("El Matadero",token).publications.isEmpty())
            val calls=f.requests.get()
            assertFails {f.source.search("Other",token)};assertEquals(calls,f.requests.get())
            assertFails {f.source.search("El Matadero","https://example.org/private")};assertEquals(calls,f.requests.get())
        } finally {f.source.close();f.source.awaitClosed()}
    }
    @Test fun authorLookupIsLimitedToReviewedFactsAndFreshMetadataNotArbitraryPages()=runBlocking<Unit> {
        val f=realFixture()
        try {
            assertEquals("1425",f.source.search("ECHEVERRIA").publications.single().id.localId)
            assertEquals(1,f.requests.get())
            assertEquals(1,f.source.discover(DiscoveryRequest("*")).entries.size)
            val calls=f.requests.get()
            assertTrue(f.source.discover(DiscoveryRequest("book",language="en")).entries.isEmpty());assertEquals(calls,f.requests.get())
            assertEquals(DownloadFailure.RIGHTS_UNVERIFIED,assertFailsWith<DownloadException>{f.source.downloadOffers(PublicationId(WIKISOURCE_ID,"999"))}.failure)
        } finally {f.source.close();f.source.awaitClosed()}
    }
    @Test fun revisionDisambiguationCanonicalUrlAndNamespaceCannotAuthorizeADifferentWork()=runBlocking<Unit> {
        val approval=wikisourceApprovals.single()
        for(change in listOf<(String)->String>(
            {it.replace("\"lastrevid\":1393104","\"lastrevid\":1393105")},
            {it.replace("\"ns\":0","\"ns\":104")},
            {it.replace(approval.sourceUrl,"https://example.org/book")},
            {it.replace("\"title\":\"El Matadero\"","\"title\":\"El Matadero/Chapter\"")},
            {it.replace("\"revisions\":","\"pageprops\":{\"disambiguation\":\"\"},\"revisions\":")})) {
            val f=realFixture()
            try {f.metadataBody=change(metadata(approval));assertEquals(DownloadFailure.RIGHTS_UNVERIFIED,assertFailsWith<DownloadException>{f.source.downloadOffers(PublicationId(WIKISOURCE_ID,"1425"))}.failure);assertEquals(0,f.exports.get())}
            finally {f.source.close();f.source.awaitClosed()}
        }
    }
    @Test fun malformedMissingAndOversizedJsonFailWithoutExposingProviderMessages()=runBlocking<Unit> {
        for(body in listOf("{\"query\":{},\"query\":{}}","{\"query\":{},\"\\u0071uery\":{}}","<html>no JSON</html>","{}","{\"query\":{\"search\":[{\"ns\":0,\"pageid\":\"1425\"}]}}","{"+" ".repeat(131072)+"}","[".repeat(25)+"]".repeat(25),"{\"error\":{\"code\":\"private\",\"info\":\"private text\"}}")) {
            val f=realFixture()
            try {f.metadataBody=body;val error=assertFailsWith<CatalogSourceException>{f.source.search("El Matadero")};assertEquals(CatalogErrorKind.INVALID_JSON,error.kind);assertFalse(error.message!!.contains("private text"))}
            finally {f.source.close();f.source.awaitClosed()}
        }
    }
    @Test fun redirectsForbiddenResponsesAndRateLimitingNeverFollowUntrustedLocations()=runBlocking<Unit> {
        for(status in listOf(302,403,429,500)) {
            val f=realFixture()
            try {
                f.status=status;val error=assertFailsWith<CatalogSourceException>{f.source.search("El Matadero")}
                assertEquals(when(status){302->CatalogErrorKind.REDIRECT;403->CatalogErrorKind.HTTP_FORBIDDEN;429->CatalogErrorKind.HTTP_RATE_LIMITED;else->CatalogErrorKind.HTTP_ERROR},error.kind)
                assertEquals(1,f.requests.get())
                if(status==429) {assertFails {f.source.search("El Matadero")};assertEquals(1,f.requests.get());f.clock+=31000;f.status=200;assertEquals(1,f.source.search("El Matadero").publications.size)}
            } finally {f.source.close();f.source.awaitClosed()}
        }
    }
    @Test fun apiBusyWithHttp200IsNotAnEmptyResultAndHasABoundedCooldown()=runBlocking<Unit> {
        val f=realFixture()
        try {
            f.metadataBody="{\"error\":{\"code\":\"cirrussearch-too-busy-error\",\"info\":\"ignored\"}}"
            assertEquals(CatalogErrorKind.HTTP_RATE_LIMITED,assertFailsWith<CatalogSourceException>{f.source.search("El Matadero")}.kind)
            assertFails {f.source.search("El Matadero")};assertEquals(1,f.requests.get())
        } finally {f.source.close();f.source.awaitClosed()}
    }
    @Test fun cancellationRetiresTheOwnedRequestAndCloseRejectsNewOperations()=runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val engine=MockEngine {entered.complete(Unit);try {awaitCancellation()}finally{cancelled.complete(Unit)}}
        val source=WikisourceSource(engine)
        try {
            val job=launch {source.search("El Matadero")};withTimeout(5000){entered.await()};job.cancelAndJoin()
            withTimeout(5000){cancelled.await()}
            source.close();source.awaitClosed();assertFailsWith<IllegalStateException>{source.search("El Matadero")}
        } finally {source.close();source.awaitClosed()}
    }
    @Test fun exportRequiresAFreshApprovalAndRejectsHtmlOrAChangedRevisionAtEof()=runBlocking<Unit> {
        for(changeRevision in listOf(false,true)) {
            val f=realFixture()
            try {
                val offer=f.source.downloadOffers(PublicationId(WIKISOURCE_ID,"1425")).single()
                if(changeRevision)f.afterExport={f.revision++} else f.exportType="text/html"
                val error=assertFailsWith<DownloadException>{
                    val content=f.source.openDownload(offer)
                    try {val buffer=ByteArray(32);while(content.read(buffer,0,buffer.size)>=0) {}}finally {content.close()}
                }
                assertEquals(if(changeRevision)DownloadFailure.RIGHTS_UNVERIFIED else DownloadFailure.INVALID,error.failure)
            } finally {f.source.close();f.source.awaitClosed()}
        }
    }
    @Test fun exportRedirectsTruncatedAndOversizedBodiesDoNotEscapeThePolicy()=runBlocking<Unit> {
        for(mode in listOf("redirect","truncated","oversized")) {
            val f=realFixture()
            try {
                val offer=f.source.downloadOffers(PublicationId(WIKISOURCE_ID,"1425")).single()
                when(mode) {"redirect"->f.exportStatus=307;"truncated"->f.exportLength=100;else->f.exportLength=WIKISOURCE_BYTES+1}
                val error=assertFailsWith<DownloadException>{
                    val content=f.source.openDownload(offer)
                    try {val b=ByteArray(32);while(content.read(b,0,b.size)>=0){}}finally{content.close()}
                }
                assertEquals(when(mode){"redirect"->DownloadFailure.HTTP;"truncated"->DownloadFailure.INCOMPLETE;else->DownloadFailure.INVALID},error.failure)
                if(mode=="redirect")assertEquals(307,error.httpStatus)
                assertEquals(1,f.exports.get())
            }finally{f.source.close();f.source.awaitClosed()}
        }
    }
}
