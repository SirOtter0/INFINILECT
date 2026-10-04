// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.nio.file.*
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

/** Offline real SQL/file persistence + mock transport. No Android device is implied. */
class GutenbergReadingIntegrationTest {
    private val id=testGutenbergId
    private suspend fun ApplicationSession.terminal()=opening.first { it is OpenPublicationState.Ready || it is OpenPublicationState.Error }
    private fun <T> value(r: LocalStoreResult<T>)=assertIs<LocalStoreResult.Success<T>>(r).value

    @Test fun catalogSaveRestartLibraryOpenLargeTextHistoryAndDurableProgress()=runTest {
        withContext(Dispatchers.Default) {
            val root=Files.createTempDirectory("gutenberg-durable");val bytes=ByteArray(600_000){65};var metadata=0;var downloads=0
            fun store()=SqlCollectionsStore({JdbcSqliteDriver("jdbc:sqlite:${root.resolve(COLLECTIONS_DATABASE_NAME)}",collectionsJdbcProperties()).also(::initializeCollectionsSchema)})
            val first=store()
            val catalog=GutenbergSource(MockEngine{respond(catalogFixture(),headers=headersOf(HttpHeaders.ContentType,"application/atom+xml"))},::testGutenbergXml)
            val snapshot=PublicationSnapshot.from(catalog.search("books").publications.single())
            value(first.library.put(snapshot,1));assertTrue(value(first.history.listRecent()).isEmpty());first.close();catalog.close()
            // New owner/store, no process-local metadata or recent progress map.
            fun owner(): Pair<ApplicationSources,SqlCollectionsStore> {
                val persisted=store();val cache=DiskResourceCache(root.resolve("cache"))
                val source=GutenbergSource(MockEngine{request->when{
                    request.url.encodedPath.endsWith(".rdf")-> {metadata++;respond(rdfFixture(size=bytes.size.toLong()),headers=headersOf(HttpHeaders.ContentType,"application/rdf+xml"))}
                    request.url.encodedPath.endsWith(".txt")-> {downloads++;respond(bytes,headers=headersOf(HttpHeaders.ContentType to listOf(GUTENBERG_TEXT_MIME),HttpHeaders.ContentLength to listOf(bytes.size.toString())))}
                    else->respond(catalogFixture(),headers=headersOf(HttpHeaders.ContentType,"application/atom+xml"))
                }},::testGutenbergXml)
                return ApplicationSources(listOf(SourceOption("Gutenberg",source,true)),createLoader={cache.loader(it.id,DirectResourceLoader(it))},
                    progress=ProgressPersistence(FileReadingProgressStore(root.resolve("progress"))),
                    collections=ApplicationCollections(persisted.library,persisted.history),
                    textPreparer=FileTextPreparer(root.resolve("text"))) {cache.close();source.close()} to persisted
            }
            try {
                val (a,dbA)=owner();val sessionA=ApplicationSession(a,this);a.attach(sessionA)
                val saved=value(dbA.library.get(id))!!.publication;assertEquals("Catalog title",saved.title)
                sessionA.navigate(Destination.LIBRARY);sessionA.openSaved(saved)
                val ready=assertIs<OpenPublicationState.Ready>(sessionA.terminal())
                assertEquals("A Book & 🦦",ready.document.title);assertEquals(600_000,ready.document.codePoints)
                assertEquals(1,downloads);assertEquals(2,metadata);assertFalse(Files.exists(root.resolve("cache")))
                ready.reading!!.report(400_000);sessionA.back();assertEquals(Destination.LIBRARY,sessionA.destination.value)
                a.close();a.awaitProgressClosed();dbA.close()
                assertTrue(Files.list(root.resolve("progress")).use{it.anyMatch{p->p.toString().endsWith(".progress")}})
                val (b,dbB)=owner();val sessionB=ApplicationSession(b,this);b.attach(sessionB)
                try {
                    val entry=value(dbB.library.get(id))!!;sessionB.openSaved(entry.publication)
                    val reopened=assertIs<OpenPublicationState.Ready>(sessionB.terminal())
                    assertEquals(400_000,reopened.reading!!.codePointOffset.value);assertEquals(2,downloads);assertEquals(4,metadata)
                    sessionB.back();b.collections!!.close();b.collections.awaitClosed()
                    // Removing Library changes neither successfully recorded History nor progress.
                    value(dbB.library.remove(id));assertEquals(1,value(dbB.history.listRecent()).size)
                    val progress=FileReadingProgressStore(root.resolve("progress")).get(ReadingProgressId(id,GUTENBERG_TEXT_KEY,PublicationFormat.TEXT))
                    assertEquals(400_000L,(progress!!.locator as ReadingLocator.Text).codePointOffset)
                } finally {b.close();b.awaitProgressClosed();dbB.close()}
            } finally {root.toFile().deleteRecursively()}
        }
    }

