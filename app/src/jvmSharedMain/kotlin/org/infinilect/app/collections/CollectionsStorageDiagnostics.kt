// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import java.io.IOException
import java.sql.SQLException
import kotlinx.coroutines.CancellationException

/** Only fixed enums cross the diagnostic boundary; never a Throwable or user data. */
internal data class CollectionsStorageFailure(
    val operation: Operation,
    val stage: Stage,
    val reason: Reason,
) {
    enum class Operation { LIBRARY_GET, LIBRARY_LIST, LIBRARY_PUT, LIBRARY_REMOVE,
        HISTORY_RECORD, HISTORY_LIST, HISTORY_REMOVE, HISTORY_CLEAR, CLOSE }
    enum class Stage { STORAGE_PATH, DRIVER_CREATE, DATABASE_OPEN, CONFIGURE_FOREIGN_KEYS,
        CONFIGURE_DURABILITY, CONFIGURE_BUSY_TIMEOUT, SCHEMA_VERSION, SCHEMA_CREATE,
        SCHEMA_MIGRATE, TRANSACTION_BEGIN, PAGE_SIZE, PAGE_LIMIT, QUERY_OR_MUTATION,
        PRE_COMMIT, COMMIT, DRIVER_CLOSE, CLOSED }
    enum class Reason { DATABASE, CORRUPTION, IO, SECURITY, INVALID_INPUT, INVALID_STATE,
        UNSUPPORTED, CANCELLED, CLOSED, OTHER }
}

internal class CollectionsStorageException(
    val stage: CollectionsStorageFailure.Stage,
    val reason: CollectionsStorageFailure.Reason,
    cause: Exception? = null,
) : RuntimeException(null, cause)

internal fun collectionsFailureReason(error: Exception): CollectionsStorageFailure.Reason = when (error) {
    is CancellationException -> CollectionsStorageFailure.Reason.CANCELLED
    is SQLException -> CollectionsStorageFailure.Reason.DATABASE
    is IOException -> CollectionsStorageFailure.Reason.IO
    is SecurityException -> CollectionsStorageFailure.Reason.SECURITY
    is UnsupportedOperationException -> CollectionsStorageFailure.Reason.UNSUPPORTED
    is IllegalArgumentException -> CollectionsStorageFailure.Reason.INVALID_INPUT
    is IllegalStateException -> CollectionsStorageFailure.Reason.INVALID_STATE
    else -> {
        // Classify platform exceptions without logging their names/messages or importing Android.
        val types = generateSequence<Class<*>>(error.javaClass) { it.superclass }.map { it.name }.toSet()
        when {
            "android.database.sqlite.SQLiteDatabaseCorruptException" in types -> CollectionsStorageFailure.Reason.CORRUPTION
            "android.database.sqlite.SQLiteException" in types -> CollectionsStorageFailure.Reason.DATABASE
            else -> CollectionsStorageFailure.Reason.OTHER
        }
    }
}

internal inline fun <T> collectionsStage(stage: CollectionsStorageFailure.Stage, block: () -> T): T = try {
    block()
} catch (error: CancellationException) { throw error }
catch (error: CollectionsStorageException) { throw error }
catch (error: Exception) { throw CollectionsStorageException(stage, collectionsFailureReason(error), error) }
