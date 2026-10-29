// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.infinilect.app.discovery.*
import org.infinilect.app.downloads.*
import org.infinilect.app.search.SearchException
import org.infinilect.core.*
import kotlin.test.*

class OapenSourceTest {
    private val id = PublicationId(OAPEN_SOURCE_ID, "20.500.12657/25287")
    private fun record() = checkNotNull(javaClass.getResourceAsStream("/oapen/public-record.xml")).use { it.readBytes() }
    private fun parse(text: String) = oapenMetadata(text.toByteArray(), id)
    private fun xml(body: ByteArray = record()) = MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "text/xml;charset=UTF-8")) }

    @Test fun observedPublicRecordMapsBibliographyWithoutAuthorizingAnyResource() {
        val b = assertNotNull(oapenMetadata(record(), id))
        assertEquals("The deliverance of open access books", b.publication.title)
        assertEquals(listOf("Snijder, Ronald"), b.publication.authors)
        assertEquals(listOf("eng"), b.publication.languages)
        assertEquals(PublicationType.BOOK, b.publication.type)
        assertEquals("https://library.oapen.org/handle/20.500.12657/25287", b.publication.sourceUrl)
        assertEquals("Amsterdam University Press", b.publisher)
        assertEquals("2019", b.date)
        assertTrue("10.26530/OAPEN_1004809" in b.identifiers)
        assertTrue("9789085551201" in b.identifiers)
        assertTrue(b.subjects.isNotEmpty()); assertNotNull(b.description)
        assertEquals(listOf("application/pdf"), b.announcedFormats)
        assertTrue(assertNotNull(b.publication.rights).contains("CC-BY-NC"))
        assertTrue(b.publication.resources.isEmpty())
    }
    @Test fun metadataCanBeIncompleteAndDoesNotInventMissingBibliography() {
        val minimal = """<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"><GetRecord><record><header><identifier>oai:library.oapen.org:${id.localId}</identifier></header><metadata><metadata xmlns="http://www.lyncode.com/xoai"><element name="dc"><element name="title"><element name="none"><field name="value">Un títol 日本語 📖</field></element></element></element></metadata></metadata></record></GetRecord></OAI-PMH>"""
        val b = assertNotNull(parse(minimal))
        assertEquals("Un títol 日本語 📖", b.publication.title)
        assertTrue(b.publication.authors.isEmpty()); assertTrue(b.publication.languages.isEmpty())
        assertNull(b.publisher); assertNull(b.date); assertNull(b.description); assertNull(b.publication.rights)
        assertTrue(b.identifiers.isEmpty()); assertTrue(b.announcedFormats.isEmpty())
    }
    @Test fun officialNotFoundAndDeletedRecordsAreEmptyNotInventedPublications() {
        assertNull(parse("""<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"><error code="idDoesNotExist">Missing</error></OAI-PMH>"""))
        val deleted=record().toString(Charsets.UTF_8).replace("<header>","<header status=\"deleted\">")
        assertNull(parse(deleted))
    }
    @Test fun rejectsWrongIdentityNamespaceMissingTitleAndMalformedXml() {
        val original=record().toString(Charsets.UTF_8)
        for (text in listOf(original.replace("25287", "25288"), original.replace("http://www.lyncode.com/xoai", "https://evil.example"),
            original.replace("name=\"title\"", "name=\"unrecognized\""), "<broken>", "[]",
            """<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"><error code="badArgument">idDoesNotExist</error></OAI-PMH>"""))
            assertFails { parse(text) }
    }
    @Test fun blocksXmlEntitiesDeepTreesOversizedFieldsAndBytes() {
        for (text in listOf("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>",
            "<x>".repeat(33)+"</x>".repeat(33), "<x>"+"z".repeat(16_385)+"</x>",
            "<x>"+"<y/>".repeat(4096)+"</x>")) assertFails { parse(text) }
        assertFails { oapenMetadata(ByteArray(OAPEN_METADATA_LIMIT+1),id) }
        assertFails { oapenMetadata(byteArrayOf(0xc3.toByte(), 0x28),id) }
    }
    @Test fun parserCancellationPropagates() {
        assertFailsWith<CancellationException> { oapenMetadata(record(), id) { throw CancellationException() } }
    }
    @Test fun unsafeMetadataLinksRemainInertNeverCopiedIntoResourceOrSourceUrl() {
        val text=record().toString(Charsets.UTF_8)
            .replace("https://library.oapen.org/bitstream/", "http://127.0.0.1/private/")
            .replace("http://library.oapen.org/handle/", "file:///private/")
        val publication=assertNotNull(parse(text)).publication
        assertEquals(oapenPage(id),publication.sourceUrl); assertTrue(publication.resources.isEmpty())
    }
    @Test fun oneIdentifiedGetRecordUsesFixedHttpsHostAndNoCatalogOrFileRequests()=runTest {
        var calls=0
        OapenSource(MockEngine { request ->
            calls++
            assertEquals("https",request.url.protocol.name); assertEquals("library.oapen.org",request.url.host)
            assertEquals("/oai/request",request.url.encodedPath)
            assertEquals(setOf("verb","identifier","metadataPrefix"),request.url.parameters.names())
            assertEquals("GetRecord",request.url.parameters["verb"])
            assertEquals("oai:library.oapen.org:${id.localId}",request.url.parameters["identifier"])
            assertEquals("xoai",request.url.parameters["metadataPrefix"])
            assertEquals("identity",request.headers[HttpHeaders.AcceptEncoding])
            assertNotNull(request.headers[HttpHeaders.UserAgent])
            respond(record(),headers=headersOf(HttpHeaders.ContentType,"application/xml"))
        }).use { source ->
            val page=source.search("https://library.oapen.org/handle/${id.localId}")
            assertEquals(listOf(id),page.publications.map {it.id}); assertNull(page.nextPageToken)
            assertEquals(1,calls)
        }
    }
    @Test fun unsupportedTextQueriesAndPaginationDoNotFetchOrHarvest()=runTest {
        OapenSource(MockEngine {error("No request allowed")}).use { source ->
            for (q in listOf("water", "author:Snijder", "10.26530/OAPEN_1004809", "", "20.500.12657/../secret", "https://evil.example/handle/${id.localId}"))
                assertEquals(OAPEN_LOOKUP_HELP,assertFailsWith<SearchException> {source.search(q)}.message)
            assertFailsWith<IllegalArgumentException> {source.search(id.localId,"https://127.0.0.1/")}
            assertFailsWith<IllegalArgumentException> {source.getPublication(id.copy(sourceId=SourceId("internet-archive")))}
        }
    }
    @Test fun noOfflineCapabilityEvenWithClaimedCreativeCommonsAndPdf()=runTest {
        OapenSource(xml()).use { source ->
            assertFalse((source as Any) is OfflineDownloadProvider)
            val p=assertNotNull(source.getPublication(id)); assertTrue(p.resources.isEmpty())
            assertFailsWith<SearchException> {source.loadResource(PublicationResource(id,"https://evil.example/book.pdf",PublicationFormat.PDF,"application/pdf"))}
            assertNotEquals(offlineKey(id),offlineKey(id.copy(sourceId=SourceId("internet-archive"))))
        }
    }
    @Test fun httpDenialsErrorsAndRedirectsNeverFollowRetryOrExposeBodies()=runTest {
        for(status in listOf(301,302,307,308,401,403,404,429,500)) {
            var calls=0
            OapenSource(MockEngine {calls++; respond("private token path",HttpStatusCode.fromValue(status),headersOf(HttpHeaders.Location,"http://127.0.0.1/secret"))}).use { source ->
                assertEquals(OAPEN_METADATA_ERROR,assertFailsWith<SearchException> {source.getPublication(id)}.message)
                assertEquals(1,calls)
            }
        }
    }
    @Test fun invalidLengthTypeCompressionAndBodyLimitsFailClosed()=runTest {
        for ((body, headers) in listOf(
            record() to headersOf(HttpHeaders.ContentType,"text/html"),
            record() to headersOf(HttpHeaders.ContentType to listOf("text/xml"),HttpHeaders.ContentEncoding to listOf("gzip")),
            record() to headersOf(HttpHeaders.ContentType to listOf("text/xml"),HttpHeaders.ContentLength to listOf("1")),
            record() to headersOf(HttpHeaders.ContentType to listOf("text/xml"),HttpHeaders.ContentLength to listOf("99999999")),
            ByteArray(OAPEN_METADATA_LIMIT+1) to headersOf(HttpHeaders.ContentType,"text/xml"))) {
            OapenSource(MockEngine {respond(body,headers=headers)}).use { source ->
                assertEquals(OAPEN_METADATA_ERROR,assertFailsWith<SearchException> {source.getPublication(id)}.message)
            }
        }
    }
    @Test fun timeoutAndNetworkFailureAreSanitizedAndNotRetried()=runTest {
        for(timeout in listOf(false,true)) {
            var calls=0
            OapenSource(MockEngine {request ->calls++;if(timeout) throw HttpRequestTimeoutException(request) else throw java.io.IOException("secret")}).use { source ->
                assertEquals(OAPEN_METADATA_ERROR,assertFailsWith<SearchException> {source.getPublication(id)}.message)
                assertEquals(1,calls)
            }
        }
    }
    @Test fun cancellationAndLeavingScreenReleaseRequestAndAllowExplicitRetry()=runTest {
        val entered=CompletableDeferred<Unit>(); var calls=0
        OapenSource(MockEngine {
            if(++calls==1) {entered.complete(Unit);awaitCancellation()}
            respond(record(),headers=headersOf(HttpHeaders.ContentType,"text/xml"))
        }).use { source ->
            val pending=async {source.getPublication(id)}
            entered.await();pending.cancelAndJoin();assertFailsWith<CancellationException> {pending.await()}
            assertNotNull(source.getPublication(id));assertEquals(2,calls)
        }
    }
    @Test fun duplicateIdentitiesCoalesceThroughExistingDiscoveryCache()=runTest {
        var calls=0
        val controlled=kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        // Both network and parser use the controlled scheduler: a real IO dispatcher
        // must not race runTest advancing the catalog's virtual timeout.
        val engine=MockEngine(MockEngineConfig().apply {
            dispatcher=controlled
            addHandler {calls++;respond(record(),headers=headersOf(HttpHeaders.ContentType,"text/xml"))}
        })
        OapenSource(engine,controlled).use { source ->
            val catalog=DiscoveryCatalog(listOf(source),backgroundScope){testScheduler.currentTime}
            try {
                val a=async {catalog.load(id.sourceId,DiscoveryRequest(id.localId))}
                val b=async {catalog.load(id.sourceId,DiscoveryRequest(id.localId))}
                assertEquals(a.await(),b.await()); assertEquals(1,calls)
                assertEquals(1,catalog.load(id.sourceId,DiscoveryRequest(id.localId)).entries.size); assertEquals(1,calls)
            } finally {catalog.close()}
        }
    }
    @Test fun closeIsIdempotentAndPreventsFurtherRequests()=runTest {
        val source=OapenSource(xml());source.close();source.close()
        assertFailsWith<SearchException> {source.getPublication(id)}
    }
}