    @Test fun searchOpenBackRetainsResultsAndRepeatedOpenRefreshes()=runTest {
        withContext(Dispatchers.Default) {
            val root=Files.createTempDirectory("gutenberg-session");val fake=FakeCollections();var n=0
            val source=GutenbergSource(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf")){n++;respond(rdfFixture(),headers=headersOf(HttpHeaders.ContentType,"application/rdf+xml"))}
                else if(request.url.encodedPath.endsWith(".txt"))respond("Hello world!",headers=headersOf(HttpHeaders.ContentType to listOf(GUTENBERG_TEXT_MIME),HttpHeaders.ContentLength to listOf("12")))
                else respond(catalogFixture(),headers=headersOf(HttpHeaders.ContentType,"application/atom+xml"))},::testGutenbergXml)
            val owner=ApplicationSources(listOf(SourceOption("Gutenberg",source,true)),collections=ApplicationCollections(fake.library,fake.history),textPreparer=FileTextPreparer(root)) {source.close()}
            val session=ApplicationSession(owner,this);owner.attach(session)
            try {
                val search=session.searchSession.value;search.editQuery("books");search.submitSearch()
                val results=assertIs<SearchState.Results>(search.search.state.first{it is SearchState.Results || it is SearchState.Error || it is SearchState.Empty})
                session.openSearch(results.result.page.publications.single());assertIs<OpenPublicationState.Ready>(session.terminal());session.back()
                assertEquals("books",search.query.value);assertSame(results,search.search.state.value)
                session.openSearch(results.result.page.publications.single());assertIs<OpenPublicationState.Ready>(session.terminal())
                assertEquals(4,n)
            } finally {owner.close();owner.awaitProgressClosed();root.toFile().deleteRecursively()}
            assertEquals(1,fake.opened.size)
        }
    }

    @Test fun failedAcquisitionKeepsLibraryAndPriorProgressAndNoHistory()=runTest {
        withContext(Dispatchers.Default) {
            val root=Files.createTempDirectory("gutenberg-failure");val fake=FakeCollections();val snapshot=PublicationSnapshot(id,"Old",PublicationType.BOOK)
            fake.saved[id]=LibraryEntry(snapshot,1)
            val record=ReadingProgress(ReadingProgressId(id,GUTENBERG_TEXT_KEY,PublicationFormat.TEXT),ReadingLocator.Text(5,12),5.0/12,1)
            val store=FileReadingProgressStore(root.resolve("progress"));assertTrue(store.save(record))
            val source=GutenbergSource(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(),headers=headersOf(HttpHeaders.ContentType,"application/rdf+xml"))else respond("",HttpStatusCode.ServiceUnavailable)},::testGutenbergXml)
            val owner=ApplicationSources(listOf(SourceOption("Gutenberg",source,true)),progress=ProgressPersistence(store),collections=ApplicationCollections(fake.library,fake.history),textPreparer=FileTextPreparer(root.resolve("text"))) {source.close()}
            val session=ApplicationSession(owner,this);owner.attach(session)
            try{session.openSaved(snapshot);assertIs<OpenPublicationState.Error>(session.terminal());assertEquals(record,store.get(record.id));assertEquals(snapshot,fake.saved[id]!!.publication)}
            finally{owner.close();owner.awaitProgressClosed();root.toFile().deleteRecursively()}
            assertTrue(fake.opened.isEmpty())
        }
    }

    @Test fun cancellationDuringMetadataLeavesNoHistoryAndClosesSource()=runTest {
        withContext(Dispatchers.Default) {
            val root=Files.createTempDirectory("gutenberg-cancel-session");val fake=FakeCollections();val entered=CompletableDeferred<Unit>()
            val source=GutenbergSource(MockEngine{entered.complete(Unit);awaitCancellation()},::testGutenbergXml)
            val owner=ApplicationSources(listOf(SourceOption("Gutenberg",source,true)),collections=ApplicationCollections(fake.library,fake.history),textPreparer=FileTextPreparer(root)){source.close()}
            val session=ApplicationSession(owner,this);owner.attach(session)
            try{session.openSearch(Publication(id,"Title",PublicationType.BOOK));entered.await();session.back();assertIs<OpenPublicationState.Idle>(session.opening.value)}
            finally{owner.close();owner.awaitProgressClosed();root.toFile().deleteRecursively()}
            assertTrue(fake.opened.isEmpty())
        }
    }
    @Test fun cancelledAfterPreparationNeverHandsOffDocumentOrRecordsHistory()=runTest {
        withContext(Dispatchers.Default) {
            val root=Files.createTempDirectory("gutenberg-handoff");val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var opened=0
            val source=GutenbergSource(MockEngine{request->if(request.url.encodedPath.endsWith(".rdf"))respond(rdfFixture(),headers=headersOf(HttpHeaders.ContentType,"application/rdf+xml"))
                else respond("Hello world!",headers=headersOf(HttpHeaders.ContentType to listOf(GUTENBERG_TEXT_MIME),HttpHeaders.ContentLength to listOf("12")))},::testGutenbergXml)
            val progress=ProgressPersistence(object : ReadingProgressStore {
                override suspend fun get(id: ReadingProgressId): ReadingProgress? {entered.complete(Unit);withContext(NonCancellable){release.await()};return null}
                override suspend fun save(progress: ReadingProgress)=true
                override suspend fun remove(id: ReadingProgressId)=true
            })
            val preparer=FileTextPreparer(root)
            val opener=OpenPublicationController(source,DirectResourceLoader(source),this,progress=progress,preparer=preparer,onOpened={opened++})
            try {
                opener.open(Publication(id,"Catalog",PublicationType.BOOK));entered.await()
                assertEquals(1L,Files.walk(root).use{it.filter{p->p.toString().endsWith(".utf8")}.count()})
                opener.cancel();release.complete(Unit)
                // Close/drain the independent owner; no late document may remain reusable.
                opener.close();preparer.close();preparer.awaitClosed()
                assertIs<OpenPublicationState.Idle>(opener.state.value);assertEquals(0,opened)
                assertEquals(0L,Files.walk(root).use{it.filter{p->p.toString().endsWith(".utf8")}.count()})
            }finally{release.complete(Unit);opener.close();source.close();progress.close();progress.awaitClosed();preparer.close();preparer.awaitClosed();root.toFile().deleteRecursively()}
        }
    }

}
