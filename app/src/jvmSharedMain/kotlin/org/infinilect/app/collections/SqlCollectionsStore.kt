// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import org.infinilect.app.collections.database.*
import org.infinilect.core.*

internal const val COLLECTIONS_DATABASE_NAME = "infinilect-collections.sqlite"
internal const val MAX_COLLECTIONS_DATABASE_BYTES = 64L * 1024 * 1024

/** Settings apply to every short JDBC connection, not only the initialization thread. */
internal fun collectionsJdbcProperties() = java.util.Properties().apply {
    setProperty("foreign_keys","true"); setProperty("busy_timeout","3000"); setProperty("synchronous","FULL")
}

/** Initial schema/migrations/version commit atomically; unknown future schemas fail closed. */
internal fun initializeCollectionsSchema(driver: SqlDriver) {
    LocalCollectionsDatabase(driver).transaction {
        val version = driver.executeQuery(null,"PRAGMA user_version", { cursor ->
            check(cursor.next().value); QueryResult.Value(cursor.getLong(0)!!)
        },0).value
        val schema = LocalCollectionsDatabase.Schema
        check(version in 0..schema.version)
        if(version==0L) schema.create(driver).value
        else if(version<schema.version) schema.migrate(driver,version,schema.version).value
        driver.execute(null,"PRAGMA user_version=${schema.version}",0).value
    }
}

/** Lazy opening, migrations and all queries run on IO. No optimistic metadata cache.
 * Short synchronized transactions serialize this owner's readers/writers/close.
 */
