// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.infinilect.app.diagnostics.*
import org.infinilect.app.downloads.*
import org.infinilect.core.*
import kotlin.test.*

/** Real adapter/store/validators, original synthetic bytes; never downloaded book fixtures. */
class ArchiveOfflineFailureTest {
    private val id=PublicationId(ARCHIVE_ID,"RepublicanismMarxism")
    private val text="Original synthetic text, Unicode català español 🦦.".toByteArray()
    private fun metadata(license: String="http://creativecommons.org/publicdomain/zero/1.0/",format: String="DjVuTXT",name: String="original_djvu.txt") =
        """{"metadata":{"identifier":"RepublicanismMarxism","title":"Original synthetic fixture","mediatype":"texts","licenseurl":"$license"},"files":[{"name":"$name","format":"$format","size":"${text.size}"}]}"""
    private class Events: TechnicalDiagnostics {
        val values=java.util.Collections.synchronizedList(mutableListOf<Triple<TechnicalEvent,Long,Int>>())
        override fun record(event: TechnicalEvent,generation: Long,count: Int) {values+=Triple(event,generation,count)}
    }
    @Test fun metadataHttpFailurePreservesStatusThroughBackendInsteadOfBecomingConnection()=runBlocking<Unit> {
        for(status in listOf(403,429,503)) {
            val root=Files.createTempDirectory("offline-http");val fixture=OfflineFixtureOwner(root,OfflineFixtureSource())
            InternetArchiveSource(MockEngine {respond("untrusted private server message",HttpStatusCode.fromValue(status))}).use {source ->
                val store=FileOfflineDownloads(root.resolve("retained"),listOf(source),fixture.validator);val events=Events();store.setDiagnostics(events)
                try {
                    store.load()
                    val error=assertFailsWith<DownloadException> {store.offers(id)}
                    assertEquals(DownloadFailure.HTTP,error.failure);assertEquals(status,error.httpStatus)
                    assertTrue(Triple(TechnicalEvent.DOWNLOAD_HTTP_ERROR,DownloadStage.RESOLVE.code,status) in events.values)
                    assertTrue(store.snapshot().entries.isEmpty())
                } finally {store.close();fixture.close();root.toFile().deleteRecursively()}
            }
        }
    }
    @Test fun payloadHttpFailureKeepsItsCauseAndCannotPublishAnIncompleteCopy()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-payload-error");val fixture=OfflineFixtureOwner(root,OfflineFixtureSource())
        InternetArchiveSource(MockEngine {request ->
            if(request.url.encodedPath.startsWith("/metadata/")) respond(metadata(),headers=headersOf(HttpHeaders.ContentType,"application/json"))
            else respond("denied",HttpStatusCode.Forbidden)
        }).use {source ->
            val store=FileOfflineDownloads(root.resolve("retained"),listOf(source),fixture.validator);val events=Events();store.setDiagnostics(events)
            try {
                store.load();val row=store.pending(store.offers(id).single())
                val error=assertFailsWith<DownloadException> {store.download(row,{},{})}
                assertEquals(DownloadFailure.HTTP,error.failure);assertEquals(403,error.httpStatus)
                store.settle(id,DownloadStatus.ERROR,error.failure)
                assertNull(store.acquire(id));assertEquals(0L,store.snapshot().usedBytes)
                assertFalse(Files.exists(root.resolve("retained/${offlineKey(id)}.part")))
                assertTrue(Triple(TechnicalEvent.DOWNLOAD_HTTP_ERROR,DownloadStage.OPEN.code,403) in events.values)
            } finally {store.close();fixture.close();root.toFile().deleteRecursively()}
        }
    }
    @Test fun rightsAndUnsupportedFormatsAreDistinctAndNeverOpenPayloads()=runBlocking<Unit> {
        val cases=listOf(
            metadata(license="https://example.invalid/unknown-license") to DownloadFailure.RIGHTS_UNVERIFIED,
            metadata(format="EPUB",name="original.epub") to DownloadFailure.UNSUPPORTED_FORMAT,
            metadata().replace("\"mediatype\":","\"access-restricted-item\":true,\"mediatype\":") to DownloadFailure.NOT_ALLOWED)
        for((json,expected) in cases) {
            var payloads=0
            InternetArchiveSource(MockEngine {request ->
                if(!request.url.encodedPath.startsWith("/metadata/")) payloads++
                respond(json,headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }).use {source ->
                assertEquals(expected,assertFailsWith<DownloadException> {source.downloadOffers(id)}.failure)
                assertEquals(0,payloads)
            }
        }
    }
    @Test fun malformedMetadataAndWrongMimeAreValidationErrorsNotConnectionFailures()=runBlocking<Unit> {
        InternetArchiveSource(MockEngine {respond("{",headers=headersOf(HttpHeaders.ContentType,"application/json"))}).use {source ->
            assertEquals(DownloadFailure.INVALID,assertFailsWith<DownloadException> {source.downloadOffers(id)}.failure)
        }
        InternetArchiveSource(MockEngine {request ->
            if(request.url.encodedPath.startsWith("/metadata/")) respond(metadata(),headers=headersOf(HttpHeaders.ContentType,"application/json"))
            else respond("<html>not text</html>",headers=headersOf(HttpHeaders.ContentType,"text/html"))
        }).use {source ->
            assertEquals(DownloadFailure.INVALID,assertFailsWith<DownloadException> {source.openDownload(source.downloadOffers(id).single())}.failure)
        }
    }
    @Test fun truncatedArchiveBodyIsIncompleteAndNeverRetained()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-archive-truncated");val fixture=OfflineFixtureOwner(root,OfflineFixtureSource())
        InternetArchiveSource(MockEngine {request ->
            if(request.url.encodedPath.startsWith("/metadata/")) respond(metadata(),headers=headersOf(HttpHeaders.ContentType,"application/json"))
            else respond(text.copyOf(7),headers=headersOf(HttpHeaders.ContentType,"text/plain"))
        }).use {source ->
            val store=FileOfflineDownloads(root.resolve("retained"),listOf(source),fixture.validator)
            try {
                store.load();val row=store.pending(store.offers(id).single())
                assertEquals(DownloadFailure.INCOMPLETE,assertFailsWith<DownloadException> {store.download(row,{},{})}.failure)
                assertNull(store.acquire(id));assertEquals(0L,store.snapshot().usedBytes)
                assertFalse(Files.exists(root.resolve("retained/${offlineKey(id)}.part")))
            } finally {store.close();fixture.close();root.toFile().deleteRecursively()}
        }
    }
    @Test fun archiveTextDownloadSurvivesRestartAndReadsWithoutAnySourceRequest()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-archive-restart");val fixture=OfflineFixtureOwner(root,OfflineFixtureSource());var online=true;var requests=0
        InternetArchiveSource(MockEngine {request ->
            check(online) {"Network forbidden after restart"};requests++
            if(request.url.encodedPath.startsWith("/metadata/")) respond(metadata(),headers=headersOf(HttpHeaders.ContentType,"application/json"))
            else respond(text,headers=headersOf(HttpHeaders.ContentType to listOf("text/plain"),HttpHeaders.ContentLength to listOf(text.size.toString())))
        }).use {source ->
            var store=FileOfflineDownloads(root.resolve("retained"),listOf(source),fixture.validator)
            try {
                store.load();val row=store.pending(store.offers(id).single())
                val done=store.download(row,{},{})
                assertEquals(DownloadStatus.DOWNLOADED,done.status);store.close();online=false;val before=requests
                store=FileOfflineDownloads(root.resolve("retained"),listOf(source),fixture.validator)
                assertEquals(DownloadStatus.DOWNLOADED,store.load().entries.single().status)
                val copy=assertNotNull(store.acquire(id))
                try {assertEquals(id,copy.publication.id);assertContentEquals(text,copy.loader.load(copy.publication.resources.single()).readBytes(1024))} finally {copy.close()}
                assertEquals(before,requests)
            } finally {store.close();fixture.close();root.toFile().deleteRecursively()}
        }
    }
}
