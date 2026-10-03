// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import org.infinilect.core.*

internal class FakeCollections {
    val saved=linkedMapOf<PublicationId,LibraryEntry>()
    val opened=linkedMapOf<PublicationId,HistoryEntry>()
    var fail=false
    var beforeWrite: suspend () -> Unit = {}
    private fun <T> result(value: T): LocalStoreResult<T> = if(fail) LocalStoreResult.Unavailable else LocalStoreResult.Success(value)
    val library=object : LibraryRepository {
        override suspend fun get(id: PublicationId)=result(saved[id])
        override suspend fun list()=result(saved.values.toList())
        override suspend fun contains(id: PublicationId)=result(id in saved)
        override suspend fun put(publication: PublicationSnapshot, atEpochMillis: Long): LocalStoreResult<LibraryEntry> {
            beforeWrite(); if(fail) return LocalStoreResult.Unavailable
            val entry=LibraryEntry(publication,saved[publication.id]?.addedAtEpochMillis ?: atEpochMillis)
            saved[publication.id]=entry;return LocalStoreResult.Success(entry)
        }
        override suspend fun remove(id: PublicationId): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable;saved.remove(id);return LocalStoreResult.Success(Unit)
        }
    }
    val history=object : ReadingHistoryRepository {
        override suspend fun recordOpened(publication: PublicationSnapshot, atEpochMillis: Long): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable
            opened[publication.id]=HistoryEntry(publication,atEpochMillis);return LocalStoreResult.Success(Unit)
        }
        override suspend fun listRecent(limit: Int)=result(opened.values.sortedByDescending {it.lastOpenedAtEpochMillis}.take(limit))
        override suspend fun remove(id: PublicationId): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable;opened.remove(id);return LocalStoreResult.Success(Unit)
        }
        override suspend fun clear(): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable;opened.clear();return LocalStoreResult.Success(Unit)
        }
    }
}
