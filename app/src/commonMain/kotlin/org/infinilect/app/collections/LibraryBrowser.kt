// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.core.*

internal expect fun librarySearchText(value: String): String
internal enum class LibrarySort { ADDED, LAST_READ, TITLE, AUTHOR, PROGRESS }
internal data class LibraryQuery(val text: String = "", val state: LibraryReadingState? = null,
    val favorites: Boolean = false, val tags: Set<Long> = emptySet(), val untagged: Boolean = false,
    val sort: LibrarySort = LibrarySort.ADDED, val descending: Boolean = true) {
    val filtered get() = state != null || favorites || tags.isNotEmpty() || untagged
}
internal data class LibrarySearchRow(val entry: LibraryEntry, val title: String, val author: String,
    val search: String, val tagIds: Set<Long>, val progress: Double?, val lastRead: Long?)
internal fun libraryIndex(entries: List<LibraryEntry>, progress: List<ReadingProgress> = emptyList()): List<LibrarySearchRow> {
    val positions = progress.groupBy { it.id.publicationId }.mapValues { (_, rows) -> rows.maxBy { it.updatedAtEpochMillis } }
    return entries.map { entry ->
        val title = librarySearchText(entry.publication.title)
        val author = librarySearchText(entry.publication.authors.joinToString(" "))
        LibrarySearchRow(entry,title,author, "$title\n$author\n${librarySearchText(entry.tags.joinToString(" ") { it.name })}",
            entry.tags.map { it.id }.toSet(),positions[entry.publication.id]?.progression,
            maxOf(entry.lastOpenedAtEpochMillis ?: -1,positions[entry.publication.id]?.updatedAtEpochMillis ?: -1).takeIf {it>=0})
    }
}
internal fun libraryMatches(index: List<LibrarySearchRow>, query: LibraryQuery): List<LibraryEntry> {
    val words = librarySearchText(query.text.take(256)).split(' ').filter { it.isNotEmpty() }
    val matching = index.filter { row ->
        val entry = row.entry
        (query.state == null || entry.readingState == query.state) && (!query.favorites || entry.favorite) &&
            (!query.untagged || row.tagIds.isEmpty()) && row.tagIds.containsAll(query.tags) && words.all { it in row.search }
    }
    val comparator = Comparator<LibrarySearchRow> { a,b ->
        val result = when(query.sort) {
            LibrarySort.ADDED -> a.entry.addedAtEpochMillis.compareTo(b.entry.addedAtEpochMillis)
            // Missing metadata always follows known values, in either direction.
            LibrarySort.LAST_READ -> compareValues(a.lastRead,b.lastRead)
            LibrarySort.TITLE -> a.title.compareTo(b.title)
            LibrarySort.AUTHOR -> a.author.compareTo(b.author)
            LibrarySort.PROGRESS -> compareValues(a.progress,b.progress)
        }
        val missingA = when(query.sort) { LibrarySort.LAST_READ -> a.lastRead == null; LibrarySort.AUTHOR -> a.author.isEmpty(); LibrarySort.PROGRESS -> a.progress == null; else -> false }
        val missingB = when(query.sort) { LibrarySort.LAST_READ -> b.lastRead == null; LibrarySort.AUTHOR -> b.author.isEmpty(); LibrarySort.PROGRESS -> b.progress == null; else -> false }
        if (missingA != missingB) return@Comparator if(missingA) 1 else -1
        if (result != 0) return@Comparator if(query.descending) -result else result
        val pa=a.entry.publication.id; val pb=b.entry.publication.id
        pa.sourceId.value.compareTo(pb.sourceId.value).takeIf { it != 0 } ?: pa.localId.compareTo(pb.localId)
    }
    return matching.sortedWith(comparator).map { it.entry }
}

/** Session-owned query and index survive tab disposal. No database or source work per keystroke.
 * One latest-only worker; canceled computations cannot publish stale results. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LibraryBrowser(private val scope: CoroutineScope, private val entries: StateFlow<CollectionList<LibraryEntry>>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val loadProgress: suspend (List<PublicationId>) -> List<ReadingProgress> = { emptyList() }) {
    private val mutableQuery = MutableStateFlow(LibraryQuery())
    val query = mutableQuery.asStateFlow()
    private val mutableResult = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val result = mutableResult.asStateFlow()
    private val mutableWorking = MutableStateFlow(false)
    val working = mutableWorking.asStateFlow()
    private val progress = MutableStateFlow<List<ReadingProgress>>(emptyList())
    private var active = false
    fun activate() {
        if (active) return
        active = true
        scope.launch {
            entries.map { it.entries.map { row -> row.publication.id to row.lastOpenedAtEpochMillis } }.distinctUntilChanged()
                .collectLatest { keys ->
                    mergeProgress(loadProgress(keys.map { it.first }))
                }
        }
        scope.launch {
            val index = combine(entries.map { it.entries }.distinctUntilChanged(),progress) { rows, records -> rows to records }
                .mapLatest { (rows,records) -> withContext(dispatcher) { libraryIndex(rows,records) } }
            combine(index,query) { rows,query -> rows to query }.collectLatest { (rows,query) ->
                mutableWorking.value = true
                try {
                    val result = withContext(dispatcher) { libraryMatches(rows,query) }
                    currentCoroutineContext().ensureActive()
                    mutableResult.value = result
                } finally { mutableWorking.value = false }
            }
        }
    }
    fun mergeProgress(records: List<ReadingProgress>) {
        if(records.isEmpty()) return
        val ids = entries.value.entries.map { it.publication.id }.toSet()
        progress.value = (progress.value + records).filter { it.id.publicationId in ids }
            .groupBy { it.id.publicationId }.map { (_,values) -> values.maxBy { it.updatedAtEpochMillis } }
            .take(LibraryOrganizationLimits.PUBLICATIONS)
    }
    fun change(value: LibraryQuery) { mutableQuery.value = value.copy(text = value.text.take(256), tags = value.tags.toSet()) }
    fun resetFilters() { change(query.value.copy(state = null, favorites = false, tags = emptySet(), untagged = false)) }
}
