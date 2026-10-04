// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.infinilect.app.*
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.collections.*
import org.infinilect.app.progress.*
import org.infinilect.app.reader.*
import org.infinilect.app.search.SearchState
import org.infinilect.core.*

class GutenbergPreviewIntegrationTest {
    private val id=PublicationId(GUTENBERG_ID,"84")
    private fun source(onRequest: ()->Unit={}): GutenbergSource=GutenbergSource(MockEngine{r->
        onRequest();respond(when{r.url.toString()==GUTENBERG_ROOT->previewRoot;r.url.encodedPath.endsWith("publications")->previewPublication();else->previewPage()},headers=headersOf(HttpHeaders.ContentType,"application/json"))
    })
    private fun <T> value(result: LocalStoreResult<T>)=assertIs<LocalStoreResult.Success<T>>(result).value
    @Test fun metadataLibrarySurvivesRealDatabaseReopenAndUnavailableOpenKeepsUserStores()=runTest {
        withContext(Dispatchers.Default) {
            val root=Files.createTempDirectory("gutenberg-preview-library")
            fun store()=SqlCollectionsStore({JdbcSqliteDriver("jdbc:sqlite:${root.resolve(COLLECTIONS_DATABASE_NAME)}",collectionsJdbcProperties()).also(::initializeCollectionsSchema)})
            val catalog=source();val first=store()
            val snapshot=PublicationSnapshot.from(catalog.search("books").publications.single());assertTrue(snapshot.id==id)
            value(first.library.put(snapshot,1));first.close();catalog.close()
            val second=store();var requests=0;val s=source{requests++}
            val progressId=ReadingProgressId(id,"text-utf8",PublicationFormat.TEXT)
            val progressStore=FileReadingProgressStore(root.resolve("progress"))
            val recorded=ReadingProgress(progressId,ReadingLocator.Text(10,20),0.5,1)
            assertTrue(progressStore.save(recorded))
            val cache=DiskResourceCache(root.resolve("cache"))
            val owner=ApplicationSources(listOf(SourceOption("Gutenberg (experimental)",s,false)),createLoader={cache.loader(it.id,DirectResourceLoader(it))},collections=ApplicationCollections(second.library,second.history),textPreparer=FileTextPreparer(root.resolve("text"))) {cache.close();s.close()}
            val app=ApplicationSession(owner,this);owner.attach(app)
            try {
                app.navigate(Destination.LIBRARY);app.openSaved(value(second.library.get(id))!!.publication)
                assertIs<OpenPublicationState.Error>(app.opening.value);assertEquals(0,requests)
                app.back();assertEquals(Destination.LIBRARY,app.destination.value)
                assertNotNull(value(second.library.get(id)));assertTrue(value(second.history.listRecent()).isEmpty())
                val reopened=FileReadingProgressStore(root.resolve("progress"));assertEquals(recorded,reopened.get(progressId))
                assertFalse(Files.exists(root.resolve("cache")));assertFalse(Files.exists(root.resolve("text")))
            } finally {owner.close();owner.awaitProgressClosed();second.close();root.toFile().deleteRecursively()}
        }
    }
    @Test fun searchOnlyOpenIsIgnoredAndSearchResultsStayIntact()=runTest {
        withContext(Dispatchers.Default) {
            val root=Files.createTempDirectory("preview-session");var n=0;val source=source{n++}
            val owner=ApplicationSources(listOf(SourceOption("Experimental",source,false)),textPreparer=FileTextPreparer(root)) {source.close()}
            val app=ApplicationSession(owner,this);owner.attach(app)
            try {
                val search=app.searchSession.value;search.editQuery("books");search.submitSearch()
                val results=assertIs<SearchState.Results>(search.search.state.first{it is SearchState.Results || it is SearchState.Error})
                app.openSearch(results.result.page.publications.single());app.back()
                assertIs<OpenPublicationState.Idle>(app.opening.value);assertEquals("books",search.query.value);assertSame(results,search.search.state.value);assertEquals(2,n)
            } finally {owner.close();owner.awaitProgressClosed();root.toFile().deleteRecursively()}
        }
    }
    @Test fun evenEnabledForgedTextOpenReResolvesThenFailsWithoutHistoryOrDownload()=runTest {
        withContext(Dispatchers.Default) {
            val root=Files.createTempDirectory("preview-forged");var n=0;val source=source{n++};val fake=FakeCollections()
            val owner=ApplicationSources(listOf(SourceOption("Experimental",source,true)),collections=ApplicationCollections(fake.library,fake.history),textPreparer=FileTextPreparer(root)) {source.close()}
            val app=ApplicationSession(owner,this);owner.attach(app)
            try {
                val snapshot=PublicationSnapshot.from(Publication(id,"Stored metadata",PublicationType.BOOK))
                app.openSaved(snapshot)
                assertIs<OpenPublicationState.Error>(app.opening.first{it is OpenPublicationState.Error || it is OpenPublicationState.Ready})
                assertEquals(1,n);assertTrue(fake.opened.isEmpty());app.back()
            } finally {owner.close();owner.awaitProgressClosed();root.toFile().deleteRecursively()}
        }
    }
}
