// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.infinilect.core.*
import org.infinilect.app.downloads.*
import kotlin.test.*

/** Production admission and URL/redirect policy, synthetic HTTP bytes; no live server. */
class ArchiveOfflineAuthorizationTest {
    private val id=PublicationId(ARCHIVE_ID,"gmb-2015-93040")
    @Test fun offersRequireExplicitCc0AndRetainOnlyExistingTextAndPdfContracts()=runTest {
        var assets=0
        InternetArchiveSource(MockEngine {request ->
            if(!request.url.encodedPath.startsWith("/metadata/")) assets++
            respond(itemFixture(),headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }).use {source ->
            val offers=source.downloadOffers(id)
            assertEquals(setOf(PublicationFormat.TEXT,PublicationFormat.PDF),offers.map {it.resource.format}.toSet())
            assertEquals(listOf(2566L,443517L),offers.map {it.size});assertTrue(offers.all {it.publication.id==id && it.resource.publicationId==id})
            assertEquals(0,assets,"Looking at download choices must not fetch a publication")
        }
    }
    @Test fun restrictionsAreRecheckedInBothOfferAndPayloadPaths()=runTest {
        val mutations=listOf<(String)->String>(
            {it.replace("\"mediatype\":","\"access-restricted-item\":\"true\",\"mediatype\":")},
            {it.replace("\"server\":","\"is_dark\":true,\"server\":")},
            {it.replace("\"server\":","\"nodownload\":true,\"server\":")},
            {it.replace("dutchgovernmentdocuments","inlibrary")},
            {it.replace("http://creativecommons.org/publicdomain/zero/1.0/","https://example.invalid/unknown-rights")}
        )
        for((index,change) in mutations.withIndex()) {
            var restricted=false;var assets=0
            InternetArchiveSource(MockEngine {request ->
                if(!request.url.encodedPath.startsWith("/metadata/")) {assets++;error("Denied item must never reach payload")}
                respond(itemFixture {if(restricted) change(it) else it},headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }).use {source ->
                val offer=source.downloadOffers(id).first();restricted=true
                assertEquals(if(index==4) DownloadFailure.RIGHTS_UNVERIFIED else DownloadFailure.NOT_ALLOWED,
                    assertFailsWith<DownloadException> {source.downloadOffers(id)}.failure)
                assertFails {source.openDownload(offer)};assertEquals(0,assets)
            }
        }
    }
    @Test fun payloadCannotRedirectToAnUnrelatedHostOrInsecureTransport()=runTest {
        for(url in listOf("https://evil.example/book.txt","http://archive.org/download/a.txt","https://127.0.0.1/internal","file:///private/data")) {
            var assets=0
            InternetArchiveSource(MockEngine {request ->
                if(request.url.encodedPath.startsWith("/metadata/")) respond(itemFixture(),headers=headersOf(HttpHeaders.ContentType,"application/json"))
                else {assets++;assertEquals("archive.org",request.url.host);respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,url))}
            }).use {source ->
                assertFails {source.openDownload(source.downloadOffers(id).first())};assertEquals(1,assets)
            }
        }
    }
}
