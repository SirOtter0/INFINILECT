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

data class LibraryEntry(val publication: PublicationSnapshot, val addedAtEpochMillis: Long, val lastOpenedAtEpochMillis: Long? = null,
    val readingState: LibraryReadingState = LibraryReadingState.NOT_STARTED,
    val stateIsExplicit: Boolean = false, val favorite: Boolean = false, val tags: List<LibraryTag> = emptyList()) {
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
    /** All IDs are validated before a single atomic edit; failures leave the batch unchanged. */
    suspend fun organize(ids: Set<PublicationId>, change: LibraryOrganizationChange): LocalStoreResult<Unit> = LocalStoreResult.Unavailable
    /** Existing validated locator evidence; changes only an implicit not-started state. */
    suspend fun recordProgressEvidence(ids: Set<PublicationId>): LocalStoreResult<Int> = LocalStoreResult.Success(0)
    suspend fun tags(): LocalStoreResult<List<LibraryTag>> = LocalStoreResult.Unavailable
    suspend fun createTag(name: String): LocalStoreResult<LibraryTag> = LocalStoreResult.Unavailable
    suspend fun renameTag(id: Long, name: String): LocalStoreResult<Unit> = LocalStoreResult.Unavailable
    suspend fun deleteTag(id: Long): LocalStoreResult<Unit> = LocalStoreResult.Unavailable
}
interface ReadingHistoryRepository {
    suspend fun recordOpened(publication: PublicationSnapshot, atEpochMillis: Long): LocalStoreResult<Unit>
    suspend fun listRecent(limit: Int = 50): LocalStoreResult<List<HistoryEntry>>
    suspend fun remove(id: PublicationId): LocalStoreResult<Unit>
    suspend fun clear(): LocalStoreResult<Unit>
}

/** Persistent identifiers are independent of the interface language. */
enum class LibraryReadingState(val key: String) {
    NOT_STARTED("not_started"), READING("reading"), COMPLETED("completed"), PAUSED("paused"), ABANDONED("abandoned");
    companion object { fun fromKey(key: String) = entries.single { it.key == key } }
}
data class LibraryTag(val id: Long, val name: String)
/** Nullable fields are unchanged. Add/remove membership deltas avoid stale-dialog overwrites. */
data class LibraryOrganizationChange(val state: LibraryReadingState? = null, val favorite: Boolean? = null,
    val addTags: Set<Long> = emptySet(), val removeTags: Set<Long> = emptySet())
object LibraryOrganizationLimits {
    const val PUBLICATIONS = 5000
    const val TAGS = 100
    const val TAG_NAME = 64
    const val TAGS_PER_PUBLICATION = 20
}
