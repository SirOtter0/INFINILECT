// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import org.infinilect.core.*

internal class FakeCollections {
    val saved=linkedMapOf<PublicationId,LibraryEntry>()
    val opened=linkedMapOf<PublicationId,HistoryEntry>()
    val tagValues=linkedMapOf<Long,LibraryTag>()
    private var tagSequence=0L
    var fail=false
    var libraryReads=0
    var historyReads=0
    var beforeRead: suspend () -> Unit = {}
    val failedLibraryRemovals = mutableSetOf<PublicationId>()
    val libraryRemovals = mutableListOf<PublicationId>()
    var beforeWrite: suspend () -> Unit = {}
    private fun <T> result(value: T): LocalStoreResult<T> = if(fail) LocalStoreResult.Unavailable else LocalStoreResult.Success(value)
    val library=object : LibraryRepository {
        override suspend fun get(id: PublicationId)=result(saved[id])
        override suspend fun list(): LocalStoreResult<List<LibraryEntry>> { libraryReads++; beforeRead(); return result(saved.values.toList()) }
        override suspend fun contains(id: PublicationId)=result(id in saved)
        override suspend fun put(publication: PublicationSnapshot, atEpochMillis: Long): LocalStoreResult<LibraryEntry> {
            beforeWrite(); if(fail) return LocalStoreResult.Unavailable
            val entry=LibraryEntry(publication,saved[publication.id]?.addedAtEpochMillis ?: atEpochMillis)
            saved[publication.id]=entry;return LocalStoreResult.Success(entry)
        }
        override suspend fun tags() = result(tagValues.values.toList())
        override suspend fun createTag(name: String): LocalStoreResult<LibraryTag> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable
            if(name.isBlank()) return LocalStoreResult.InvalidInput
            val existing=tagValues.values.firstOrNull {it.name.equals(name.trim(),ignoreCase=true)}
            val tag=existing ?: LibraryTag(++tagSequence,name.trim()).also { tagValues[it.id]=it }
            return LocalStoreResult.Success(tag)
        }
        override suspend fun renameTag(id: Long,name: String): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable
            if(id !in tagValues || name.isBlank()) return LocalStoreResult.InvalidInput
            val tag=LibraryTag(id,name.trim());tagValues[id]=tag
            for((key,entry) in saved.toMap()) saved[key]=entry.copy(tags=entry.tags.map {if(it.id==id) tag else it})
            return LocalStoreResult.Success(Unit)
        }
        override suspend fun deleteTag(id: Long): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable
            tagValues.remove(id);for((key,entry) in saved.toMap()) saved[key]=entry.copy(tags=entry.tags.filter {it.id!=id})
            return LocalStoreResult.Success(Unit)
        }
        override suspend fun organize(ids: Set<PublicationId>,change: LibraryOrganizationChange): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable
            if(ids.any {it !in saved} || (change.addTags+change.removeTags).any {it !in tagValues}) return LocalStoreResult.InvalidInput
            for(id in ids) {
                val entry=saved.getValue(id)
                saved[id]=entry.copy(readingState=change.state ?: entry.readingState,stateIsExplicit=entry.stateIsExplicit || change.state!=null,
                    favorite=change.favorite ?: entry.favorite,tags=(entry.tags.filter {it.id !in change.removeTags} + change.addTags.map {tagValues.getValue(it)}).distinctBy {it.id})
            }
            return LocalStoreResult.Success(Unit)
        }
        override suspend fun remove(id: PublicationId): LocalStoreResult<Unit> {
            beforeWrite(); libraryRemovals += id
            if(fail || id in failedLibraryRemovals) return LocalStoreResult.Unavailable
            saved.remove(id);return LocalStoreResult.Success(Unit)
        }
    }
    val history=object : ReadingHistoryRepository {
        override suspend fun recordOpened(publication: PublicationSnapshot, atEpochMillis: Long): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable
            opened[publication.id]=HistoryEntry(publication,atEpochMillis);return LocalStoreResult.Success(Unit)
        }
        override suspend fun listRecent(limit: Int): LocalStoreResult<List<HistoryEntry>> { historyReads++; beforeRead(); return result(opened.values.sortedByDescending {it.lastOpenedAtEpochMillis}.take(limit)) }
        override suspend fun remove(id: PublicationId): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable;opened.remove(id);return LocalStoreResult.Success(Unit)
        }
        override suspend fun clear(): LocalStoreResult<Unit> {
            beforeWrite();if(fail) return LocalStoreResult.Unavailable;opened.clear();return LocalStoreResult.Success(Unit)
        }
    }
}
