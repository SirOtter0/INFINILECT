// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.*
import org.infinilect.app.collections.*
import org.infinilect.app.progress.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class LargeTextSessionTest {
    private class Source : PublicationSource {
        override val id = SourceId("internet-archive")
        val resource = PublicationResource(PublicationId(id,"large"),"text",PublicationFormat.TEXT,"text/plain")
        val publication = Publication(resource.publicationId,"Fresh title",PublicationType.BOOK,resources=listOf(resource))
        var metadata = 0; var acquisitions = 0; var closes = 0
        override suspend fun search(query: String,pageToken: String?) = SearchPage(listOf(publication.copy(resources=emptyList())))
        override suspend fun getPublication(publicationId: PublicationId): Publication { assertEquals(publication.id,publicationId); metadata++; return publication }
        override suspend fun loadResource(resource: PublicationResource): ResourceContent {
            assertEquals(this.resource,resource); acquisitions++
            return object : ResourceContent {
                override val sizeBytes = 2L * 1024 * 1024; var cursor = 0L
                override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                    if (cursor==sizeBytes) return -1
                    val n=minOf(length.toLong(),sizeBytes-cursor).toInt()
                    repeat(n){buffer[offset+it]=65};cursor+=n;return n
                }
                override fun close(){closes++}
            }
        }
    }
    private suspend fun payloadCount(root: java.nio.file.Path) = Files.walk(root).use { it.filter { p -> p.toString().endsWith(".utf8") }.count() }

    @Test fun searchLargeReaderBackRetainsResultsAndReopenAcquiresAndRestores() = runTest {
        val root=Files.createTempDirectory("infinilect-large-session");val source=Source();val fake=FakeCollections()
        val dispatcher=StandardTestDispatcher(testScheduler)
        val progress=ProgressPersistence(FileReadingProgressStore(root.resolve("progress"),dispatcher=dispatcher),dispatcher)
        val owner=ApplicationSources(listOf(SourceOption("Archive",source,true)),progress=progress,
            collections=ApplicationCollections(fake.library,fake.history,dispatcher),textPreparer=FileTextPreparer(root.resolve("text"),dispatcher)){}
        val session=ApplicationSession(owner,this,dispatcher);owner.attach(session)
        try {
            val search=session.searchSession.value;search.editQuery("query");search.submitSearch();advanceUntilIdle();val results=search.search.state.value
            session.openSearch(source.publication);advanceUntilIdle();val ready=assertIs<OpenPublicationState.Ready>(session.opening.value)
            assertEquals(2097152,ready.document.codePoints);assertNotNull(ready.reading).report(1500000)
            session.back();advanceUntilIdle();assertEquals(0L,payloadCount(root));assertSame(results,search.search.state.value);assertEquals("query",search.query.value)
            session.openSearch(source.publication);advanceUntilIdle();assertEquals(1500000,assertNotNull(assertIs<OpenPublicationState.Ready>(session.opening.value).reading).codePointOffset.value)
            assertEquals(2,source.metadata);assertEquals(2,source.acquisitions);assertEquals(2,source.closes)
            assertEquals(1,fake.opened.size);assertNull(source.resource.revision);assertNull(source.resource.cacheKey)
        } finally { owner.close();owner.awaitProgressClosed();root.toFile().deleteRecursively() }
    }
    @Test fun savedLibraryLargeOpenReResolvesAndReturnsToLibraryWithoutMutations() = runTest {
        val root=Files.createTempDirectory("infinilect-large-library");val source=Source();val fake=FakeCollections();val dispatcher=StandardTestDispatcher(testScheduler)
        val snapshot=PublicationSnapshot.from(source.publication).copy(title="Stale title",sourceUrl="https://untrusted.invalid/file")
        fake.saved[snapshot.id]=LibraryEntry(snapshot,1)
        val owner=ApplicationSources(listOf(SourceOption("Archive",source,true)),collections=ApplicationCollections(fake.library,fake.history,dispatcher),
            textPreparer=FileTextPreparer(root.resolve("text"),dispatcher)){}
        val session=ApplicationSession(owner,this,dispatcher);owner.attach(session)
        try {
            session.navigate(Destination.LIBRARY);session.openSaved(snapshot);advanceUntilIdle()
            val document=assertIs<OpenPublicationState.Ready>(session.opening.value).document
            assertEquals("Fresh title",document.title);assertEquals(2097152,document.codePoints)
            assertEquals(1,source.metadata);assertEquals(1,source.acquisitions);assertEquals("Fresh title",fake.opened[snapshot.id]?.publication?.title)
            session.back();advanceUntilIdle();assertEquals(Destination.LIBRARY,session.destination.value)
            assertEquals(snapshot,fake.saved[snapshot.id]?.publication);assertEquals(0L,payloadCount(root))
        } finally {owner.close();owner.awaitProgressClosed();root.toFile().deleteRecursively()}
    }
    @Test fun cancellationAfterPreparationDuringProgressLookupDiscardsBacking() = runTest {
        val root=Files.createTempDirectory("infinilect-large-cancel");val source=Source();val dispatcher=StandardTestDispatcher(testScheduler)
        val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        val progress=ProgressPersistence(object : ReadingProgressStore {
            override suspend fun get(id: ReadingProgressId): ReadingProgress? {entered.complete(Unit);withContext(NonCancellable){gate.await()};return null}
            override suspend fun save(progress: ReadingProgress)=true
            override suspend fun remove(id: ReadingProgressId)=true
        },dispatcher)
        val preparer=FileTextPreparer(root.resolve("text"),dispatcher)
        val opener=OpenPublicationController(source,org.infinilect.app.acquisition.DirectResourceLoader(source),this,dispatcher,progress,preparer)
        try {
            opener.open(source.publication);entered.await();assertEquals(1L,payloadCount(root))
            opener.cancel();gate.complete(Unit);advanceUntilIdle();assertIs<OpenPublicationState.Idle>(opener.state.value)
            assertEquals(0L,payloadCount(root));assertEquals(1,source.closes)
        }finally{opener.close();progress.close();progress.awaitClosed();preparer.close();preparer.awaitClosed();root.toFile().deleteRecursively()}
    }
}
