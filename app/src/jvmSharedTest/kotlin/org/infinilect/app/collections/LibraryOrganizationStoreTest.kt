// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import java.io.IOException
import java.nio.file.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryOrganizationStoreTest {
    private lateinit var root: Path
    private val file get() = root.resolve(COLLECTIONS_DATABASE_NAME)
    private fun publication(key: String = "one") = PublicationSnapshot(PublicationId(SourceId("original"),key),"Original $key",PublicationType.BOOK,listOf("Author"))
    private fun driver() = JdbcSqliteDriver("jdbc:sqlite:$file",collectionsJdbcProperties()).also(::initializeCollectionsSchema)
    private fun store(hook: () -> Unit = {}) = SqlCollectionsStore(::driver,beforeCommit = hook)
    private fun <T> success(result: LocalStoreResult<T>) = assertIs<LocalStoreResult.Success<T>>(result).value
    @BeforeTest fun setup() { root = Files.createTempDirectory("library-organization") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    @Test fun newEntryAndPriorHistoryHaveConservativeDefaults() = runTest {
        val s = store(); val p = publication(); val prior = publication("prior")
        success(s.library.put(p,1)); assertEquals(LibraryReadingState.NOT_STARTED,success(s.library.get(p.id))!!.readingState)
        success(s.history.recordOpened(prior,2));success(s.library.put(prior,3))
        val entry = success(s.library.get(prior.id))!!
        assertEquals(LibraryReadingState.READING,entry.readingState);assertEquals(2,entry.lastOpenedAtEpochMillis)
        assertFalse(entry.stateIsExplicit);assertFalse(entry.favorite);assertTrue(entry.tags.isEmpty());s.close()
    }
    @Test fun everyManualStateSurvivesReopeningAndFavoriteIsIndependent() = runTest {
        val s=store();val p=publication();success(s.library.put(p,1))
        LibraryReadingState.entries.forEachIndexed { index,state ->
            success(s.library.organize(setOf(p.id),LibraryOrganizationChange(state,true)))
            success(s.history.recordOpened(p,10L+index))
            val entry=success(s.library.get(p.id))!!
            assertEquals(state,entry.readingState);assertTrue(entry.stateIsExplicit);assertTrue(entry.favorite)
            success(s.library.organize(setOf(p.id),LibraryOrganizationChange(favorite=false)))
            assertEquals(state,success(s.library.get(p.id))!!.readingState)
        };s.close()
    }
    @Test fun realOpenStartsOnlyImplicitNotStartedAndNeverAutoCompletes() = runTest {
        val s=store();val p=publication();success(s.library.put(p,1));success(s.history.recordOpened(p,2))
        assertEquals(LibraryReadingState.READING,success(s.library.get(p.id))!!.readingState)
        success(s.history.remove(p.id));success(s.history.recordOpened(p,3))
        assertEquals(LibraryReadingState.READING,success(s.library.get(p.id))!!.readingState);s.close()
    }
    @Test fun sharedUnicodeTagsRenameDeleteAndMetadataRefreshPreserveMembership() = runTest {
        val s=store();val a=publication();val b=publication("two")
        success(s.library.put(a,1));success(s.library.put(b,2))
        val tag=success(s.library.createTag("  Ciència   FICCIÓ 📚  "))
        assertEquals(tag,success(s.library.createTag("Ciència FICCIÓ 📚")))
        assertEquals(tag,success(s.library.createTag("Cie\u0300ncia ficcio\u0301 📚")))
        success(s.library.organize(setOf(a.id,b.id),LibraryOrganizationChange(LibraryReadingState.PAUSED,true,setOf(tag.id))))
        success(s.library.put(a.copy(title="New metadata",authors=listOf("Changed")),3))
        success(s.library.renameTag(tag.id,"Relectura"))
        for(p in listOf(a,b)) {
            val entry=success(s.library.get(p.id))!!
            assertEquals(listOf(LibraryTag(tag.id,"Relectura")),entry.tags)
            assertEquals(LibraryReadingState.PAUSED,entry.readingState);assertTrue(entry.favorite)
        }
        s.close();val reopened=store();assertEquals(2,success(reopened.library.list()).size)
        success(reopened.library.deleteTag(tag.id));assertTrue(success(reopened.library.tags()).isEmpty())
        assertTrue(success(reopened.library.list()).all { it.tags.isEmpty() && it.favorite });reopened.close()
    }
    @Test fun tagValidationCapacityAndDuplicatesNeverDamageExistingNames() = runTest {
        val s=store();val tag=success(s.library.createTag("Fantasy"));val other=success(s.library.createTag("Other"))
        assertEquals(LocalStoreResult.InvalidInput,s.library.renameTag(other.id," FANTASY "))
        for(name in listOf("", "   ", "line\nbreak", "x".repeat(65), "bad\u0000")) assertEquals(LocalStoreResult.InvalidInput,s.library.createTag(name))
        assertEquals("Fantasy",success(s.library.tags()).first { it.id==tag.id }.name)
        repeat(98) {success(s.library.createTag("Tag $it"))}
        assertEquals(LocalStoreResult.CapacityReached,s.library.createTag("Over limit"));assertEquals(100,success(s.library.tags()).size);s.close()
    }
    @Test fun batchValidationIsAllOrNothingAndMembershipRemovalKeepsTagsAndHistory() = runTest {
        val s=store();val a=publication();val b=publication("two");val missing=publication("missing")
        for(p in listOf(a,b)) {success(s.library.put(p,1));success(s.history.recordOpened(p,2))}
        val tags=List(21) {success(s.library.createTag("Tag $it"))}
        assertEquals(LocalStoreResult.InvalidInput,s.library.organize(setOf(a.id,missing.id),LibraryOrganizationChange(favorite=true)))
        assertTrue(success(s.library.list()).none { it.favorite })
        assertEquals(LocalStoreResult.CapacityReached,s.library.organize(setOf(a.id,b.id),LibraryOrganizationChange(favorite=true,addTags=tags.map {it.id}.toSet())))
        assertTrue(success(s.library.list()).none { it.favorite || it.tags.isNotEmpty() })
        success(s.library.organize(setOf(a.id,b.id),LibraryOrganizationChange(favorite=true,addTags=setOf(tags[0].id))))
        success(s.library.organize(setOf(a.id),LibraryOrganizationChange(addTags=setOf(tags[1].id),removeTags=setOf(tags[0].id))))
        assertEquals(listOf(tags[1]),success(s.library.get(a.id))!!.tags)
        assertEquals(listOf(tags[0]),success(s.library.get(b.id))!!.tags)
        success(s.library.remove(a.id));assertEquals(21,success(s.library.tags()).size);assertEquals(2,success(s.history.listRecent()).size);s.close()
    }
    @Test fun failureBeforeCommitRollsBackWholeBatchAndRenameAndDelete() = runTest {
        val p=publication();val other=publication("two");val initial=store()
        success(initial.library.put(p,1));success(initial.library.put(other,2));val tag=success(initial.library.createTag("Original"))
        success(initial.library.organize(setOf(p.id),LibraryOrganizationChange(addTags=setOf(tag.id))));initial.close()
        val failed=store {throw IOException("synthetic unavailable storage")}
        assertEquals(LocalStoreResult.Unavailable,failed.library.organize(setOf(p.id,other.id),LibraryOrganizationChange(LibraryReadingState.COMPLETED,true)))
        assertEquals(LocalStoreResult.Unavailable,failed.library.renameTag(tag.id,"Not saved"))
        assertEquals(LocalStoreResult.Unavailable,failed.library.deleteTag(tag.id));failed.close()
        val reopened=store();assertTrue(success(reopened.library.list()).all {it.readingState==LibraryReadingState.NOT_STARTED && !it.favorite})
        assertEquals(listOf(tag),success(reopened.library.get(p.id))!!.tags);reopened.close()
    }
    @Test fun simultaneousDeltaWritesDoNotLoseFavoritesStatesOrTagAssignments() = runTest {
        val s=store();val p=publication();success(s.library.put(p,1));val tags=List(12) {success(s.library.createTag("Tag $it"))}
        coroutineScope {
            (tags.map { tag -> async(Dispatchers.Default) {success(s.library.organize(setOf(p.id),LibraryOrganizationChange(addTags=setOf(tag.id))))} } +
                async(Dispatchers.Default) {success(s.library.organize(setOf(p.id),LibraryOrganizationChange(favorite=true)))} +
                async(Dispatchers.Default) {success(s.library.organize(setOf(p.id),LibraryOrganizationChange(state=LibraryReadingState.PAUSED)))}).awaitAll()
        }
        val entry=success(s.library.get(p.id))!!;assertEquals(tags.map{it.id}.toSet(),entry.tags.map{it.id}.toSet());assertTrue(entry.favorite);assertEquals(LibraryReadingState.PAUSED,entry.readingState);s.close()
    }
    @Test fun realV1DatabaseMigratesOnceWithoutChangingMetadataOrHistory() = runTest {
        JdbcSqliteDriver("jdbc:sqlite:$file",collectionsJdbcProperties()).use { d ->
            val sql=checkNotNull(javaClass.getResource("/collections-v1.sql")).readText()
            sql.split(';').map {it.trim()}.filter {it.isNotEmpty()}.forEach {d.execute(null,it,0)}
            for(key in listOf("one","two","three")) d.execute(null,"INSERT INTO library_entries VALUES ('original','$key','Original $key','BOOK',NULL,NULL,1,${if(key=="two") "2" else "NULL"})",0)
            d.execute(null,"INSERT INTO reading_history VALUES ('original','three','Original three','BOOK',NULL,NULL,3)",0)
            d.execute(null,"INSERT INTO library_authors VALUES ('original','one',0,'Unicode 作者')",0)
            d.execute(null,"PRAGMA user_version=1",0)
        }
        val migrated=store();val rows=success(migrated.library.list()).associateBy {it.publication.id.localId}
        assertEquals(3,rows.size);assertEquals(LibraryReadingState.NOT_STARTED,rows.getValue("one").readingState)
        for(key in listOf("two","three")) assertEquals(LibraryReadingState.READING,rows.getValue(key).readingState)
        assertEquals(listOf("Unicode 作者"),rows.getValue("one").publication.authors)
        assertEquals(1,success(migrated.history.listRecent()).size)
        success(migrated.library.organize(setOf(publication().id),LibraryOrganizationChange(LibraryReadingState.ABANDONED,true)))
        migrated.close();val reopened=store();assertEquals(LibraryReadingState.ABANDONED,success(reopened.library.get(publication().id))!!.readingState);reopened.close()
    }
    @Test fun validatedProgressEvidenceIsIdempotentAndNeverOverridesManualChoices() = runTest {
        val s=store();val a=publication();val b=publication("two")
        success(s.library.put(a,1));success(s.library.put(b,1))
        success(s.library.organize(setOf(b.id),LibraryOrganizationChange(state=LibraryReadingState.NOT_STARTED)))
        assertEquals(1,success(s.library.recordProgressEvidence(setOf(a.id,b.id))))
        assertEquals(0,success(s.library.recordProgressEvidence(setOf(a.id,b.id))))
        assertEquals(LibraryReadingState.READING,success(s.library.get(a.id))!!.readingState)
        assertEquals(LibraryReadingState.NOT_STARTED,success(s.library.get(b.id))!!.readingState)
        assertFalse(success(s.library.get(a.id))!!.stateIsExplicit);assertTrue(success(s.history.listRecent()).isEmpty());s.close()
    }
    @Test fun cancellationBeforeCommitPreservesEveryEntryAndTag() = runTest {
        val p=publication();val initial=store();success(initial.library.put(p,1));val tag=success(initial.library.createTag("Keep"));initial.close()
        val canceled=store {throw CancellationException("Controlled cancellation")}
        assertFailsWith<CancellationException> {canceled.library.organize(setOf(p.id),LibraryOrganizationChange(favorite=true,addTags=setOf(tag.id)))}
        canceled.close();val s=store();val entry=success(s.library.get(p.id))!!
        assertFalse(entry.favorite);assertTrue(entry.tags.isEmpty());assertEquals(listOf(tag),success(s.library.tags()));s.close()
    }
    @Test fun fiveThousandEntriesAndAtomicBatchStayWithinExistingDatabaseBudget() = runTest {
        val s=store();val publications=List(5000) {publication("$it")}
        for(p in publications) success(s.library.put(p,1))
        val ids=publications.map {it.id}.toSet()
        val samples=List(5) { iteration ->
            val start=System.nanoTime()
            success(s.library.organize(ids,LibraryOrganizationChange(favorite=iteration%2==0)))
            (System.nanoTime()-start)/1_000_000.0
        }
        System.getenv("INFINILECT_LIBRARY_METRICS")?.let {directory ->
            val path=Path.of(directory);require(path.isAbsolute);Files.createDirectories(path)
            Files.writeString(path.resolve("library-batch-host-${System.getProperty("infinilect.test.platform","jvm")}.txt"),
                "5000-row atomic favorite deltas; five host wall-time samples in ms: $samples\nMedian=${samples.sorted()[2]}; includes IO scheduling and FULL synchronous commit; not Android device timings.\n")
        }
        assertEquals(5000,success(s.library.list()).count {it.favorite})
        assertEquals(LocalStoreResult.CapacityReached,s.library.put(publication("overflow"),2))
        assertTrue(Files.size(file)<MAX_COLLECTIONS_DATABASE_BYTES);s.close()
    }
}
