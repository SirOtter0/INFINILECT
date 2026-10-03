// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*
import org.infinilect.app.collections.CollectionsStorageFailure.Operation
import org.infinilect.app.collections.CollectionsStorageFailure.Stage
import org.infinilect.app.collections.CollectionsStorageFailure.Reason

/** Calls the production Android callback against a bounded JDBC-backed API seam.
 * It reproduces AOSP's non-query/result distinction, not the Android OS/provider.
 */
class AndroidCollectionsConfigurationTest {
    private lateinit var root: java.nio.file.Path
    private lateinit var connection: Connection
    private val commands=mutableListOf<String>()
    private var openedCursors=0
    private var closedCursors=0
    private var wrongTimeout=false
    @BeforeTest fun setup() {
        root=Files.createTempDirectory("infinilect-android-sql-contract")
        connection=DriverManager.getConnection("jdbc:sqlite:${root.resolve("fixture.sqlite")}")
    }
    @AfterTest fun cleanup() { connection.close();root.toFile().deleteRecursively() }
    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, call: (String,Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _,method,args -> call(method.name,args ?: emptyArray()) } as T
    private fun nonQuery(sql: String) {
        commands+=sql
        connection.createStatement().use { statement ->
            if(statement.execute(sql)) throw IllegalStateException("Host simulation: Android execSQL rejects SQLITE_ROW")
        }
    }
    private fun query(sql: String): Cursor {
        val statement=connection.createStatement();val rows=statement.executeQuery(sql);openedCursors++
        var closed=false
        return proxy(Cursor::class.java) { method,args -> when(method) {
            "moveToFirst", "moveToNext" -> rows.next()
            "getLong" -> if(wrongTimeout) 3001L else rows.getLong((args[0] as Int)+1)
            "close" -> { if(!closed) { closed=true;closedCursors++;rows.close();statement.close() };Unit }
            else -> error("Unexpected test cursor call")
        } }
    }
    private fun database(): SupportSQLiteDatabase = proxy(SupportSQLiteDatabase::class.java) { method,args -> when(method) {
        "setForeignKeyConstraintsEnabled" -> { nonQuery("PRAGMA foreign_keys=${if(args[0] == true) "ON" else "OFF"}");Unit }
        "execSQL" -> { nonQuery(args[0] as String);Unit }
        "query" -> query(args[0] as String)
        else -> error("Unexpected test database call")
    } }
    private fun scalar(sql: String): Long = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next());rows.getLong(1) }
    }
    @Test fun oldBusyTimeoutExecSqlFailsOnResultReturningAndroidApiContract() {
        assertFailsWith<IllegalStateException> { database().execSQL("PRAGMA busy_timeout=3000") }
    }
    @Test fun productionConfigureUsesQueryAndPreservesForeignKeysDurabilityAndTimeout() {
        androidCollectionsCallback().onConfigure(database())
        assertEquals(1L,scalar("PRAGMA foreign_keys"));assertEquals(2L,scalar("PRAGMA synchronous"))
        assertEquals(3000L,scalar("PRAGMA busy_timeout"));assertEquals(1,openedCursors);assertEquals(1,closedCursors)
        assertFalse(commands.any { it.startsWith("PRAGMA busy_timeout") })
    }
    @Test fun invalidTimeoutResultFailsClosedAtCorrectStageAndClosesCursor() {
        wrongTimeout=true
        val failure=assertFailsWith<CollectionsStorageException> { androidCollectionsCallback().onConfigure(database()) }
        assertEquals(Stage.CONFIGURE_BUSY_TIMEOUT,failure.stage);assertEquals(Reason.INVALID_STATE,failure.reason)
        assertEquals(openedCursors,closedCursors)
    }
    @Test fun corruptionCallbackPreservesDatabaseInsteadOfDeletingOrRecreatingIt() {
        nonQuery("CREATE TABLE sentinel(value INTEGER)");nonQuery("INSERT INTO sentinel VALUES(42)")
        val failure=assertFailsWith<CollectionsStorageException> { androidCollectionsCallback().onCorruption(database()) }
        assertEquals(Stage.DATABASE_OPEN,failure.stage);assertEquals(Reason.CORRUPTION,failure.reason)
        assertEquals(42L,scalar("SELECT value FROM sentinel"))
    }
    @Test fun debugDiagnosticsEmitOnlyFixedEnumsAndReleaseEmitsNothing() {
        val logs=mutableListOf<Pair<String,String>>()
        val failure=CollectionsStorageFailure(Operation.LIBRARY_PUT,Stage.PAGE_LIMIT,Reason.DATABASE)
        androidCollectionsDiagnostics(false) { tag,message -> logs+=tag to message }(failure)
        assertTrue(logs.isEmpty())
        androidCollectionsDiagnostics(true) { tag,message -> logs+=tag to message }(failure)
        assertEquals(listOf("INFINILECTCollections" to "LIBRARY_PUT/PAGE_LIMIT/DATABASE"),logs)
    }
}
