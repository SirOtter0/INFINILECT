// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import kotlinx.coroutines.test.runTest
import org.infinilect.app.discovery.*
import org.infinilect.core.*
import kotlin.test.*

class WikisourceReadableCatalogTest {
    private val edition = WikisourceEdition.ES
    private fun entry(n: Int, auxiliary: Boolean = false): DiscoveryEntry {
        val id = PublicationId(edition.id, n.toString())
        val resources = if (auxiliary) emptyList() else listOf(PublicationResource(id, "epub3-policy1-r77", PublicationFormat.EPUB, "application/epub+zip"))
        return DiscoveryEntry(Publication(id, "Synthetic $n", PublicationType.DOCUMENT, resources = resources))
    }

    @Test fun strictModeResumesPartialPagesAndKeepsCandidatesSeparate() = runTest {
        var calls = 0
        val inspected = mutableListOf<Int>()
        val catalog = WikisourceReadableCatalog(edition, { 0 }, { _, _ ->
            calls++
            DiscoveryPage((1..20).map { entry(it, it == 1) })
        }, { id ->
            val n = id.localId.toInt()
            inspected += n
            CatalogPublicationInfo(CatalogPageKind.TEXT,
                if (n in 2..3) CatalogEpubState.VALIDATED else CatalogEpubState.EXPORTABLE,
                authorized = n != 2)
        }, { CatalogReadability.UNVERIFIED })
        val query = DiscoveryRequest("obra")
        val first = catalog.load(query, null)
        assertEquals(listOf("3"), first.entries.map { it.publication.id.localId })
        assertEquals(6, first.candidates.size)
        assertEquals((2..9).toList(), inspected)
        assertTrue(first.assessmentLimited)
        val second = catalog.load(query, assertNotNull(first.nextToken))
        val third = catalog.load(query, assertNotNull(second.nextToken))
        assertNull(third.nextToken)
        assertEquals((2..20).toList(), inspected)
        val all = catalog.load(query.copy(readabilityMode = ReadabilityMode.ALL), null)
        assertEquals(20, all.entries.size)
        assertEquals(1, calls)
    }

    @Test fun budgetsStopAndResumeWithoutLosingTheUnprocessedRecord() = runTest {
        var limited = true
        val catalog = WikisourceReadableCatalog(edition, { 0 },
            { _, _ -> DiscoveryPage(listOf(entry(1))) },
            {
                if (limited) throw WikiBudgetReached()
                CatalogPublicationInfo(CatalogPageKind.TEXT, CatalogEpubState.VALIDATED, authorized = true)
            }, { CatalogReadability.UNVERIFIED })
        val first = catalog.load(DiscoveryRequest("obra"), null)
        assertTrue(first.entries.isEmpty())
        assertNotNull(first.nextToken)
        assertFailsWith<WikisourceInvalid> { catalog.load(DiscoveryRequest("otra"), first.nextToken) }
        limited = false
        assertEquals(1, catalog.load(DiscoveryRequest("obra"), first.nextToken).entries.size)
        val budget = WikiAssessmentBudget()
        repeat(24) { budget.request() }
        assertFailsWith<WikiBudgetReached> { budget.request() }
        budget.reset()
        budget.consume(8 * 1024 * 1024)
        assertFailsWith<WikiBudgetReached> { budget.request() }
    }

    @Test fun temporaryErrorsAndHttpSuccessDoNotBecomeConfirmed() {
        fun availability(state: CatalogEpubState, authorized: Boolean = true) =
            CatalogPublicationInfo(CatalogPageKind.TEXT, state, authorized = authorized).readability()
        assertEquals(CatalogReadability.TEMPORARY_ERROR, availability(CatalogEpubState.TEMPORARY_ERROR))
        assertEquals(CatalogReadability.READABLE_CANDIDATE, availability(CatalogEpubState.EXPORTABLE))
        assertEquals(CatalogReadability.UNVERIFIED, availability(CatalogEpubState.VALIDATED, false))
        assertEquals(CatalogReadability.UNAVAILABLE, availability(CatalogEpubState.INCOMPATIBLE))
        assertEquals(CatalogReadability.WEB_ONLY, availability(CatalogEpubState.WEB_ONLY))
    }
    @Test fun malformedLaterDetailsPreserveColdCandidatesAndDoNotClaimAnEmptyCatalog() = runTest {
        val catalog = WikisourceReadableCatalog(edition, { 0 },
            { _, _ -> DiscoveryPage(listOf(entry(1), entry(2), entry(3, auxiliary = true))) },
            { id ->
                if (id.localId == "2") throw WikisourceInvalid()
                CatalogPublicationInfo(CatalogPageKind.TEXT, CatalogEpubState.POTENTIAL, authorized = true)
            }, { CatalogReadability.UNVERIFIED })
        val page = catalog.load(DiscoveryRequest("A"), null)
        assertTrue(page.hadCatalogMatches)
        assertTrue(page.assessmentFailed)
        assertFalse(page.assessmentLimited)
        assertTrue(page.entries.isEmpty())
        assertEquals(listOf("1"), page.candidates.map { it.publication.id.localId })
    }

}
