// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

/** Local display metadata, never authority to acquire bytes. No resources or reading position. */
data class PublicationSnapshot(
    val id: PublicationId,
    val title: String,
    val type: PublicationType,
    val authors: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val rights: String? = null,
) {
    init { validate() }
    fun validate() {
        fun bounded(value: String, max: Int) { require(value.isNotBlank() && value.length <= max && '\u0000' !in value) }
        bounded(id.sourceId.value, 128); bounded(id.localId, 1024); bounded(title, 2048)
        require(authors.size <= 64 && languages.size <= 32)
        authors.forEach { bounded(it, 512) }; languages.forEach { bounded(it, 128) }
        sourceUrl?.let { bounded(it, 2048) }; rights?.let { bounded(it, 8192) }
    }
    companion object {
        fun from(publication: Publication) = PublicationSnapshot(publication.id, publication.title, publication.type,
            publication.authors.toList(), publication.languages.toList(), publication.sourceUrl, publication.rights)
    }
}

data class LibraryEntry(val publication: PublicationSnapshot, val addedAtEpochMillis: Long, val lastOpenedAtEpochMillis: Long? = null) {
    init { require(addedAtEpochMillis >= 0 && (lastOpenedAtEpochMillis == null || lastOpenedAtEpochMillis >= 0)) }
}
data class HistoryEntry(val publication: PublicationSnapshot, val lastOpenedAtEpochMillis: Long) {
    init { require(lastOpenedAtEpochMillis >= 0) }
}

/** Success denotes committed storage, never optimistic RAM. Errors contain no private details. */
sealed interface LocalStoreResult<out T> {
    data class Success<T>(val value: T) : LocalStoreResult<T>
    data object Unavailable : LocalStoreResult<Nothing>
    data object InvalidInput : LocalStoreResult<Nothing>
    data object CapacityReached : LocalStoreResult<Nothing>
}
interface LibraryRepository {
    suspend fun get(id: PublicationId): LocalStoreResult<LibraryEntry?>
    suspend fun list(): LocalStoreResult<List<LibraryEntry>>
    suspend fun put(publication: PublicationSnapshot, atEpochMillis: Long): LocalStoreResult<LibraryEntry>
    suspend fun remove(id: PublicationId): LocalStoreResult<Unit>
    suspend fun contains(id: PublicationId): LocalStoreResult<Boolean>
}
interface ReadingHistoryRepository {
    suspend fun recordOpened(publication: PublicationSnapshot, atEpochMillis: Long): LocalStoreResult<Unit>
    suspend fun listRecent(limit: Int = 50): LocalStoreResult<List<HistoryEntry>>
    suspend fun remove(id: PublicationId): LocalStoreResult<Unit>
    suspend fun clear(): LocalStoreResult<Unit>
}
