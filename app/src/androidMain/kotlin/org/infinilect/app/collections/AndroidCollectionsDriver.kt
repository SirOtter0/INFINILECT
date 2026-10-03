// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import org.infinilect.app.collections.database.LocalCollectionsDatabase
import org.infinilect.app.collections.CollectionsStorageFailure.Stage

internal fun androidCollectionsFile(privateDatabasesDirectory: File): File {
    require(privateDatabasesDirectory.isAbsolute && privateDatabasesDirectory.name == "databases")
    return privateDatabasesDirectory.resolve(COLLECTIONS_DATABASE_NAME)
}

/** Called only by the IO store, never during Activity creation or Compose rendering. */
internal fun androidCollectionsDriver(applicationContext: Context): SqlDriver {
    val context = applicationContext.applicationContext
    collectionsStage(Stage.STORAGE_PATH) {
        val file = context.getDatabasePath(COLLECTIONS_DATABASE_NAME)
        require(file == androidCollectionsFile(file.parentFile!!))
        require(file.isAbsolute && (!file.exists() || file.length() <= MAX_COLLECTIONS_DATABASE_BYTES))
    }
    return collectionsStage(Stage.DRIVER_CREATE) {
        AndroidSqliteDriver(LocalCollectionsDatabase.Schema, context, COLLECTIONS_DATABASE_NAME,
            callback = androidCollectionsCallback())
    }
}

internal fun androidCollectionsCallback() = object : AndroidSqliteDriver.Callback(LocalCollectionsDatabase.Schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        collectionsStage(Stage.CONFIGURE_FOREIGN_KEYS) { db.setForeignKeyConstraintsEnabled(true) }
        collectionsStage(Stage.CONFIGURE_DURABILITY) { db.execSQL("PRAGMA synchronous=FULL") }
        collectionsStage(Stage.CONFIGURE_BUSY_TIMEOUT) {
            // Unlike synchronous assignment, busy_timeout assignment returns a row.
            // Android execSQL rejects SQLITE_ROW; consume/validate it as a query.
            db.query("PRAGMA busy_timeout=3000").use { cursor ->
                check(cursor.moveToFirst() && cursor.getLong(0) == 3000L)
                check(!cursor.moveToNext())
            }
        }
    }
    override fun onCreate(db: SupportSQLiteDatabase) {
        collectionsStage(Stage.SCHEMA_CREATE) { super.onCreate(db) }
    }
    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        collectionsStage(Stage.SCHEMA_MIGRATE) { super.onUpgrade(db, oldVersion, newVersion) }
    }
    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        collectionsStage(Stage.SCHEMA_MIGRATE) { super.onDowngrade(db, oldVersion, newVersion) }
    }
    override fun onCorruption(db: SupportSQLiteDatabase) {
        // Android's default handler deletes corrupt databases. Preserve user metadata.
        throw CollectionsStorageException(Stage.DATABASE_OPEN, CollectionsStorageFailure.Reason.CORRUPTION)
    }
}

internal fun androidCollectionsDiagnostics(
    debuggable: Boolean,
    log: (String, String) -> Unit = { tag, message -> Log.w(tag, message) },
): (CollectionsStorageFailure) -> Unit = { failure ->
    if (debuggable) log("INFINILECTCollections", "${failure.operation}/${failure.stage}/${failure.reason}")
}
