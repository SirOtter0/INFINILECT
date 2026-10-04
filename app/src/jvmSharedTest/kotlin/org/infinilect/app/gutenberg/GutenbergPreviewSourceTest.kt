// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class GutenbergPreviewSourceTest {
    private val id=PublicationId(GUTENBERG_ID,"84")
    private fun jsonHeaders()=headersOf(HttpHeaders.ContentType,"application/json; charset=utf-8")
    @Test fun rootDiscoveryAndOneSearchOnlyExplicitNextPageAndNoLegacy()=runTest {
        val urls=mutableListOf<String>()
        val source=GutenbergSource(MockEngine{r->
            urls+=r.url.toString()
            assertEquals("INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)",r.headers[HttpHeaders.UserAgent]);assertEquals("identity",r.headers[HttpHeaders.AcceptEncoding])
            val body=when {r.url.toString()==GUTENBERG_ROOT->previewRoot;r.url.parameters["page"]=="2"->previewPage(page=2);else->previewPage(next=true)}
            respond(body,headers=jsonHeaders())
        })
        source.use {
            assertTrue(urls.isEmpty());val first=it.search(" books ");assertEquals(2,urls.size);assertEquals(GUTENBERG_ROOT,urls.first());assertEquals(GutenbergUrls.search("books"),urls.last())
            it.search("books",assertNotNull(first.nextPageToken));assertEquals(3,urls.size)
            assertEquals("2",Url(urls.last()).parameters["page"])
            it.search("books");assertEquals(4,urls.size);assertTrue(urls.all{it.startsWith(GUTENBERG_ROOT)})
        }
    }
    @Test fun invalidQueriesAndTokensDoNotEvenDiscoverRoot()=runTest {
        var requests=0
        GutenbergSource(MockEngine{requests++;error("No request")}).use {s->
            for(query in listOf(" ","x".repeat(257)))assertFailsWith<IllegalArgumentException>{s.search(query)}
            assertFailsWith<IllegalArgumentException>{s.search("books","gutenberg-opds-1:old")}
            assertEquals(0,requests)
        }
    }
    @Test fun encodingKeepsQueryAsOneParameter()=runTest {
        val query="café & page=88 / ?"
        GutenbergSource(MockEngine{r->
            if(r.url.toString()==GUTENBERG_ROOT)respond(previewRoot,headers=jsonHeaders())
            else {assertEquals(query,r.url.parameters["query"]);assertEquals(setOf("query"),r.url.parameters.names());respond(previewPage(query),headers=jsonHeaders())}
        }).use{it.search(query)}
    }
    @Test fun getPublicationUsesDevelopmentDetailAndValidatesOwnership()=runTest {
        var requests=0
        GutenbergSource(MockEngine{r->requests++;assertEquals(GutenbergUrls.publication("84"),r.url.toString());respond(previewPublication(),headers=headersOf(HttpHeaders.ContentType,"application/opds-publication+json"))}).use{s->
            assertEquals(id,s.getPublication(id)!!.id)
            assertFailsWith<IllegalArgumentException>{s.getPublication(id.copy(sourceId=SourceId("other")))}
            assertFailsWith<IllegalArgumentException>{s.getPublication(id.copy(localId="01"))}
            assertEquals(1,requests)
        }
    }
    @Test fun detailNotFoundAndWrongPublication()=runTest {
        GutenbergSource(MockEngine{respond("",HttpStatusCode.NotFound)}).use{assertNull(it.getPublication(id))}
        GutenbergSource(MockEngine{respond(previewPublication("11"),headers=jsonHeaders())}).use{assertFailsWith<InvalidOpdsException>{it.getPublication(id)}}
    }
    @Test fun loadResourceAlwaysExplicitlyUnsupportedWithoutTrafficIncludingForgedText()=runTest {
        var requests=0
        GutenbergSource(MockEngine{requests++;error("No acquisition")}).use{s->
            for(resource in listOf(PublicationResource(id,"epub",PublicationFormat.EPUB,"application/epub+zip"),PublicationResource(id,"text-utf8",PublicationFormat.TEXT,"text/plain; charset=utf-8"),PublicationResource(id,"https://evil",PublicationFormat.TEXT,"text/plain",revision="forged")))
                assertEquals(GUTENBERG_UNSUPPORTED,assertFailsWith<SearchException>{s.loadResource(resource)}.message)
            assertFailsWith<IllegalArgumentException>{s.loadResource(PublicationResource(PublicationId(SourceId("other"),"84"),"text",PublicationFormat.TEXT,"text/plain"))}
            assertEquals(0,requests)
        }
    }
    @Test fun rootMalformedDoesNotFallBackOrRequestSearch()=runTest {
        val urls=mutableListOf<String>()
        GutenbergSource(MockEngine{urls+=it.url.toString();respond("<feed/>",headers=jsonHeaders())}).use {assertFailsWith<InvalidOpdsException>{it.search("books")}}
        assertEquals(listOf(GUTENBERG_ROOT),urls)
    }
    @Test fun redirectsIncludingLegitimateHostLoopsAndErrorsNeverFollowedRetried()=runTest {
        for(status in listOf(301,302,307,308,429,503,500)) {
            var requests=0
            GutenbergSource(MockEngine{requests++;respond("",HttpStatusCode.fromValue(status),headersOf(HttpHeaders.Location,GUTENBERG_ROOT))}).use{s->assertFailsWith<SearchException>{s.search("books")}}
            assertEquals(1,requests)
        }
    }
    @Test fun wrongContentTypeCharsetCompressedAndHugeDeclaredBodyRejected()=runTest {
        val bad=listOf(headersOf(HttpHeaders.ContentType,"text/html"),headersOf(HttpHeaders.ContentType,"application/atom+xml"),headersOf(HttpHeaders.ContentType,"application/json; charset=latin1"),headersOf(HttpHeaders.ContentType to listOf("application/json"),HttpHeaders.ContentEncoding to listOf("gzip")),headersOf(HttpHeaders.ContentType to listOf("application/json"),HttpHeaders.ContentLength to listOf((MAX_FEED_BYTES+1).toString())),headersOf(HttpHeaders.ContentType to listOf("application/json"),HttpHeaders.ContentLength to listOf("-1")))
        for(headers in bad)GutenbergSource(MockEngine{respond(previewRoot,headers=headers)}).use{assertFailsWith<InvalidOpdsException>{it.search("books")}}
    }
    @Test fun actualOversizeAndMetadataLengthDisagreementRejected()=runTest {
        for(body in listOf("x".repeat(MAX_FEED_BYTES+1),previewRoot))
            GutenbergSource(MockEngine{respond(body,headers=headersOf(HttpHeaders.ContentType to listOf("application/json"),HttpHeaders.ContentLength to listOf("1")))}).use{assertFailsWith<InvalidOpdsException>{it.search("books")}}
    }
    @Test fun timeoutIsFixedSafeAndNotRetried()=runTest {
        var n=0
        GutenbergSource(MockEngine{n++;throw HttpRequestTimeoutException(it)}).use{val e=assertFailsWith<SearchException>{it.search("books")};assertFalse(e.message!!.contains("https:"))}
        assertEquals(1,n)
    }
    @Test fun cancellationReleasesRequestLockAndAllowsLaterExplicitAction()=runTest {
        val entered=CompletableDeferred<Unit>();var n=0
        GutenbergSource(MockEngine{r->n++;if(n==1){entered.complete(Unit);awaitCancellation()};respond(if(r.url.toString()==GUTENBERG_ROOT)previewRoot else previewPage(),headers=jsonHeaders())}).use{s->
            val first=async{s.search("books")};entered.await();first.cancelAndJoin()
            assertEquals(1,s.search("books").publications.size);assertEquals(3,n)
        }
    }
    @Test fun sourceCloseCancelsActiveRequestAndIsIdempotent()=runTest {
        val entered=CompletableDeferred<Unit>()
        val s=GutenbergSource(MockEngine{entered.complete(Unit);awaitCancellation()})
        val request=async{s.search("books")};entered.await();s.close();s.close()
        assertFailsWith<CancellationException>{request.await()}
        assertFailsWith<IllegalStateException>{s.search("books")}
    }
    @Test fun concurrentRequestsRemainSerialized()=runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var n=0
        GutenbergSource(MockEngine{r->n++;if(n==1){entered.complete(Unit);release.await()};respond(if(r.url.toString()==GUTENBERG_ROOT)previewRoot else previewPage(),headers=jsonHeaders())}).use{s->
            val a=async{s.search("books")};entered.await();val b=async{s.search("books")};runCurrent();assertEquals(1,n)
            release.complete(Unit);a.await();b.await();assertEquals(3,n)
        }
    }
}
