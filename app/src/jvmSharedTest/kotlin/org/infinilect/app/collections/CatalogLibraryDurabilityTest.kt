// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.*
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.progress.FileReadingProgressStore
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.search.SearchState
import org.infinilect.core.*

/** Real committed SQLite files and completely new owners/drivers, offline.
 * Shared host execution does not claim physical Android OS/provider verification.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogLibraryDurabilityTest {
    private lateinit var root: java.nio.file.Path
    @BeforeTest fun setup() { root=Files.createTempDirectory("infinilect-catalog-library") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    private class Source(override val id: SourceId=SourceId("internet-archive")) : PublicationSource {
        val resource=PublicationResource(PublicationId(id,"1"),"text",PublicationFormat.TEXT,"text/plain",revision=null)
        val details=Publication(resource.publicationId,"Fresh title",PublicationType.BOOK,listOf("Author"),listOf(resource),
            languages=listOf("en"),rights="https://creativecommons.org/publicdomain/zero/1.0/")
        val catalog=details.copy(title="Catalog title")
        var searches=0;var metadata=0;var loads=0;var closedHandles=0;var failDetails=false
        override suspend fun search(query: String,pageToken: String?): SearchPage {
            searches++;return SearchPage(listOf(catalog),"opaque-next")
        }
        override suspend fun getPublication(publicationId: PublicationId): Publication? {
            assertEquals(details.id,publicationId);metadata++;return if(failDetails)null else details
        }
        override suspend fun loadResource(resource: PublicationResource): ResourceContent {
            assertEquals(this.resource,resource);loads++
            return object : ResourceContent {
                override val sizeBytes=10L;var done=false;var closed=false
                override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                    if(done)return -1;done=true;"0123456789".encodeToByteArray().copyInto(buffer,offset);return 10
                }
                override fun close() { if(!closed){closed=true;closedHandles++} }
            }
        }
    }
    private fun <T> value(result: LocalStoreResult<T>)=assertIs<LocalStoreResult.Success<T>>(result).value
    private inner class Fixture(
        val source: Source,val store: SqlCollectionsStore,val progressStore: FileReadingProgressStore,
        val owner: ApplicationSources,val session: ApplicationSession,
    ) {
        suspend fun close() { session.close();owner.close();owner.awaitProgressClosed() }
    }
    private fun TestScope.fixture(source: Source=Source(), failCommit: Boolean=false): Fixture {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val store=SqlCollectionsStore({ JdbcSqliteDriver("jdbc:sqlite:${root.resolve(COLLECTIONS_DATABASE_NAME)}",
            collectionsJdbcProperties()).also(::initializeCollectionsSchema) },dispatcher,
            beforeCommit={if(failCommit)throw IOException("private SQL/path/user data")})
        val collections=ApplicationCollections(store.library,store.history,dispatcher,store::close)
        val progressStore=FileReadingProgressStore(root.resolve("reading-progress-v1"),dispatcher=dispatcher)
        val progress=ProgressPersistence(progressStore,dispatcher){10L}
        val cache=DiskResourceCache(root.resolve("resource-cache-v1"),ioDispatcher=dispatcher)
        val owner=ApplicationSources(listOf(SourceOption("Internet Archive",source,true)),
            createLoader={cache.loader(it.id,DirectResourceLoader(it))},progress=progress,collections=collections) {cache.close()}
        return Fixture(source,store,progressStore,owner,ApplicationSession(owner,this,dispatcher){10L})
    }
    private fun TestScope.search(f: Fixture): SearchState.Results {
        val session=f.session.searchSession.value;session.editQuery("retained query");session.submitSearch();advanceUntilIdle()
        return assertIs<SearchState.Results>(session.search.state.value)
    }
    private fun TestScope.add(f: Fixture) { f.session.collections.toggleCatalogLibrary(f.source.catalog);advanceUntilIdle() }

    @Test fun catalogSaveSurvivesCompleteOwnerAndRepositoryRestart()=runTest {
        suspend fun originalOwner() {
            val first=fixture();search(first);add(first)
            assertEquals(true,first.session.collections.membership.value.forPublication(first.source.details.id).inLibrary)
            assertEquals(PublicationSnapshot.from(first.source.catalog),value(first.store.library.get(first.source.details.id))?.publication)
            first.close()
        }
        originalOwner();assertTrue(Files.size(root.resolve(COLLECTIONS_DATABASE_NAME))>0)
        val restarted=fixture();search(restarted)
        assertEquals(true,restarted.session.collections.membership.value.forPublication(restarted.source.catalog.id).inLibrary)
        assertEquals(0,restarted.source.metadata);assertEquals(0,restarted.source.loads)
        assertEquals(1,value(restarted.store.library.list()).size);restarted.close()
    }
    @Test fun catalogAddDoesNotAcquireOrCreateHistoryProgressOrCache()=runTest {
        val f=fixture();val state=search(f);add(f)
        assertSame(state,f.session.searchSession.value.search.state.value);assertEquals(1,f.source.searches)
        assertEquals(0,f.source.metadata);assertEquals(0,f.source.loads);assertEquals(0,f.source.closedHandles)
        assertTrue(value(f.store.history.listRecent()).isEmpty())
        assertFalse(Files.exists(root.resolve("reading-progress-v1")));assertFalse(Files.exists(root.resolve("resource-cache-v1")))
        f.close()
    }
    @Test fun catalogRemoveKeepsCommittedHistoryProgressAndCacheFiles()=runTest {
        val f=fixture();search(f);add(f)
        val id=f.source.details.id;value(f.store.history.recordOpened(PublicationSnapshot.from(f.source.details),1))
        val key=ReadingProgressId(id,"text",PublicationFormat.TEXT)
        val position=ReadingProgress(key,ReadingLocator.Text(6,10),0.6,1);assertTrue(f.progressStore.save(position))
        val cache=root.resolve("resource-cache-v1");Files.createDirectories(cache);Files.write(cache.resolve("unrelated-fixture"),byteArrayOf(4,5))
        f.session.collections.toggleCatalogLibrary(f.source.catalog);advanceUntilIdle()
        assertFalse(value(f.store.library.contains(id)));assertEquals(1L,value(f.store.history.listRecent()).single().lastOpenedAtEpochMillis)
        assertEquals(position,f.progressStore.get(key));assertContentEquals(byteArrayOf(4,5),Files.readAllBytes(cache.resolve("unrelated-fixture")))
        assertEquals(0,f.source.metadata);assertEquals(0,f.source.loads);f.close()
    }
    @Test fun failedCatalogPutCannotMasqueradeAsSavedAfterNewOwnerRestart()=runTest {
        val failed=fixture(failCommit=true);search(failed);add(failed)
        assertEquals(false,failed.session.collections.membership.value.forPublication(failed.source.catalog.id).inLibrary)
        assertEquals("The library change could not be saved on this device.",failed.session.collections.error.value);failed.close()
        val restarted=fixture();search(restarted)
        assertEquals(false,restarted.session.collections.membership.value.forPublication(restarted.source.catalog.id).inLibrary)
        assertTrue(value(restarted.store.library.list()).isEmpty());restarted.close()
    }
    @Test fun failedCatalogRemoveRetainsPreviousDiskCommitAfterRestart()=runTest {
        val original=fixture();search(original);add(original);original.close()
        val failed=fixture(failCommit=true);search(failed);failed.session.collections.toggleCatalogLibrary(failed.source.catalog);advanceUntilIdle()
        assertEquals(true,failed.session.collections.membership.value.forPublication(failed.source.catalog.id).inLibrary);assertNotNull(failed.session.collections.error.value);failed.close()
        val restarted=fixture();advanceUntilIdle();assertEquals(1,value(restarted.store.library.list()).size);restarted.close()
    }
    @Test fun catalogToReaderMembershipAndBackRetainSearchAndPagination()=runTest {
        val f=fixture();val results=search(f);add(f)
        val key=ReadingProgressId(f.source.details.id,"text",PublicationFormat.TEXT)
        assertTrue(f.progressStore.save(ReadingProgress(key,ReadingLocator.Text(6,10),0.6,1)))
        f.session.openSearch(f.source.catalog);advanceUntilIdle()
        val ready=assertIs<OpenPublicationState.Ready>(f.session.opening.value)
        assertEquals("Fresh title",ready.document.title);assertEquals(6,assertNotNull(ready.reading).utf16Offset.value)
        assertEquals(true,f.session.collections.reader.value.inLibrary);assertEquals(1,f.source.metadata);assertEquals(1,f.source.loads)
        f.session.collections.toggleLibrary();advanceUntilIdle();f.session.back();advanceUntilIdle()
        assertEquals(false,f.session.collections.membership.value.forPublication(f.source.catalog.id).inLibrary)
        assertSame(results,f.session.searchSession.value.search.state.value);assertEquals("opaque-next",results.result.page.nextPageToken)
        assertEquals("retained query",f.session.searchSession.value.query.value);assertEquals(0,f.session.selected.value);assertEquals(1,f.source.searches)
        assertEquals(1,value(f.store.history.listRecent()).size);assertNull(f.source.resource.revision);assertNull(f.source.resource.cacheKey)
        f.close()
    }
    @Test fun savedCatalogOpenStillSourceResolvesAndReturnsToLibraryAndHistory()=runTest {
        val f=fixture();val retained=search(f);add(f)
        for(destination in listOf(Destination.LIBRARY,Destination.HISTORY)) {
            f.session.navigate(destination)
            f.session.openSaved(PublicationSnapshot.from(f.source.catalog));advanceUntilIdle()
            assertIs<OpenPublicationState.Ready>(f.session.opening.value);f.session.back();advanceUntilIdle()
            assertEquals(destination,f.session.destination.value);f.session.back();advanceUntilIdle()
            assertEquals(Destination.SEARCH,f.session.destination.value)
            assertSame(retained,f.session.searchSession.value.search.state.value)
        }
        assertEquals(2,f.source.metadata);assertEquals(2,f.source.loads);assertEquals(1,value(f.store.history.listRecent()).size)
        assertFalse(Files.exists(root.resolve("resource-cache-v1")));f.close()
    }
    @Test fun unavailableSavedPublicationKeepsLibraryAndDoesNotRecordHistory()=runTest {
        val f=fixture();search(f);add(f);f.source.failDetails=true
        f.session.navigate(Destination.LIBRARY);f.session.openSaved(PublicationSnapshot.from(f.source.catalog));advanceUntilIdle()
        assertIs<OpenPublicationState.Error>(f.session.opening.value);assertEquals(1,value(f.store.library.list()).size)
        assertTrue(value(f.store.history.listRecent()).isEmpty());assertEquals(0,f.source.loads);f.close()
    }
    @Test fun ownerRecreationAndCacheOnlyDeletionRetainCatalogMembership()=runTest {
        repeat(3){iteration ->
            val f=fixture();search(f)
            if(iteration==0)add(f)
            assertEquals(true,f.session.collections.membership.value.forPublication(f.source.details.id).inLibrary)
            f.close();root.resolve("resource-cache-v1").toFile().deleteRecursively()
        }
    }
}
