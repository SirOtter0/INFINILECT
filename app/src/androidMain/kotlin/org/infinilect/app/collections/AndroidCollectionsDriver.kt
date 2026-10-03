// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import org.infinilect.app.collections.database.LocalCollectionsDatabase

internal fun androidCollectionsFile(privateDatabasesDirectory: File): File {
    require(privateDatabasesDirectory.isAbsolute && privateDatabasesDirectory.name == "databases")
    return privateDatabasesDirectory.resolve(COLLECTIONS_DATABASE_NAME)
}

/** Called only by the IO store, never during Activity creation or Compose rendering. */
internal fun androidCollectionsDriver(applicationContext: Context): SqlDriver {
    val context = applicationContext.applicationContext
    val file = context.getDatabasePath(COLLECTIONS_DATABASE_NAME)
    require(file == androidCollectionsFile(file.parentFile!!))
    require(file.isAbsolute && (!file.exists() || file.length() <= MAX_COLLECTIONS_DATABASE_BYTES))
    return AndroidSqliteDriver(LocalCollectionsDatabase.Schema, context, COLLECTIONS_DATABASE_NAME,
        callback = object : AndroidSqliteDriver.Callback(LocalCollectionsDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
                db.execSQL("PRAGMA synchronous=FULL")
                db.execSQL("PRAGMA busy_timeout=3000")
            }
            override fun onCorruption(db: SupportSQLiteDatabase) {
                // Android's default handler deletes corrupt databases. Preserve user metadata.
                throw SQLiteException("Local metadata storage is unavailable")
            }
        })
}
