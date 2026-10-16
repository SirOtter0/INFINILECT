// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.test.*
import org.infinilect.app.collections.*
import org.infinilect.core.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NavigationResponsivenessTest {
    @Test fun repeatedTabVisitsDoNotReloadUnchangedCollections() = runTest {
        val data = FakeCollections()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = ApplicationCollections(data.library, data.history, dispatcher)
        val source = object : PublicationSource {
            override val id = SourceId("original-navigation-fixture")
            override suspend fun search(query: String, pageToken: String?) = SearchPage(emptyList())
            override suspend fun getPublication(publicationId: PublicationId): Publication? = null
            override suspend fun loadResource(resource: PublicationResource): ResourceContent = error("No acquisition")
        }
        val sources = ApplicationSources(listOf(SourceOption("Fixture", source)), collections = owner, sessionDispatcher = dispatcher) {}
        val session = sources.applicationSession()
        try {
            advanceUntilIdle()
            val library = session.collections.library.value.entries
            val history = session.collections.history.value.entries
            repeat(20) { for (destination in listOf(Destination.LIBRARY, Destination.HISTORY, Destination.SEARCH, Destination.SETTINGS, Destination.HOME)) {
                session.navigate(destination); advanceUntilIdle()
            } }
            System.getenv("INFINILECT_PERFORMANCE_DIRECTORY")?.let { directory ->
                val path = Path.of(directory); Files.createDirectories(path)
                Files.writeString(path.resolve("navigation.txt"), "100 tab changes; library reads=${data.libraryReads}; history reads=${data.historyReads}\n")
            }
            assertEquals(1, data.libraryReads)
            assertEquals(1, data.historyReads)
            assertEquals(library, session.collections.library.value.entries)
            assertEquals(history, session.collections.history.value.entries)
            session.navigate(Destination.HOME); advanceUntilIdle()
            assertEquals(1, data.libraryReads, "Re-selecting the active tab is a no-op")
            assertEquals(1, data.historyReads)
            val book = PublicationSnapshot(PublicationId(source.id, "new"), "New original book", PublicationType.BOOK)
            data.saved[book.id] = LibraryEntry(book, 1)
            owner.changed(); advanceUntilIdle()
            assertEquals(listOf(book), session.collections.library.value.entries.map { it.publication })
            assertEquals(2, data.libraryReads, "Committed changes must still invalidate snapshots")
            assertEquals(2, data.historyReads)
        } finally { sources.close(); advanceUntilIdle(); owner.awaitClosed() }
    }
}
