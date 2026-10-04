// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import io.ktor.utils.io.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.reader.*
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class GutenbergAcquisitionSourceTest {
    private val body="Hello world!".encodeToByteArray()
    private val resource=GutenbergAcquisition.resource(testGutenbergId)
    private fun source(engine: MockEngine)=GutenbergSource(engine,::testGutenbergXml)
    private fun headers(type: String=GUTENBERG_TEXT_MIME,size: String?="12",extra: Pair<String,List<String>>?=null)=headersOf(
        *(listOf(HttpHeaders.ContentType to listOf(type)) + listOfNotNull(size?.let{HttpHeaders.ContentLength to listOf(it)},extra)).toTypedArray())
    @Test fun getPublicationAnd404AreRealBoundedRequests()=runTest {
        var n=0
        source(MockEngine{request->n++;assertEquals(GutenbergAcquisition.metadata("84"),request.url.toString())
            respond(rdfFixture(),headers=headers("application/rdf+xml",null))}).use{assertEquals(testGutenbergId,it.getPublication(testGutenbergId)!!.id)}
        assertEquals(1,n)
        source(MockEngine{respond("",HttpStatusCode.NotFound)}).use{assertNull(it.getPublication(testGutenbergId))}
    }
    @Test fun freshMetadataThenStreamingWithStableLogicalResource()=runTest {
        var metadata=0;var downloads=0
        source(MockEngine{request->
            assertEquals("INFINILECT/0.0.1-SNAPSHOT (+https://github.com/SirOtter0/INFINILECT/issues)",request.headers[HttpHeaders.UserAgent])
            assertEquals("identity",request.headers[HttpHeaders.AcceptEncoding])
            if(request.url.encodedPath.endsWith(".rdf")){metadata++;respond(rdfFixture(),headers=headers("application/rdf+xml",null))}
            else{downloads++;assertEquals("/files/84/84-0.txt",request.url.encodedPath);respond(body,headers=headers())}
        }).use{s->val publication=s.getPublication(testGutenbergId)!!;assertEquals(resource,publication.resources.single())
            assertContentEquals(body,s.loadResource(resource).readBytes(12))}
        assertEquals(2,metadata);assertEquals(1,downloads)
    }
    @Test fun malformedForeignAndUrlResourcesMakeNoRequests()=runTest {
        var requests=0
        source(MockEngine{requests++;error("unexpected")}).use{s->
            assertFailsWith<IllegalArgumentException>{s.getPublication(testGutenbergId.copy(sourceId=SourceId("other")))}
            for(id in listOf("0","-1","01","2147483648","1?x"))assertFailsWith<IllegalArgumentException>{s.getPublication(testGutenbergId.copy(localId=id))}
            for(r in listOf(resource.copy(key="https://www.gutenberg.org/files/84/84-0.txt"),resource.copy(revision="invented"),resource.copy(format=PublicationFormat.PDF),resource.copy(mediaType="text/plain"),resource.copy(publicationId=testGutenbergId.copy(sourceId=SourceId("other")))))assertFailsWith<IllegalArgumentException>{s.loadResource(r)}
        };assertEquals(0,requests)
    }
    @Test fun nullRevisionCacheCannotBypassFreshMetadata()=runTest {
        val root=Files.createTempDirectory("gutenberg-cache-test");var metadata=0;var downloads=0;var permitted=true
        try{source(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf")){metadata++;respond(rdfFixture(mime=if(permitted)GUTENBERG_TEXT_MIME else "text/plain; charset=iso-8859-1"),headers=headers("application/rdf+xml",null))}
            else{downloads++;respond(body,headers=headers())}}).use{s->
            DiskResourceCache(root.resolve("cache"),ioDispatcher=StandardTestDispatcher(testScheduler)).use{cache->
                val loader=cache.loader(s.id,DirectResourceLoader(s))
                repeat(2){assertContentEquals(body,loader.load(resource).readBytes(12))}
                permitted=false;assertFailsWith<SearchException>{loader.load(resource)}
                assertEquals(3,metadata);assertEquals(2,downloads);assertFalse(Files.exists(root.resolve("cache")))
            }
        }}finally{root.toFile().deleteRecursively()}
    }
    @Test fun missingUtf8AndOversizedMetadataResourcePreventDownload()=runTest {
        for(xml in listOf(rdfFixture(mime="text/plain"),rdfFixture(size=MAX_TEXT_DOCUMENT_BYTES.toLong()+1))) {
            var n=0;source(MockEngine{n++;respond(xml,headers=headers("application/rdf+xml",null))}).use{s->assertFailsWith<SearchException>{s.loadResource(resource)}};assertEquals(1,n)
        }
    }
    @Test fun metadataMimeCompressionLengthAndBoundsRejected()=runTest {
        val invalid=listOf("text/html" to null,"application/rdf+xml" to "-1","application/rdf+xml" to "1","application/rdf+xml" to (MAX_FEED_BYTES+1).toString(),"application/rdf+xml" to "abc")
        for((mime,size)in invalid)source(MockEngine{respond(rdfFixture(),headers=headers(mime,size))}).use{s->assertFailsWith<InvalidOpdsException>{s.getPublication(testGutenbergId)}}
        source(MockEngine{respond(ByteArray(MAX_FEED_BYTES+1),headers=headers("application/rdf+xml",null))}).use{s->assertFailsWith<InvalidOpdsException>{s.getPublication(testGutenbergId)}}
        source(MockEngine{respond(rdfFixture(),headers=headers("application/rdf+xml",null,HttpHeaders.ContentEncoding to listOf("gzip")))}).use{s->assertFailsWith<InvalidOpdsException>{s.getPublication(testGutenbergId)}}
    }
    @Test fun resourceMimeCharsetLengthAndCompressionRejected()=runTest {
        val invalid=listOf(headers("text/html"),headers("text/plain"),headers("text/plain; charset=iso-8859-1"),headers(size=null),headers(size="11"),headers(size="13"),headers(size="-1"),headers(size="abc"),headers(size=(MAX_TEXT_DOCUMENT_BYTES+1).toString()),headers(extra=HttpHeaders.ContentEncoding to listOf("gzip")))
        for(h in invalid) source(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(),headers=headers("application/rdf+xml",null))else respond(body,headers=h)}).use{s->assertFailsWith<InvalidOpdsException>{s.loadResource(resource)}}
    }
    @Test fun shortAndExtraBodiesRejectedAndHandlesClosed()=runTest {
        for(bytes in listOf(body.copyOf(11),body+byteArrayOf(65))) source(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(),headers=headers("application/rdf+xml",null))else respond(bytes,headers=headers())}).use{s->
            val c=s.loadResource(resource);assertFailsWith<InvalidOpdsException>{c.readBytes(20)};assertFailsWith<IllegalStateException>{c.read(ByteArray(1))};c.close()
        }
    }
    @Test fun strictReaderRejectsMalformedUtf8AndCleansBacking()=runTest {
        val root=Files.createTempDirectory("gutenberg-bad-utf8");val preparer=FileTextPreparer(root,StandardTestDispatcher(testScheduler))
        try{source(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(),headers=headers("application/rdf+xml",null))else respond(ByteArray(12){0xff.toByte()},headers=headers())}).use{s->
            val p=s.getPublication(testGutenbergId)!!;assertEquals(TextFailure.INVALID_UTF8,assertFailsWith<TextDocumentException>{loadTextDocument(p,resource,DirectResourceLoader(s),StandardTestDispatcher(testScheduler),preparer)}.failure)
            assertEquals(0L,Files.walk(root).use{it.filter{p->p.toString().endsWith(".utf8")}.count()})
        }}finally{preparer.close();preparer.awaitClosed();root.toFile().deleteRecursively()}
    }
    @Test fun exactEofReadBoundsAndIdempotentClose()=runTest {
        source(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(),headers=headers("application/rdf+xml",null))else respond(body,headers=headers())}).use{s->
            val c=s.loadResource(resource);val buffer=ByteArray(14)
            assertEquals(0,c.read(buffer,14,0));assertFailsWith<IllegalArgumentException>{c.read(buffer,-1,1)}
            assertEquals(12,c.read(buffer,1,12));assertEquals(-1,c.read(buffer,1,1));c.close();c.close();assertFailsWith<IllegalStateException>{c.read(buffer)}
        }
    }
    @Test fun generatedRedirectIsItemScopedAndBounded()=runTest {
        val requests=mutableListOf<String>()
        source(MockEngine{request->requests+=request.url.encodedPath
            when{request.url.encodedPath.endsWith(".rdf")->respond(rdfFixture(url="https://www.gutenberg.org/ebooks/84.txt.utf-8"),headers=headers("application/rdf+xml",null))
                request.url.encodedPath.startsWith("/ebooks/")->respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"/cache/epub/84/pg84.txt"))
                else->respond(body,headers=headers())}
        }).use{s->assertContentEquals(body,s.loadResource(resource).readBytes(12))}
        assertEquals(listOf("/cache/epub/84/pg84.rdf","/ebooks/84.txt.utf-8","/cache/epub/84/pg84.txt"),requests)
    }
    @Test fun unsafeRedirectsDoNotMakeSecondDownloadRequest()=runTest {
        for(target in listOf("https://evil.invalid/x","http://www.gutenberg.org/files/84/84-0.txt","https://www.gutenberg.org/files/85/85-0.txt","https://www.gutenberg.org/files/84/other.txt","https://aleph.gutenberg.org/files/84/84-0.txt")) {
            var n=0;source(MockEngine{request->n++;if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(),headers=headers("application/rdf+xml",null))else respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,target))}).use{s->assertFailsWith<InvalidOpdsException>{s.loadResource(resource)}};assertEquals(2,n)
        }
    }
    @Test fun redirectSelfLoopAndTwoNodeLoopRejected()=runTest {
        for(two in listOf(false,true)) {
            var n=0;source(MockEngine{request->n++
                if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(url="https://www.gutenberg.org/ebooks/84.txt.utf-8"),headers=headers("application/rdf+xml",null))
                else respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,if(two&&request.url.encodedPath.startsWith("/ebooks/"))"/cache/epub/84/pg84.txt" else "/ebooks/84.txt.utf-8"))
            }).use{s->assertFailsWith<InvalidOpdsException>{s.loadResource(resource)}};assertEquals(if(two)3 else 2,n)
        }
    }
    @Test fun httpErrorsAreNotRetriedAndDoNotDownload()=runTest {
        for(status in listOf(HttpStatusCode.TooManyRequests,HttpStatusCode.ServiceUnavailable)){
            var n=0;source(MockEngine{n++;respond("private body",status)}).use{s->val error=assertFailsWith<SearchException>{s.loadResource(resource)};assertFalse(error.message!!.contains("private"))};assertEquals(1,n)
        }
    }
    @Test fun networkTimeoutIsSafeAndNoRetry()=runTest {
        var n=0;source(MockEngine{request->n++;throw HttpRequestTimeoutException(request.url.toString(),15_000)}).use{s->assertEquals("Project Gutenberg took too long to respond. Please try again.",assertFailsWith<SearchException>{s.getPublication(testGutenbergId)}.message)};assertEquals(1,n)
    }
    @Test fun cancellationWhileOpeningReleasesMutex()=runTest {
        val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>();var n=0
        source(MockEngine{n++;if(n==1){entered.complete(Unit);gate.await()};respond(rdfFixture(),headers=headers("application/rdf+xml",null))}).use{s->
            val job=async{s.loadResource(resource)};entered.await();job.cancelAndJoin();gate.complete(Unit)
            assertNotNull(s.getPublication(testGutenbergId));assertEquals(2,n)
        }
    }
    @Test fun earlyCloseReleasesSerializedAcquisition()=runTest {
        source(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(),headers=headers("application/rdf+xml",null))else respond(body,headers=headers())}).use{s->
            val c=s.loadResource(resource);c.read(ByteArray(1));c.close();assertNotNull(s.getPublication(testGutenbergId))
        }
    }
    @Test fun sourceCloseAbortsOwnedContentAndRejectsRequests()=runTest {
        val s=source(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(),headers=headers("application/rdf+xml",null))else respond(body,headers=headers())})
        val c=s.loadResource(resource);s.close();s.close();assertFails{c.readBytes(12)}
        assertFailsWith<IllegalStateException>{s.getPublication(testGutenbergId)}
    }
    @Test fun cancellationDuringReadClosesContent()=runTest {
        val channel=ByteChannel();val c=GutenbergResourceContent(channel,12,GutenbergHttpEvidence("",200,null)){}
        val job=async{c.read(ByteArray(1))};runCurrent();job.cancelAndJoin();assertTrue(channel.isClosedForRead)
        assertFailsWith<IllegalStateException>{c.read(ByteArray(1))}
    }
}
