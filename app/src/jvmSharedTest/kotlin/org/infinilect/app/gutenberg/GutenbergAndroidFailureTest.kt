// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.net.*
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertificateException
import io.ktor.client.plugins.HttpRequestTimeoutException
import org.infinilect.app.*
import org.infinilect.app.discovery.*
import org.infinilect.app.archive.InternetArchiveSource
import org.infinilect.app.search.SearchException
import kotlin.test.*

/** Reproduces lost diagnostic distinctions, not an unobserved physical Android TLS cause. */
class GutenbergAndroidFailureTest {
    @Test fun certificateFailureHasSpecificSafeMessage() = runTest {
        GutenbergSource(MockEngine { throw SSLHandshakeException("private query/path") }).use { source ->
            val error=assertFailsWith<SearchException>{source.search("cervantes")}
            assertTrue(error.message.orEmpty().contains("secure connection"), error.message)
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }
    @Test fun forbiddenHttpResponseHasSpecificSafeMessage() = runTest {
        GutenbergSource(MockEngine { respond("private publication", HttpStatusCode.Forbidden) }).use { source ->
            val error=assertFailsWith<SearchException>{source.search("cervantes")}
            assertTrue(error.message.orEmpty().contains("blocked"), error.message)
        }
    }
    @Test fun invalidJsonIsDistinguishedFromInvalidOpdsStructure() = runTest {
        GutenbergSource(MockEngine { respond("{invalid}",headers=headersOf(HttpHeaders.ContentType,"application/json")) }).use { source ->
            val error=assertFailsWith<SearchException>{source.search("cervantes")}
            assertTrue(error.message.orEmpty().contains("invalid JSON"), error.message)
        }
    }
    @Test fun missingSearchLinkHasSpecificSafeMessage() = runTest {
        GutenbergSource(MockEngine { respond(previewRoot.replace("\"search\"","\"other\""),headers=headersOf(HttpHeaders.ContentType,"application/json")) }).use { source ->
            val error=assertFailsWith<SearchException>{source.search("cervantes")}
            assertTrue(error.message.orEmpty().contains("search link"), error.message)
        }
    }
    private fun headers()=headersOf(HttpHeaders.ContentType,"application/json; charset=utf-8")
    @Test fun dnsConnectionTlsAndNestedFailuresKeepCategoriesWithoutMessages() = runTest {
        val cases=listOf(UnknownHostException("private") to CatalogErrorKind.CONNECTION,
            ConnectException("private") to CatalogErrorKind.CONNECTION,
            SocketException("private") to CatalogErrorKind.CONNECTION,
            IOException("private") to CatalogErrorKind.CONNECTION,
            NoRouteToHostException("private") to CatalogErrorKind.CONNECTION,
            UnresolvedAddressException() to CatalogErrorKind.CONNECTION,
            SSLHandshakeException("private") to CatalogErrorKind.TLS,
            IOException("private",CertificateException("private")) to CatalogErrorKind.TLS,
            SocketTimeoutException("private") to CatalogErrorKind.TIMEOUT,
            IllegalStateException("private") to CatalogErrorKind.INTERNAL)
        for((exception,kind) in cases) {
            val events=mutableListOf<GutenbergDiagnostic>()
            GutenbergSource(MockEngine {throw exception},diagnostics=events::add).use {source ->
                val error=assertFailsWith<CatalogSourceException>{source.search("private query")}
                assertEquals(kind,error.kind)
                // Coroutine stack recovery may copy Throwable; preserve the cause category/class, not object identity.
                assertTrue(generateSequence(error.cause) {it.cause}.take(8).any {it.javaClass==exception.javaClass})
                val event=events.single();assertEquals(kind,event.failure);assertEquals(GutenbergStage.ROOT,event.stage)
                assertEquals("/opds/",event.path);assertNull(event.status);assertFalse(event.cancelled)
                assertFalse(events.toString().contains("private"));assertFalse(error.message.orEmpty().contains("private"))
                assertTrue(event.durationMillis>=0)
            }
        }
    }
    @Test fun ktorTimeoutIsClassifiedWithoutAutomaticRetry()=runTest {
        var calls=0
        GutenbergSource(MockEngine {calls++;throw HttpRequestTimeoutException(it)}).use {source->
            assertEquals(CatalogErrorKind.TIMEOUT,assertFailsWith<CatalogSourceException>{source.search("books")}.kind)
        }
        assertEquals(1,calls)
    }
    @Test fun httpFailuresAndRedirectsPreserveStatusWithoutFollowingLocation()=runTest {
        for((code,kind) in listOf(301 to CatalogErrorKind.REDIRECT,302 to CatalogErrorKind.REDIRECT,
            307 to CatalogErrorKind.REDIRECT,308 to CatalogErrorKind.REDIRECT,
            403 to CatalogErrorKind.HTTP_FORBIDDEN,429 to CatalogErrorKind.HTTP_RATE_LIMITED,
            500 to CatalogErrorKind.HTTP_ERROR,503 to CatalogErrorKind.HTTP_ERROR)) {
            val events=mutableListOf<GutenbergDiagnostic>();var calls=0
            GutenbergSource(MockEngine {calls++;respond("private body",HttpStatusCode.fromValue(code),headersOf(HttpHeaders.Location,"https://outside.invalid/private"))},diagnostics=events::add).use {source->
                assertEquals(kind,assertFailsWith<CatalogSourceException>{source.search("private query")}.kind)
            }
            assertEquals(1,calls);assertEquals(code,events.single().status);assertEquals(kind,events.single().failure)
            assertFalse(events.toString().contains("private"));assertFalse(events.toString().contains("outside"))
        }
    }
    @Test fun contentTypeCharsetCompressionAndBoundsAreClassifiedWithoutRelaxingLimits()=runTest {
        for(header in listOf(headersOf(HttpHeaders.ContentType,"text/html"),headersOf(HttpHeaders.ContentType,"application/atom+xml"),
            headersOf(HttpHeaders.ContentType,"application/json; charset=latin1"),
            headersOf(HttpHeaders.ContentType to listOf("application/json"),HttpHeaders.ContentEncoding to listOf("gzip")),
            headersOf(HttpHeaders.ContentType to listOf("application/json"),HttpHeaders.ContentLength to listOf((MAX_FEED_BYTES+1).toString())))) {
            val events=mutableListOf<GutenbergDiagnostic>()
            GutenbergSource(MockEngine {respond(previewRoot,headers=header)},diagnostics=events::add).use {source->
                assertEquals(CatalogErrorKind.RESPONSE_FORMAT,assertFailsWith<InvalidOpdsException>{source.search("books")}.kind)
            }
            assertEquals(GutenbergParsingStage.HEADERS,events.single().parsing);assertEquals(200,events.single().status)
        }
    }
    @Test fun malformedJsonMissingFieldsAndBadSearchLinkExposeParsingStage()=runTest {
        for((body,kind) in listOf("{invalid}" to CatalogErrorKind.INVALID_JSON,
            "{}" to CatalogErrorKind.INVALID_OPDS,
            previewRoot.replace("\"search\"","\"other\"") to CatalogErrorKind.SEARCH_LINK,
            previewRoot.replace(GUTENBERG_SEARCH_TEMPLATE,"https://outside.invalid/search{?query}") to CatalogErrorKind.SEARCH_LINK)) {
            val events=mutableListOf<GutenbergDiagnostic>()
            GutenbergSource(MockEngine {respond(body,headers=headers())},diagnostics=events::add).use {source->
                assertEquals(kind,assertFailsWith<InvalidOpdsException>{source.search("books")}.kind)
            }
            assertEquals(200,events.first().status);assertEquals(kind,events.last().failure)
            assertEquals(when(kind){CatalogErrorKind.INVALID_JSON->GutenbergParsingStage.JSON;CatalogErrorKind.SEARCH_LINK->GutenbergParsingStage.SEARCH_LINK;else->GutenbergParsingStage.DOCUMENT},events.last().parsing)
        }
    }
    @Test fun searchFailureIsIdentifiedAfterSuccessfulRootDiscoveryAndRetryDoesNotRediscover()=runTest {
        val events=mutableListOf<GutenbergDiagnostic>();var rootCalls=0;var searchCalls=0
        GutenbergSource(MockEngine {request->
            if(request.url.toString()==GUTENBERG_ROOT){rootCalls++;respond(previewRoot,headers=headers())}
            else {searchCalls++;if(searchCalls==1)respond("",HttpStatusCode.ServiceUnavailable) else respond(previewPage("cervantes"),headers=headers())}
        },diagnostics=events::add).use {source->
            assertEquals(CatalogErrorKind.HTTP_ERROR,assertFailsWith<CatalogSourceException>{source.search("cervantes")}.kind)
            assertEquals(GutenbergStage.SEARCH,events.last().stage);assertEquals(503,events.last().status)
            assertEquals(1,source.search("cervantes").publications.size)
        }
        assertEquals(1,rootCalls);assertEquals(2,searchCalls);assertEquals(GutenbergParsingStage.COMPLETE,events.last().parsing)
    }
    @Test fun failedRootIsRetriedBeforeAnySearchAndNoAcquisitionOccurs()=runTest {
        var rootCalls=0;val paths=mutableListOf<String>()
        GutenbergSource(MockEngine {request->
            paths+=request.url.encodedPath
            if(request.url.toString()==GUTENBERG_ROOT){rootCalls++;if(rootCalls==1)respond("",HttpStatusCode.TooManyRequests) else respond(previewRoot,headers=headers())}
            else respond(previewPage(),headers=headers())
        }).use {source->
            assertFailsWith<CatalogSourceException>{source.search("books")}
            assertTrue(source.search("books").publications.single().resources.isEmpty())
        }
        assertEquals(listOf("/opds/","/opds/","/opds/search"),paths)
    }
    @Test fun absoluteOwnedLinksWorkAndRelativeOrExternalTemplatesRemainExplicitlyUnsupported()=runTest {
        assertEquals(GUTENBERG_SEARCH_TEMPLATE,GutenbergOpds2Parser.root(previewBytes(previewRoot)))
        for(template in listOf("search{?query,title,author}","/opds/search{?query,title,author}","http://opds-test.pglaf.org/opds/search{?query,title,author}")) {
            val error=assertFailsWith<InvalidOpdsException>{GutenbergOpds2Parser.root(previewBytes(previewRoot.replace(GUTENBERG_SEARCH_TEMPLATE,template)))}
            assertEquals(CatalogErrorKind.SEARCH_LINK,error.kind)
        }
    }
    @Test fun queryEncodingAndLanguageFiltersDoNotChangeTheOwnedSearchRoute()=runTest {
        val query="Cervantes & title=private / café ?"
        val paths=mutableListOf<String>()
        GutenbergSource(MockEngine {request->
            paths+=request.url.encodedPath
            if(request.url.toString()==GUTENBERG_ROOT)respond(previewRoot,headers=headers())
            else {assertEquals(query,request.url.parameters["query"]);assertEquals(setOf("query"),request.url.parameters.names());respond(previewPage(query),headers=headers())}
        }).use {source->
            assertEquals(1,source.discover(DiscoveryRequest(query,language="es")).entries.size)
        }
        assertEquals(listOf("/opds/","/opds/search"),paths)
    }
    @Test fun paginationIsReportedAsItsOwnStageWithoutQueriesInDiagnostics()=runTest {
        val events=mutableListOf<GutenbergDiagnostic>()
        GutenbergSource(MockEngine {request->respond(if(request.url.toString()==GUTENBERG_ROOT)previewRoot else previewPage("private",page=if(request.url.parameters["page"]=="2")2 else 1,next=request.url.parameters["page"]==null),headers=headers())},diagnostics=events::add).use {source->
            val first=source.search("private");source.search("private",assertNotNull(first.nextPageToken))
        }
        assertEquals(GutenbergStage.PAGINATION,events.last().stage);assertEquals(GutenbergParsingStage.COMPLETE,events.last().parsing)
        assertFalse(events.toString().contains("private"))
    }
    @Test fun callerCancellationIsLoggedButRethrownAndReleasesTheSourceForRetry()=runTest {
        val entered=CompletableDeferred<Unit>();val events=mutableListOf<GutenbergDiagnostic>();var calls=0
        GutenbergSource(MockEngine {request->calls++;if(calls==1){entered.complete(Unit);awaitCancellation()};respond(if(request.url.toString()==GUTENBERG_ROOT)previewRoot else previewPage(),headers=headers())},diagnostics=events::add).use {source->
            val request=async{source.search("books")};entered.await();request.cancelAndJoin()
            assertTrue(events.any {it.cancelled && it.failure==CatalogErrorKind.CANCELLED})
            assertEquals(1,source.search("books").publications.size)
        }
    }
    @Test fun throwingDiagnosticObserverCannotChangeSuccessfulSearchOrFailureCategory()=runTest {
        GutenbergSource(MockEngine {request->respond(if(request.url.toString()==GUTENBERG_ROOT)previewRoot else previewPage(),headers=headers())},diagnostics={error("private diagnostic consumer")}).use {source->
            assertEquals(1,source.search("books").publications.size)
        }
        GutenbergSource(MockEngine {throw SSLHandshakeException("private")},diagnostics={error("private diagnostic consumer")}).use {source->
            assertEquals(CatalogErrorKind.TLS,assertFailsWith<CatalogSourceException>{source.search("books")}.kind)
        }
    }
    @Test fun independentArchiveResultsSurviveGutenbergFailureAndExplicitRetryRecovers()=runTest { withContext(Dispatchers.Default) {
        // Real parser work runs on Default; do not let the virtual timeout outrun that dispatcher.
        var fail=true
        val gutenberg=GutenbergSource(MockEngine {request->
            if(fail)throw SSLHandshakeException("private")
            respond(if(request.url.toString()==GUTENBERG_ROOT)previewRoot else previewPage("cervantes"),headers=headers())
        })
        val archive=InternetArchiveSource(MockEngine {respond("""{"response":{"numFound":1,"docs":[{"identifier":"original-fixture","title":"Original Cervantes fixture"}]}}""",headers=headers())})
        val catalog=DiscoveryCatalog(listOf(archive,gutenberg),this){0}
        val controller=DiscoveryController(listOf(SourceOption("Archive",archive),SourceOption("Gutenberg",gutenberg)),catalog,this)
        try {
            controller.edit("cervantes");controller.submit()
            val first=controller.state.first{it.request!=null && !it.loading}
            assertEquals(archive.id,first.entries.single().publication.id.sourceId)
            assertEquals(CatalogErrorKind.TLS,first.catalogs.single{it.id==gutenberg.id}.failure)
            fail=false;controller.retry(gutenberg.id)
            val recovered=controller.state.first{!it.loading && it.catalogs.none{result->result.failed}}
            assertEquals(setOf(archive.id,gutenberg.id),recovered.entries.map{it.publication.id.sourceId}.toSet())
            assertTrue(recovered.entries.all {it.publication.resources.isEmpty()})
        } finally {controller.close();catalog.close();archive.close();gutenberg.close()}
    } }
}
