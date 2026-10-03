// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.nio.file.Files
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.reader.loadTextDocument
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArchiveCacheBoundaryTest {
    private val id = PublicationId(ARCHIVE_ID, "gmb-2015-93040")
    private val resource = PublicationResource(id, "gmb-2015-93040_djvu.txt", PublicationFormat.TEXT, "text/plain")

    @Test fun textReadingThroughCacheStillRefreshesAndAcquiresEveryNullRevisionOpen() = runTest {
        val directory = Files.createTempDirectory("infinilect-archive-cache")
        var metadata = 0; var downloads = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) {
                metadata++; respond(itemFixture(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                downloads++; respond(ByteArray(2566) { 65 }, headers = headersOf(HttpHeaders.ContentType, "text/plain"))
            }
        }
        try {
            // Match existing source tests: its transport-lifetime timeout uses real
            // I/O time, not a virtual clock that can expire while Ktor dispatches.
            InternetArchiveSource(engine).use { source ->
                DiskResourceCache(directory.resolve("resource-cache-v1"), ioDispatcher = StandardTestDispatcher(testScheduler)).use { cache ->
                    val loader = cache.loader(source.id, DirectResourceLoader(source))
                    repeat(2) {
                        val publication = assertNotNull(source.getPublication(id))
                        assertTrue(publication.resources.all { it.revision == null })
                        val document = loadTextDocument(publication, resource, loader, StandardTestDispatcher(testScheduler))
                        assertEquals(2566, document.codePoints)
                        document.close()
                    }
                    assertEquals(4, metadata); assertEquals(2, downloads)
                    assertFalse(Files.exists(directory.resolve("resource-cache-v1")))
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun previouslyReadNullRevisionCannotBypassFreshRestrictedMetadata() = runTest {
        val directory = Files.createTempDirectory("infinilect-archive-cache")
        var restricted = false; var metadata = 0; var downloads = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.startsWith("/metadata/")) {
                metadata++
                respond(itemFixture { if (restricted) it.replace("\"mediatype\":", "\"access-restricted-item\":\"true\",\"mediatype\":") else it },
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else { downloads++; respond(ByteArray(2566) { 65 }, headers = headersOf(HttpHeaders.ContentType, "text/plain")) }
        }
        try {
            InternetArchiveSource(engine).use { source ->
                DiskResourceCache(directory.resolve("resource-cache-v1"), ioDispatcher = StandardTestDispatcher(testScheduler)).use { cache ->
                    val loader = cache.loader(source.id, DirectResourceLoader(source))
                    loader.load(resource).readBytes(3000); restricted = true
                    assertFailsWith<SearchException> { loader.load(resource) }
                    assertEquals(2, metadata); assertEquals(1, downloads)
                    assertFalse(Files.exists(directory.resolve("resource-cache-v1")))
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
