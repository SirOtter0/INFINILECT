// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import java.nio.file.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.progress.FileReadingProgressStore
import org.infinilect.app.collections.database.LocalCollectionsDatabase
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class SqlCollectionsStoreTest {
    private lateinit var root: Path
    private val id=PublicationId(SourceId("fixture"),"1")
    private fun snapshot(key: PublicationId=id,title: String="Title",authors: List<String> = listOf("Author"))=
        PublicationSnapshot(key,title,PublicationType.BOOK,authors,listOf("en","es"),"https://example.org/detail","CC0 1.0")
    private val file get()=root.resolve(COLLECTIONS_DATABASE_NAME)
    private fun driver()=JdbcSqliteDriver("jdbc:sqlite:$file",collectionsJdbcProperties()).also(::initializeCollectionsSchema)
    private fun TestScope.store(limit: Int=1000,history: Int=500,hook: () -> Unit = {})=
        SqlCollectionsStore(::driver,StandardTestDispatcher(testScheduler),limit,history,hook)
    private fun <T> value(result: LocalStoreResult<T>)=assertIs<LocalStoreResult.Success<T>>(result).value
    @BeforeTest fun setup() { root=Files.createTempDirectory("infinilect-collections") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun addGetListContainsRemove()=runTest {
        val store=store(); assertTrue(value(store.library.list()).isEmpty()); assertFalse(value(store.library.contains(id)))
        value(store.library.put(snapshot(),10)); assertEquals(snapshot(),value(store.library.get(id))?.publication)
        assertTrue(value(store.library.contains(id))); assertEquals(1,value(store.library.list()).size)
        value(store.library.remove(id)); assertNull(value(store.library.get(id))); store.close()
    }
    @Test fun duplicateUpdateReplacesChildrenAndPreservesAddedTime()=runTest {
        val store=store(); value(store.library.put(snapshot(),1)); value(store.library.put(snapshot(title="New",authors=listOf("New author")),2))
        val entry=assertNotNull(value(store.library.get(id)))
        assertEquals(1L,entry.addedAtEpochMillis); assertEquals("New",entry.publication.title)
        assertEquals(listOf("New author"),entry.publication.authors); assertEquals(1,value(store.library.list()).size); store.close()
    }
    @Test fun authorsAndLanguagesRoundTripWithoutDelimiters()=runTest {
        val s=snapshot(authors=listOf("Last, First","Line\nBreak","作者 📚","Same","Same"))
        val store=store(); value(store.library.put(s,1)); value(store.history.recordOpened(s,2))
        assertEquals(s,value(store.library.get(id))?.publication); assertEquals(s,value(store.history.listRecent()).single().publication);store.close()
    }
    @Test fun sourceAndLocalIdsCannotAliasIncludingUnsafeCharacters()=runTest {
        val keys=listOf(id,id.copy(sourceId=SourceId("other")),id.copy(localId="2"),PublicationId(SourceId("a:b"),"c"),PublicationId(SourceId("a"),"b:c"),id.copy(localId="../../escape"))
        val store=store(); keys.forEach { value(store.library.put(snapshot(it),1)) }
        keys.forEach { assertEquals(it,value(store.library.get(it))?.publication?.id) }
        assertEquals(keys.size,value(store.library.list()).size); assertEquals(listOf(COLLECTIONS_DATABASE_NAME),Files.list(root).use {it.map {p->p.fileName.toString()}.toList()});store.close()
    }
    @Test fun orderingIsDeterministicForTimestampTies()=runTest {
        val store=store(); val a=id.copy(localId="a"); val b=id.copy(localId="b")
        value(store.library.put(snapshot(b),2));value(store.library.put(snapshot(a),2));value(store.library.put(snapshot(),1))
        assertEquals(listOf(a,b,id),value(store.library.list()).map {it.publication.id});store.close()
    }
    @Test fun historyNewestFirstAndDeduplicated()=runTest {
        val store=store();val other=id.copy(localId="2")
        value(store.history.recordOpened(snapshot(),1)); value(store.history.recordOpened(snapshot(other),2));value(store.history.recordOpened(snapshot(title="Fresh"),3))
        val entries=value(store.history.listRecent());assertEquals(listOf(id,other),entries.map {it.publication.id});assertEquals("Fresh",entries.first().publication.title);store.close()
    }
    @Test fun oldHistoryTimestampCannotMoveEntryBackwards()=runTest {
        val store=store();value(store.history.recordOpened(snapshot(),5));value(store.history.recordOpened(snapshot(title="Old"),1))
        assertEquals(5L,value(store.history.listRecent()).single().lastOpenedAtEpochMillis);assertEquals("Title",value(store.history.listRecent()).single().publication.title);store.close()
    }
    @Test fun historyLimitIsValidatedAndBounded()=runTest {
        val store=store();repeat(4){value(store.history.recordOpened(snapshot(id.copy(localId="$it")),it.toLong()))}
        assertEquals(2,value(store.history.listRecent(2)).size)
        assertEquals(LocalStoreResult.InvalidInput,store.history.listRecent(0));assertEquals(LocalStoreResult.InvalidInput,store.history.listRecent(101));store.close()
    }
    @Test fun historyRetentionPrunesOldestWithChildren()=runTest {
        val store=store(history=2);repeat(4){value(store.history.recordOpened(snapshot(id.copy(localId="$it")),it.toLong()))}
        assertEquals(listOf("3","2"),value(store.history.listRecent()).map {it.publication.id.localId})
        driver().use { d->assertEquals(2L,d.executeQuery(null,"SELECT count(*) FROM history_authors",{c->c.next();QueryResult.Value(c.getLong(0)!!)},0).value) };store.close()
    }
    @Test fun removeOneHistoryEntryAndClearKeepLibrary()=runTest {
        val store=store();value(store.library.put(snapshot(),1));value(store.history.recordOpened(snapshot(),2))
        value(store.history.recordOpened(snapshot(id.copy(localId="2")),3));value(store.history.remove(id))
        assertEquals(1,value(store.history.listRecent()).size);value(store.history.clear());assertTrue(value(store.history.listRecent()).isEmpty())
        assertNotNull(value(store.library.get(id)));store.close()
    }
    @Test fun removingLibraryKeepsHistoryAndOpenedTimestampIsUpdated()=runTest {
        val store=store();value(store.library.put(snapshot(),1));value(store.history.recordOpened(snapshot(),2))
        assertEquals(2L,value(store.library.get(id))?.lastOpenedAtEpochMillis)
        value(store.library.remove(id));assertEquals(1,value(store.history.listRecent()).size);store.close()
    }
    @Test fun completelyNewDriverAndRepositoriesRestoreCommittedMetadata()=runTest {
        suspend fun original() {val store=store();value(store.library.put(snapshot(),1));value(store.history.recordOpened(snapshot(),2));store.close()}
        original();assertTrue(Files.size(file)>0)
        val restarted=store();assertEquals(snapshot(),value(restarted.library.get(id))?.publication)
        assertEquals(2L,value(restarted.history.listRecent()).single().lastOpenedAtEpochMillis);restarted.close()
    }
    @Test fun failedWriteIsNotReturnedAsRamSuccessAndPreviousCommitSurvives()=runTest {
        val first=store();value(first.library.put(snapshot(),1));first.close()
        val failed=store(hook={throw IOException("private details")})
        assertEquals(LocalStoreResult.Unavailable,failed.library.put(snapshot(title="Not saved"),2));failed.close()
        val restarted=store();assertEquals("Title",value(restarted.library.get(id))?.publication?.title);restarted.close()
    }
    @Test fun failedFirstWriteLeavesNoLibraryOrHistoryEntry()=runTest {
        val failed=store(hook={throw IOException("write failure")})
        assertEquals(LocalStoreResult.Unavailable,failed.library.put(snapshot(),1));assertEquals(LocalStoreResult.Unavailable,failed.history.recordOpened(snapshot(),2));failed.close()
        val restarted=store();assertTrue(value(restarted.library.list()).isEmpty());assertTrue(value(restarted.history.listRecent()).isEmpty());restarted.close()
    }
    @Test fun cancellationRollsBackAtomicUpdate()=runTest {
        val first=store();value(first.library.put(snapshot(),1));first.close()
        val canceled=store(hook={throw CancellationException("cancel")})
        assertFailsWith<CancellationException>{ canceled.library.put(snapshot(title="Cancelled"),2) };canceled.close()
        val restarted=store();assertEquals("Title",value(restarted.library.get(id))?.publication?.title);restarted.close()
    }
    @Test fun libraryCapacityDoesNotEvictExistingMetadata()=runTest {
        val store=store(limit=1);value(store.library.put(snapshot(),1))
        assertEquals(LocalStoreResult.CapacityReached,store.library.put(snapshot(id.copy(localId="2")),2))
        value(store.library.put(snapshot(title="Update"),3));assertEquals(1,value(store.library.list()).size);store.close()
    }
    @Test fun invalidTimestampAndMutatedMetadataAreRejectedAtBoundary()=runTest {
        val authors=mutableListOf("Author");val s=snapshot(authors=authors);authors.add("x".repeat(513))
        val store=store();assertEquals(LocalStoreResult.InvalidInput,store.library.put(s,1));assertEquals(LocalStoreResult.InvalidInput,store.library.put(snapshot(),-1))
        assertEquals(LocalStoreResult.InvalidInput,store.history.recordOpened(snapshot(),-1));assertTrue(value(store.library.list()).isEmpty());store.close()
    }
    @Test fun corruptDatabaseIsUnavailableAndNotDeleted()=runTest {
        Files.write(file,"not a sqlite database".encodeToByteArray());val original=Files.readAllBytes(file)
        val store=store();assertEquals(LocalStoreResult.Unavailable,store.library.list());assertEquals(LocalStoreResult.Unavailable,store.history.listRecent())
        assertContentEquals(original,Files.readAllBytes(file));store.close()
    }
    @Test fun unsupportedFutureSchemaIsNotRecreated()=runTest {
        driver().use {it.execute(null,"PRAGMA user_version=99",0)}
        val store=store();assertEquals(LocalStoreResult.Unavailable,store.library.list())
        JdbcSqliteDriver("jdbc:sqlite:$file").use {d->assertEquals(99L,d.executeQuery(null,"PRAGMA user_version",{c->c.next();QueryResult.Value(c.getLong(0)!!)},0).value)};store.close()
    }
    @Test fun unavailableDriverAndRepeatedCloseAreSafe()=runTest {
        val store=SqlCollectionsStore({throw IOException("unavailable")},StandardTestDispatcher(testScheduler))
        assertEquals(LocalStoreResult.Unavailable,store.library.put(snapshot(),1));store.close();store.close();assertEquals(LocalStoreResult.Unavailable,store.history.clear())
    }
    @Test fun cacheDeletionAndCollectionMutationsNeverDeleteProgress()=runTest {
        val progressDir=root.resolve("reading-progress-v1");val progress=FileReadingProgressStore(progressDir,dispatcher=StandardTestDispatcher(testScheduler))
        val key=ReadingProgressId(id,"text",PublicationFormat.TEXT);val position=ReadingProgress(key,ReadingLocator.Text(4,10),0.4,1)
        assertTrue(progress.save(position));val cache=root.resolve("resource-cache-v1");Files.createDirectories(cache);Files.write(cache.resolve("disposable"),byteArrayOf(1))
        val store=store();value(store.library.put(snapshot(),1));value(store.history.recordOpened(snapshot(),2));cache.toFile().deleteRecursively()
        value(store.history.clear());assertNotNull(value(store.library.get(id)));value(store.library.remove(id));store.close()
        assertEquals(position,FileReadingProgressStore(progressDir).get(key));assertTrue(Files.exists(file))
    }
    @Test fun schemaVersionAndForeignKeysAreActiveAcrossIoThreads()=runTest {
        assertEquals(1L,LocalCollectionsDatabase.Schema.version)
        val store=SqlCollectionsStore(::driver)
        value(store.library.put(snapshot(),1));withContext(Dispatchers.Default){value(store.library.put(snapshot(authors=listOf("Changed")),2))}
        assertEquals(listOf("Changed"),value(store.library.get(id))?.publication?.authors)
        value(store.library.remove(id));driver().use {d->assertEquals(0L,d.executeQuery(null,"SELECT count(*) FROM library_authors",{c->c.next();QueryResult.Value(c.getLong(0)!!)},0).value)};store.close()
    }
    @Test fun parallelMutationsKeepIndependentPublications()=runTest {
        val store=SqlCollectionsStore(::driver)
        (1..12).map {async(Dispatchers.Default){value(store.library.put(snapshot(id.copy(localId="$it")),it.toLong()))}}.awaitAll()
        assertEquals(12,value(store.library.list()).size);store.close()
    }
}
