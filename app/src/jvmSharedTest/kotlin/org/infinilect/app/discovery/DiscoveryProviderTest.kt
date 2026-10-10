// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.archive.*
import org.infinilect.app.gutenberg.*
import org.infinilect.app.ui.*
import org.infinilect.core.*
import java.nio.file.Files
import kotlin.test.*

class DiscoveryProviderTest {
    @Test fun emptyIndexedFirstPageDoesNotHideLaterPages() {
        val query="subject:\"Adventure\""
        val first=ArchiveMetadata.search("""{"response":{"numFound":21,"docs":[]}}""".toByteArray(),query,1)
        assertTrue(first.publications.isEmpty())
        assertEquals(2,ArchiveUrls.page(assertNotNull(first.nextPageToken),query))
        val last=ArchiveMetadata.search("""{"response":{"numFound":21,"docs":[]}}""".toByteArray(),query,3)
        assertNull(last.nextPageToken)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun slowThumbnailDoesNotHoldTheCatalogMetadataLock()=runTest {
        val coverStarted=CompletableDeferred<Unit>()
        val queryStarted=CompletableDeferred<Unit>()
        val source=GutenbergSource(MockEngine(MockEngineConfig().apply {
            dispatcher=StandardTestDispatcher(testScheduler)
            addHandler { request ->
                if(request.url.host=="www.gutenberg.org") { coverStarted.complete(Unit); awaitCancellation() }
                else {
                    val query=request.url.parameters["query"]
                    if(query=="Cervantes") queryStarted.complete(Unit)
                    respond(if(query!=null) previewPage(query,publications=withImage("https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg")) else previewRoot,
                        headers=headersOf(HttpHeaders.ContentType,"application/json"))
                }
            }
        }))
        try {
            source.discover(DiscoveryRequest("books"))
            val cover=backgroundScope.launch { source.coverThumbnail(PublicationId(source.id,"84")) }
            runCurrent(); coverStarted.await()
            val query=backgroundScope.async { source.discover(DiscoveryRequest("Cervantes")) }
            runCurrent()
            assertTrue(queryStarted.isCompleted,"A pending image must not block catalog metadata")
            cover.cancel();runCurrent()
            assertEquals(1,query.await().entries.size)
        } finally {source.close()}
    }

    @Test fun supportedGenreAliasesRetainRightsSubsetAndPageTokenBinding()=runTest {
        val queries=mutableListOf<String>()
        val source=InternetArchiveSource(MockEngine {request ->
            queries+=request.url.parameters["q"]!!
            respond("""{"response":{"numFound":11,"docs":[{"identifier":"original-book","title":"Original","subject":["Adventure"]}]}}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            for(genre in listOf(Genre.ADVENTURE,Genre.PHILOSOPHY,Genre.ROMANCE,Genre.HISTORY)) {
                val page=source.discover(DiscoveryRequest("",genre))
                source.discover(DiscoveryRequest("",genre),assertNotNull(page.nextToken))
                assertEquals(queries[queries.lastIndex-1],queries.last())
                assertTrue(queries.last().contains("licenseurl:")); assertTrue(queries.last().contains("NOT access-restricted-item:true"))
            }
            assertTrue(queries.first().contains("subject:\"Adventure stories\" OR subject:\"Adventure\""))
            assertTrue(queries[4].contains("subject:\"Love stories\" OR subject:\"Romance\""))
        } finally {source.close()}
    }
    @Test fun archiveMapsOnlySuppliedMetadataAndLeavesResourcesUnauthorized() {
        val entries=mutableListOf<DiscoveryEntry>()
        val json="""{"response":{"numFound":1,"docs":[{"identifier":"original-book","title":"A title","creator":["Author"],"language":["en"],"subject":["Science fiction -- History and criticism"],"description":"<p>Original &amp; inert.</p>","rights":"A supplied rights statement"}]}}"""
        val page=ArchiveMetadata.search(json.toByteArray(),"book",1,entries::add)
        assertEquals(listOf("Author"),page.publications.single().authors)
        assertEquals(listOf("en"),page.publications.single().languages)
        assertEquals(setOf(Genre.SCIENCE_FICTION),entries.single().genres)
        assertEquals("Original & inert.",entries.single().description)
        assertTrue(page.publications.single().resources.isEmpty())
        assertEquals("A supplied rights statement",page.publications.single().rights)
    }
    @Test fun gutenbergStructuredSubjectsAndDescriptionRemainCatalogOnly() {
        val entries=mutableListOf<DiscoveryEntry>()
        val book=previewPublication(extra=",\"subject\":[{\"name\":\"Philosophy\"}],\"description\":\"<p>Original synopsis.</p>\"")
        val result=GutenbergOpds2Parser.search(previewBytes(previewPage(publications=book)),"books",1,entries::add)
        assertEquals(setOf(Genre.PHILOSOPHY),entries.single().genres);assertEquals("Original synopsis.",entries.single().description)
        assertTrue(result.publications.single().resources.isEmpty());assertNull(result.publications.single().rights)
    }
    @Test fun genreBrowsingEncodesSubjectAndRetainsCc0RestrictedSubset()=runTest {
        var requested: Url?=null
        val engine=MockEngine { request ->requested=request.url;respond("""{"response":{"numFound":0,"docs":[]}}""",headers=headersOf(HttpHeaders.ContentType,"application/json")) }
        val source=InternetArchiveSource(engine=engine)
        try {
            source.discover(DiscoveryRequest("",Genre.MYSTERY))
            val query=assertNotNull(requested).parameters["q"]!!
            assertTrue(query.contains("subject:\"Detective and mystery stories\""));assertTrue(query.contains("licenseurl:"));assertTrue(query.contains("NOT access-restricted-item:true"))
            assertEquals("archive.org",requested!!.host);assertEquals("/advancedsearch.php",requested!!.encodedPath)
            assertTrue("subject" in requested!!.parameters.getAll("fl[]").orEmpty())
        }finally{source.close()}
    }
    @Test fun sourceReceivesOnlyQueryOrSubjectNeverProfileOrLocalCollections()=runTest {
        val urls=mutableListOf<String>()
        val source=GutenbergSource(MockEngine { request ->
            urls+=request.url.toString()
            respond(if(request.url.encodedPath.endsWith("/search")) previewPage("books") else previewRoot,
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            source.discover(DiscoveryRequest("books",language="es"))
            assertEquals(2,urls.size);assertTrue(urls.all{it.startsWith(GUTENBERG_ROOT)})
            assertTrue(urls.none {it.contains("country") || it.contains("profile") || it.contains("history") || it.contains("language=")})
            val resource=PublicationResource(PublicationId(source.id,"84"),"forged",PublicationFormat.EPUB,"application/epub+zip")
            assertFails { source.loadResource(resource) };assertEquals(2,urls.size)
        }finally{source.close()}
    }
    @Test fun metadataStillRejectsInvalidJsonDeepTreesWrongIdentityAndUnsafeTokensBeforeIo()=runTest {
        assertFailsWith<InvalidOpdsException>{GutenbergOpds2Parser.search(ByteArray(MAX_FEED_BYTES+1),"books",1)}
        assertFailsWith<InvalidOpdsException>{GutenbergOpds2Parser.search(previewBytes("[".repeat(33)+"]".repeat(33)),"books",1)}
        assertFailsWith<InvalidArchiveData>{ArchiveMetadata.search("""{"response":{"numFound":1,"docs":[{"identifier":"../outside"}]}}""".toByteArray(),"book",1)}
        var calls=0;val source=InternetArchiveSource(MockEngine { calls++;error("No request permitted") })
        try { assertFails { source.discover(DiscoveryRequest("",Genre.HISTORY),"https://outside.invalid/path") };assertEquals(0,calls) }finally{source.close()}
    }
    @Test fun legacyAppearanceAndProfileUpgradeRetainsLibraryAndProgressFiles()=runTest {
        val root=Files.createTempDirectory("discovery-preference-fixture")
        try {
            val store=FileApplicationAppearanceStore(root.resolve("settings"))
            val untouched=root.resolve("library-history-progress.fixture");val before=byteArrayOf(4,8,12);Files.write(untouched,before)
            val legacy=ApplicationPreferences(ApplicationThemeMode.DARK,LocalProfile("María","ES",null,true))
            assertTrue(store.savePreferences(legacy));assertEquals(DiscoveryPreferences(),store.loadPreferences().discovery)
            val changed=legacy.copy(discovery=DiscoveryPreferences(true,false,Genre.entries.toSet(),12345))
            assertTrue(store.savePreferences(changed));assertEquals(changed,FileApplicationAppearanceStore(root.resolve("settings")).loadPreferences())
            assertTrue(Files.size(root.resolve("settings/appearance.preferences"))<=512)
            assertTrue(store.save(ApplicationThemeMode.LIGHT));assertEquals(changed.discovery,store.loadPreferences().discovery)
            assertEquals(legacy.profile,store.loadPreferences().profile);assertContentEquals(before,Files.readAllBytes(untouched))
        }finally{root.toFile().deleteRecursively()}
    }
    @Test fun maximumUnicodeProfilePlusAllGenresStaysInsideExistingPrivateRecordBudget()=runTest {
        val root=Files.createTempDirectory("discovery-preference-bounds")
        try {
            val store=FileApplicationAppearanceStore(root)
            val value=ApplicationPreferences(profile=LocalProfile("漢".repeat(80),"JP","en",true),discovery=DiscoveryPreferences(true,true,Genre.entries.toSet(),Long.MAX_VALUE))
            assertTrue(store.savePreferences(value));assertEquals(value,store.loadPreferences());assertTrue(Files.size(root.resolve("appearance.preferences"))<=512)
        }finally{root.toFile().deleteRecursively()}
    }
    @Test fun malformedDiscoveryPreferencesFailSafelyAndDoNotOverwriteOtherFiles()=runTest {
        val root=Files.createTempDirectory("discovery-preference-malformed")
        try {
            val store=FileApplicationAppearanceStore(root);assertTrue(store.savePreferences(ApplicationPreferences(discovery=DiscoveryPreferences(homeEnabled=true))))
            val path=root.resolve("appearance.preferences");val bytes=Files.readAllBytes(path);bytes[bytes.lastIndex]=0;Files.write(path,bytes)
            assertEquals(ApplicationPreferences(),store.loadPreferences())
        }finally{root.toFile().deleteRecursively()}
    }

    private fun withImage(url: String, id: String = "84") = previewPublication(id).dropLast(1) +
        ",\"images\":[{\"href\":\"$url\",\"type\":\"image/jpeg\"}]}"
    @Test fun catalogThumbnailOnlyAcceptsAdvertisedExactOwnedHttpsJpegRoutes() {
        val accepted = "https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg"
        for (url in listOf(accepted, accepted + "?secret=1", accepted + "#fragment", accepted.replace("https:","http:"),
            accepted.replace("www.gutenberg.org","outside.invalid"), accepted.replace("/84/","/85/"), accepted.replace("/84/","/%38%34/"))) {
            val entries = mutableListOf<DiscoveryEntry>()
            GutenbergOpds2Parser.search(previewBytes(previewPage(publications=withImage(url))),"books",1,entries::add)
            assertEquals(if(url==accepted) accepted else null,entries.single().thumbnail)
            assertTrue(entries.single().publication.resources.isEmpty())
        }
    }
    @Test fun unadvertisedCoverNeverGuessesUrlOrFetchesDetails()=runTest {
        var calls=0;val source=GutenbergSource(MockEngine {calls++;error("No I/O")})
        try {assertNull(source.coverThumbnail(PublicationId(source.id,"84")));assertEquals(0,calls)}finally{source.close()}
    }
    @Test fun thumbnailRedirectAndOversizedResponsesFailWithoutFollowingOrAcquiringBooks()=runTest {
        for (oversized in listOf(false,true)) {
            val urls=mutableListOf<String>();val url="https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg"
            val source=GutenbergSource(MockEngine {request ->
                urls+=request.url.toString()
                if(request.url.host=="www.gutenberg.org") {
                    if(oversized) respond(ByteArray(524289),headers=headersOf(HttpHeaders.ContentType,"image/jpeg"))
                    else respond("redirect",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"https://outside.invalid/private"))
                } else respond(if(request.url.encodedPath.endsWith("/search")) previewPage("books",publications=withImage(url)) else previewRoot,
                    headers=headersOf(HttpHeaders.ContentType,"application/json"))
            })
            try {source.discover(DiscoveryRequest("books"));assertFails {source.coverThumbnail(PublicationId(source.id,"84"))};assertEquals(3,urls.size);assertTrue(urls.none{it.contains("outside.invalid")||it.endsWith(".epub")})}
            finally{source.close()}
        }
    }

    @Test fun hugeOptionalSubjectListsRemainBoundedWithoutDiscardingOtherCatalogResults() {
        val subjects = List(1000) { "\"A subject $it\"" }.joinToString(",")
        val entries=mutableListOf<DiscoveryEntry>()
        ArchiveMetadata.search("{\"response\":{\"numFound\":1,\"docs\":[{\"identifier\":\"original\",\"subject\":[$subjects]}]}}".toByteArray(),"q",1,entries::add)
        assertEquals(1,entries.size);assertEquals(16,entries.single().subjects.size);assertTrue(entries.single().publication.resources.isEmpty())
    }
    @Test fun thumbnailDeclarationLruEvictsOlderIdsAndClearsOnSourceClose()=runTest {
        var calls=0
        val source=GutenbergSource(MockEngine { request ->
            calls++
            val query=request.url.parameters["query"]
            val id=query?.removePrefix("book-") ?: "1"
            respond(if(query==null) previewRoot else previewPage(query,publications=withImage("https://www.gutenberg.org/cache/epub/$id/pg$id.cover.medium.jpg",id)),
                headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {repeat(101){source.discover(DiscoveryRequest("book-${it+1}"))};val before=calls
            assertNull(source.coverThumbnail(PublicationId(source.id,"1")));assertEquals(before,calls)
        }finally{source.close()}
        assertNull(source.coverThumbnail(PublicationId(source.id,"101")))
    }

    @Test fun unifiedQueryCannotEscapeTheSubjectOrRightsSubsetWithSearchOperators()=runTest {
        var query:String?=null
        val source=InternetArchiveSource(MockEngine {request -> query=request.url.parameters["q"];respond("{\"response\":{\"numFound\":0,\"docs\":[]}}",headers=headersOf(HttpHeaders.ContentType,"application/json"))})
        try {
            source.discover(DiscoveryRequest("\") OR mediatype:movies OR (\"",Genre.PHILOSOPHY))
            val q=assertNotNull(query)
            assertTrue(q.startsWith("(\"\\\") OR mediatype:movies OR (\\\"\" AND subject:"))
            assertTrue(q.contains("licenseurl:"));assertTrue(q.endsWith("NOT access-restricted-item:true"))
        }finally{source.close()}
    }
}
