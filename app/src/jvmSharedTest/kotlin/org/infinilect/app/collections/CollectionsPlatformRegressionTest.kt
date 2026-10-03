// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.IOException
import java.nio.file.Files
import java.sql.SQLException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*
import org.infinilect.app.collections.CollectionsStorageFailure.Operation
import org.infinilect.app.collections.CollectionsStorageFailure.Stage
import org.infinilect.app.collections.CollectionsStorageFailure.Reason

/** Host simulation of AOSP executeNonQuery rejecting SQLITE_ROW, not an Android OS test. */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsPlatformRegressionTest {
    private lateinit var root: java.nio.file.Path
    private val snapshot = PublicationSnapshot(PublicationId(SourceId("fixture"),"1"),"Title",PublicationType.BOOK)
    private val file get() = root.resolve(COLLECTIONS_DATABASE_NAME)
    private fun driver() = JdbcSqliteDriver("jdbc:sqlite:$file",collectionsJdbcProperties()).also(::initializeCollectionsSchema)
    @BeforeTest fun setup() { root=Files.createTempDirectory("infinilect-collections-platform") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    private fun <T> value(result: LocalStoreResult<T>) = assertIs<LocalStoreResult.Success<T>>(result).value

    private class AndroidCommandContract(private val delegate: SqlDriver) : SqlDriver by delegate {
        var pageLimitQueries=0
        override fun execute(identifier: Int?,sql: String,parameters: Int,binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (sql.startsWith("PRAGMA busy_timeout") || sql.startsWith("PRAGMA max_page_count"))
                throw SQLException("Host simulation: AOSP non-query rejects SQLITE_ROW")
            return delegate.execute(identifier,sql,parameters,binders)
        }
        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
            if(sql.startsWith("PRAGMA max_page_count=")) pageLimitQueries++
            return delegate.executeQuery(identifier,sql,mapper,parameters,binders)
        }
    }
    @Test fun oldResultReturningPragmaCommandsAreRejectedByAndroidContract() {
        AndroidCommandContract(driver()).use { d ->
            assertFailsWith<SQLException> { d.execute(null,"PRAGMA busy_timeout=3000",0) }
            assertFailsWith<SQLException> { d.execute(null,"PRAGMA max_page_count=16384",0) }
            assertEquals(3000L,collectionsScalar(d,"PRAGMA busy_timeout=3000"))
            assertEquals(16384L,collectionsScalar(d,"PRAGMA max_page_count=16384"))
        }
    }
    @Test fun strictAndroidCommandContractCommitsAndRestoresWithNewStoreAndDriver() = runTest {
        var firstDriver: AndroidCommandContract?=null
        val first=SqlCollectionsStore({ AndroidCommandContract(driver()).also { firstDriver=it } },StandardTestDispatcher(testScheduler))
        value(first.library.put(snapshot,1));value(first.history.recordOpened(snapshot,2))
        assertTrue(firstDriver!!.pageLimitQueries>=2);first.close();firstDriver=null
        assertTrue(Files.size(file)>0)
        val restarted=SqlCollectionsStore({ AndroidCommandContract(driver()) },StandardTestDispatcher(testScheduler))
        assertEquals(snapshot,value(restarted.library.get(snapshot.id))?.publication)
        assertEquals(snapshot,value(restarted.history.listRecent()).single().publication);restarted.close()
    }
    @Test fun pageLimitQueryFailureIsDiagnosedAndCannotOverwritePreviousCommit() = runTest {
        val original=SqlCollectionsStore(::driver,StandardTestDispatcher(testScheduler))
        value(original.library.put(snapshot,1));original.close()
        val failures=mutableListOf<CollectionsStorageFailure>()
        val broken=SqlCollectionsStore({
            val d=driver()
            object : SqlDriver by d {
                override fun <R> executeQuery(identifier: Int?,sql: String,mapper: (SqlCursor) -> QueryResult<R>,parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                    if(sql.startsWith("PRAGMA max_page_count=")) throw SQLException("secret path/title/SQL")
                    return d.executeQuery(identifier,sql,mapper,parameters,binders)
                }
            }
        },StandardTestDispatcher(testScheduler),onFailure=failures::add)
        assertEquals(LocalStoreResult.Unavailable,broken.library.put(snapshot.copy(title="Not saved"),3))
        assertEquals(CollectionsStorageFailure(Operation.LIBRARY_PUT,Stage.PAGE_LIMIT,Reason.DATABASE),failures.single())
        assertFalse(failures.toString().contains("secret"));broken.close()
        val restarted=SqlCollectionsStore(::driver,StandardTestDispatcher(testScheduler))
        assertEquals("Title",value(restarted.library.get(snapshot.id))?.publication?.title);restarted.close()
    }
    @Test fun ineffectivePageLimitIsRejectedBeforeMutation() = runTest {
        val failures=mutableListOf<CollectionsStorageFailure>()
        val store=SqlCollectionsStore({
            val d=driver()
            object : SqlDriver by d {
                override fun <R> executeQuery(identifier: Int?,sql: String,mapper: (SqlCursor) -> QueryResult<R>,parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> =
                    d.executeQuery(identifier,sql,{ cursor ->
                        mapper(object : SqlCursor by cursor {
                            override fun getLong(index: Int): Long? = cursor.getLong(index)?.let {
                                if(sql.startsWith("PRAGMA max_page_count=")) it+1 else it
                            }
                        })
                    },parameters,binders)
            }
        },StandardTestDispatcher(testScheduler),onFailure=failures::add)
        assertEquals(LocalStoreResult.Unavailable,store.library.put(snapshot,1))
        assertEquals(Stage.PAGE_LIMIT,failures.single().stage);assertEquals(Reason.INVALID_STATE,failures.single().reason);store.close()
        val restarted=SqlCollectionsStore(::driver,StandardTestDispatcher(testScheduler))
        assertTrue(value(restarted.library.list()).isEmpty());restarted.close()
    }
    @Test fun nestedAndroidInitializationStageSurvivesGenericOperationCatch() = runTest {
        val failures=mutableListOf<CollectionsStorageFailure>()
        val store=SqlCollectionsStore({ throw CollectionsStorageException(Stage.CONFIGURE_BUSY_TIMEOUT,Reason.DATABASE,
            SQLException("private identifier/path/SQL")) },StandardTestDispatcher(testScheduler),onFailure=failures::add)
        assertEquals(LocalStoreResult.Unavailable,store.history.listRecent())
        assertEquals(CollectionsStorageFailure(Operation.HISTORY_LIST,Stage.CONFIGURE_BUSY_TIMEOUT,Reason.DATABASE),failures.single())
        assertFalse(failures.single().toString().contains("private"));store.close()
    }
    @Test fun failingDiagnosticConsumerDoesNotChangeUnavailableResult() = runTest {
        val store=SqlCollectionsStore({ throw IOException("private path") },StandardTestDispatcher(testScheduler),
            onFailure={ throw IllegalStateException("logger failed") })
        assertEquals(LocalStoreResult.Unavailable,store.library.list());store.close()
    }
    @Test fun cancellationIsDiagnosedAndStillPropagatesWithRollback() = runTest {
        val failures=mutableListOf<CollectionsStorageFailure>()
        val store=SqlCollectionsStore(::driver,StandardTestDispatcher(testScheduler),beforeCommit={ throw CancellationException("private") },onFailure=failures::add)
        assertFailsWith<CancellationException>{ store.library.put(snapshot,1) }
        assertEquals(Reason.CANCELLED,failures.single().reason);store.close()
        val restarted=SqlCollectionsStore(::driver,StandardTestDispatcher(testScheduler))
        assertTrue(value(restarted.library.list()).isEmpty());restarted.close()
    }
    @Test fun closeFailureIsDiagnosedOnceAndRepeatedCloseIsSafe() = runTest {
        val failures=mutableListOf<CollectionsStorageFailure>();var closed=0
        val store=SqlCollectionsStore({ val d=driver();object : SqlDriver by d {
            override fun close() { closed++;d.close();throw SQLException("private close details") }
        } },StandardTestDispatcher(testScheduler),onFailure=failures::add)
        value(store.library.list());assertFailsWith<SQLException>{store.close()};store.close()
        assertEquals(1,closed);assertEquals(CollectionsStorageFailure(Operation.CLOSE,Stage.DRIVER_CLOSE,Reason.DATABASE),failures.single())
    }
    @Test fun futureSchemaFailureAndClosedStoreAreDiagnosableWithoutRecovery() = runTest {
        driver().use { it.execute(null,"PRAGMA user_version=99",0) }
        val failures=mutableListOf<CollectionsStorageFailure>()
        val store=SqlCollectionsStore(::driver,StandardTestDispatcher(testScheduler),onFailure=failures::add)
        assertEquals(LocalStoreResult.Unavailable,store.library.list());assertEquals(Stage.SCHEMA_VERSION,failures.single().stage)
        store.close();assertEquals(LocalStoreResult.Unavailable,store.library.list())
        assertEquals(Stage.CLOSED,failures.last().stage);assertEquals(Reason.CLOSED,failures.last().reason)
        JdbcSqliteDriver("jdbc:sqlite:$file").use { assertEquals(99L,collectionsScalar(it,"PRAGMA user_version")) }
    }
}
