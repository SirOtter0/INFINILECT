// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.archive.InternetArchiveSource
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.cache.desktopCacheDirectory
import org.infinilect.app.collections.*
import org.infinilect.app.progress.*
import org.infinilect.app.reader.*
import org.infinilect.app.search.SearchState
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

/** Real Desktop files/JDBC and application ownership, with offline source transport.
 * This deliberately does not claim a window, graphical scroll or live-source test.
 */
class DesktopReadingLifecycleTest {
    private val id = PublicationId(SourceId("internet-archive"), "gmb-2015-93040")
    private val key = ReadingProgressId(id, "gmb-2015-93040_djvu.txt", PublicationFormat.TEXT)
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/archive/$name"))
        .use { it.readBytes() }
    private fun <T> success(result: LocalStoreResult<T>) = assertIs<LocalStoreResult.Success<T>>(result).value

    private class Owner(val sources: ApplicationSources, val source: InternetArchiveSource,
                        val metadata: AtomicInteger, val downloads: AtomicInteger, val releases: AtomicInteger)

    private fun owner(progressPath: Path, cachePath: Path, textPath: Path, database: Path): Owner {
        val metadata = AtomicInteger(); val downloads = AtomicInteger(); val releases = AtomicInteger()
        val source = InternetArchiveSource(MockEngine { request ->
            when {
                request.url.encodedPath == "/advancedsearch.php" ->
                    respond(fixture("search.json"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath == "/metadata/${id.localId}" -> {
                    metadata.incrementAndGet()
                    respond(fixture("item.json"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.url.host == "archive.org" && request.url.encodedPath.startsWith("/download/") ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location,
                        "https://dn760105.eu.archive.org/0/items/${id.localId}/${key.resourceKey}"))
                request.url.host == "dn760105.eu.archive.org" -> {
                    downloads.incrementAndGet()
                    respond(ByteArray(2566) { if (it % 80 == 79) 10 else 65 }, headers = headersOf(
                        HttpHeaders.ContentType to listOf("text/plain; charset=utf-8"),
                        HttpHeaders.ContentLength to listOf("2566")))
                }
                else -> error("Unexpected offline request")
            }
        })
        val cache = DiskResourceCache(cachePath)
        val store = SqlCollectionsStore({ desktopCollectionsDriver(database) })
        val collections = ApplicationCollections(store.library, store.history, release = {
            store.close(); releases.incrementAndGet()
        })
        val sources = ApplicationSources(listOf(SourceOption("Internet Archive", source, true)),
            createLoader = { cache.loader(it.id, DirectResourceLoader(it)) },
            progress = ProgressPersistence(FileReadingProgressStore(progressPath), clock = ::progressTime),
            collections = collections, textPreparer = FileTextPreparer(textPath)) {
            try { cache.close() } finally { source.close() }
        }
        return Owner(sources, source, metadata, downloads, releases)
    }

    @Test fun desktopOwnerCloseRestartAndCacheDeletionPreserveIndependentUserStores() = runBlocking {
        val root = Files.createTempDirectory("infinilect-desktop-lifecycle")
        val environment = mapOf("XDG_DATA_HOME" to root.resolve("data").toString(),
            "XDG_CACHE_HOME" to root.resolve("cache").toString())
        val progressPath = assertNotNull(desktopProgressDirectory("Linux", root.toString(), environment))
        val cachePath = assertNotNull(desktopCacheDirectory("Linux", root.toString(), environment))
        val textPath = assertNotNull(desktopTextDirectory("Linux", root.toString(), environment))
        val database = assertNotNull(desktopCollectionsFile(progressPath))
        var first: Owner? = null; var second: Owner? = null
        try {
            val a = owner(progressPath, cachePath, textPath, database).also { first = it }
            val session = ApplicationSession(a.sources, this)
            a.sources.attach(session)
            val search = session.searchSession.value
            search.editQuery("identifier:${id.localId}"); search.submitSearch()
            val results = assertIs<SearchState.Results>(search.search.state.first {
                it is SearchState.Results || it is SearchState.Error || it is SearchState.Empty
            })
            session.collections.membership.first { it.known && !it.loading }
            assertNotNull(session.collections.toggleCatalogLibrary(results.result.page.publications.single())).join()
            assertTrue(id in session.collections.membership.value.saved)
            assertTrue(success(a.sources.collections!!.history.listRecent(10)).isEmpty())

            session.openSearch(results.result.page.publications.single())
            val ready = assertIs<OpenPublicationState.Ready>(session.opening.first {
                it is OpenPublicationState.Ready || it is OpenPublicationState.Error
            })
            assertEquals(2566, ready.document.codePoints)
            assertTrue(ready.document.window(0).text.isNotEmpty())
            assertNotNull(ready.reading).report(1500)
            // Close directly while Ready: final pending progress/history must drain,
            // independent of the UI scope, before recreating every application owner.
            a.sources.close(); a.sources.close()
            withTimeout(3_000) { a.sources.awaitProgressClosed() }
            assertIs<OpenPublicationState.Idle>(search.opening.state.value)
            assertEquals(1, a.releases.get())
            assertEquals(2, a.metadata.get()); assertEquals(1, a.downloads.get())
            assertFalse(a.sources.progress!!.saveFailed.value)
            assertFalse(a.sources.collections.historyFailed.value)
            assertFails { ready.document.window(0) }
            assertFailsWith<SearchException> { a.source.getPublication(id) }
            assertTrue(Files.isRegularFile(database))
            Files.list(progressPath).use { assertEquals(1L, it.filter { p -> p.toString().endsWith(".progress") }.count()) }
            Files.walk(textPath).use { assertEquals(0L, it.filter { p -> p.toString().endsWith(".utf8") }.count()) }
            assertFalse(Files.exists(cachePath)) // revision=null has never populated reusable bytes.

            // Delete only this test's private cache base; durable data is a sibling.
            assertTrue(root.resolve("cache").toFile().deleteRecursively())
            val b = owner(progressPath, cachePath, textPath, database).also { second = it }
            val collections = assertNotNull(b.sources.collections)
            val saved = assertNotNull(success(collections.library.get(id)))
            assertEquals(id, success(collections.history.listRecent(10)).single().publication.id)
            assertEquals(1500L, assertIs<ReadingLocator.Text>(assertNotNull(b.sources.progress!!.get(key)).locator).codePointOffset)
            val restarted = ApplicationSession(b.sources, this)
            b.sources.attach(restarted); restarted.navigate(Destination.LIBRARY)
            restarted.openSaved(saved.publication)
            val restored = assertIs<OpenPublicationState.Ready>(restarted.opening.first {
                it is OpenPublicationState.Ready || it is OpenPublicationState.Error
            })
            assertEquals(1500, assertNotNull(restored.reading).codePointOffset.value)
            assertEquals(2, b.metadata.get()); assertEquals(1, b.downloads.get())
            assertNull(assertNotNull(restored.publication).resources.single { it.format == PublicationFormat.TEXT }.revision)
            restarted.back()
            assertEquals(Destination.LIBRARY, restarted.destination.value)
            b.sources.close(); withTimeout(3_000) { b.sources.awaitProgressClosed() }
            assertEquals(1, b.releases.get())
            assertFalse(Files.exists(cachePath))
        } finally {
            listOfNotNull(first, second).forEach { it.sources.close(); it.sources.awaitProgressClosed() }
            root.toFile().deleteRecursively()
        }
    }
}