internal class SqlCollectionsStore(
    private val createDriver: () -> SqlDriver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val libraryLimit: Int = 1000,
    private val historyLimit: Int = 500,
    private val beforeCommit: () -> Unit = {},
) {
    private val monitor = Any()
    private val closed = AtomicBoolean()
    private var driver: SqlDriver? = null
    private fun database(): LocalCollectionsDatabase {
        check(!closed.get())
        val current = driver ?: createDriver().also { driver = it }
        return LocalCollectionsDatabase(current)
    }
    private suspend fun <T> operation(block: (LocalCollectionsDatabase, kotlin.coroutines.CoroutineContext) -> T): LocalStoreResult<T> =
        withContext(dispatcher) {
            val context = currentCoroutineContext(); context.ensureActive()
            try { synchronized(monitor) {
                context.ensureActive()
                val db = database()
                val result = db.transactionWithResult {
                    // JDBC uses short per-thread connections. Apply this limit to the
                    // exact transaction connection, not a previous PRAGMA-only connection.
                    val current = driver!!
                    val pageSize = current.executeQuery(null, "PRAGMA page_size", { cursor ->
                        check(cursor.next().value); QueryResult.Value(cursor.getLong(0)!!)
                    }, 0).value
                    check(pageSize in 512..65536)
                    current.execute(null, "PRAGMA max_page_count=${MAX_COLLECTIONS_DATABASE_BYTES / pageSize}", 0).value
                    val value = block(db,context); context.ensureActive(); value
                }
                LocalStoreResult.Success(result)
            } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { LocalStoreResult.Unavailable }
        }
    private fun failure(result: LocalStoreResult<*>): LocalStoreResult<Nothing> = when (result) {
        LocalStoreResult.Unavailable -> LocalStoreResult.Unavailable
        LocalStoreResult.InvalidInput -> LocalStoreResult.InvalidInput
        LocalStoreResult.CapacityReached -> LocalStoreResult.CapacityReached
        is LocalStoreResult.Success -> error("Expected failure")
    }
    private fun valid(id: PublicationId): Boolean = id.sourceId.value.length in 1..128 && id.localId.length in 1..1024 &&
        '\u0000' !in id.sourceId.value && '\u0000' !in id.localId
    private fun snapshot(publication: PublicationSnapshot, time: Long): PublicationSnapshot? = try {
        require(time >= 0); publication.validate()
        publication.copy(authors = publication.authors.toList(), languages = publication.languages.toList())
    } catch (_: IllegalArgumentException) { null }
    private fun LocalCollectionsDatabase.libraryEntry(row: Library_entries): LibraryEntry {
        val q = collectionsQueries
        return LibraryEntry(PublicationSnapshot(PublicationId(SourceId(row.source_id), row.local_id), row.title,
            PublicationType.valueOf(row.semantic_type), q.libraryAuthors(row.source_id,row.local_id).executeAsList(),
            q.libraryLanguages(row.source_id,row.local_id).executeAsList(), row.source_url, row.rights), row.added_at, row.last_opened_at)
    }
    private fun LocalCollectionsDatabase.historyEntry(row: Reading_history): HistoryEntry {
        val q = collectionsQueries
        return HistoryEntry(PublicationSnapshot(PublicationId(SourceId(row.source_id),row.local_id),row.title,
            PublicationType.valueOf(row.semantic_type),q.historyAuthors(row.source_id,row.local_id).executeAsList(),
            q.historyLanguages(row.source_id,row.local_id).executeAsList(),row.source_url,row.rights),row.last_opened_at)
    }
    val library: LibraryRepository = object : LibraryRepository {
        override suspend fun get(id: PublicationId): LocalStoreResult<LibraryEntry?> {
            if (!valid(id)) return LocalStoreResult.InvalidInput
            return operation { db, _ -> db.collectionsQueries.libraryGet(id.sourceId.value,id.localId).executeAsOneOrNull()?.let { db.libraryEntry(it) } }
        }
        override suspend fun contains(id: PublicationId): LocalStoreResult<Boolean> = when (val result = get(id)) {
            is LocalStoreResult.Success -> LocalStoreResult.Success(result.value != null)
            else -> failure(result)
        }
        override suspend fun list(): LocalStoreResult<List<LibraryEntry>> = operation { db, _ ->
            db.collectionsQueries.libraryList(libraryLimit.toLong()).executeAsList().map { db.libraryEntry(it) }
        }
        override suspend fun put(publication: PublicationSnapshot, atEpochMillis: Long): LocalStoreResult<LibraryEntry> {
            val p = snapshot(publication,atEpochMillis) ?: return LocalStoreResult.InvalidInput
            val result = operation { db, context ->
                db.transactionWithResult {
                    val q = db.collectionsQueries
                    val previous = q.libraryGet(p.id.sourceId.value,p.id.localId).executeAsOneOrNull()
                    if (previous == null && q.libraryCount().executeAsOne() >= libraryLimit) return@transactionWithResult null
                    q.libraryPut(p.id.sourceId.value,p.id.localId,p.title,p.type.name,p.sourceUrl,p.rights,
                        previous?.added_at ?: atEpochMillis,previous?.last_opened_at)
                    p.authors.forEachIndexed { index, value -> q.libraryAuthorsPut(p.id.sourceId.value,p.id.localId,index.toLong(),value) }
                    p.languages.forEachIndexed { index, value -> q.libraryLanguagesPut(p.id.sourceId.value,p.id.localId,index.toLong(),value) }
                    beforeCommit(); context.ensureActive()
                    db.libraryEntry(q.libraryGet(p.id.sourceId.value,p.id.localId).executeAsOne())
                }
            }
            return when (result) {
                is LocalStoreResult.Success -> result.value?.let { LocalStoreResult.Success(it) } ?: LocalStoreResult.CapacityReached
                else -> failure(result)
            }
        }
        override suspend fun remove(id: PublicationId): LocalStoreResult<Unit> {
            if (!valid(id)) return LocalStoreResult.InvalidInput
            return operation { db, context -> db.transaction { db.collectionsQueries.libraryRemove(id.sourceId.value,id.localId); beforeCommit(); context.ensureActive() } }
        }
    }
    val history: ReadingHistoryRepository = object : ReadingHistoryRepository {
        override suspend fun recordOpened(publication: PublicationSnapshot, atEpochMillis: Long): LocalStoreResult<Unit> {
            val p = snapshot(publication,atEpochMillis) ?: return LocalStoreResult.InvalidInput
            return operation { db, context -> db.transaction {
                val q = db.collectionsQueries
                val previous = q.historyGet(p.id.sourceId.value,p.id.localId).executeAsOneOrNull()
                if (previous == null || previous.last_opened_at <= atEpochMillis) {
                    q.historyPut(p.id.sourceId.value,p.id.localId,p.title,p.type.name,p.sourceUrl,p.rights,atEpochMillis)
                    p.authors.forEachIndexed { index, value -> q.historyAuthorsPut(p.id.sourceId.value,p.id.localId,index.toLong(),value) }
                    p.languages.forEachIndexed { index, value -> q.historyLanguagesPut(p.id.sourceId.value,p.id.localId,index.toLong(),value) }
                    q.historyPrune(historyLimit.toLong())
                    val saved = q.libraryGet(p.id.sourceId.value,p.id.localId).executeAsOneOrNull()
                    if (saved != null && (saved.last_opened_at ?: -1) < atEpochMillis)
                        q.libraryOpened(atEpochMillis,p.id.sourceId.value,p.id.localId)
                }
                beforeCommit(); context.ensureActive()
            } }
        }
        override suspend fun listRecent(limit: Int): LocalStoreResult<List<HistoryEntry>> {
            if (limit !in 1..100) return LocalStoreResult.InvalidInput
            return operation { db, _ -> db.collectionsQueries.historyList(limit.toLong()).executeAsList().map { db.historyEntry(it) } }
        }
        override suspend fun remove(id: PublicationId): LocalStoreResult<Unit> {
            if (!valid(id)) return LocalStoreResult.InvalidInput
            return operation { db, context -> db.transaction { db.collectionsQueries.historyRemove(id.sourceId.value,id.localId); beforeCommit(); context.ensureActive() } }
        }
        override suspend fun clear(): LocalStoreResult<Unit> = operation { db, context -> db.transaction { db.collectionsQueries.historyClear(); beforeCommit(); context.ensureActive() } }
    }
    suspend fun close() = withContext(dispatcher) { synchronized(monitor) {
        if (closed.compareAndSet(false,true)) { driver?.close(); driver = null }
    } }
    init { require(libraryLimit in 1..1000 && historyLimit in 1..500) }
}
