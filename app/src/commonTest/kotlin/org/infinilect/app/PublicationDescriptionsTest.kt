// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.ui.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PublicationDescriptionsTest {
    private fun id(i: Int) = PublicationId(SourceId("original-local-metadata"), "$i")
    @Test fun repeatedDetailsUseLruIncludingAbsentMetadataAndKeepEightResults() = runTest {
        var calls = 0
        val cache = PublicationDescriptions({ calls++; if (it == id(0)) null else "Original synopsis" }, StandardTestDispatcher(testScheduler))
        try {
            assertNull(cache.get(id(0))); assertNull(cache.get(id(0))); assertEquals(1, calls)
            repeat(8) { assertEquals("Original synopsis", cache.get(id(it + 1))) }
            assertEquals(8, cache.retainedEntries()); assertNull(cache.get(id(0))); assertEquals(10, calls)
        } finally { cache.close(); cache.awaitClosed() }
        assertEquals(0, cache.retainedEntries())
    }
    @Test fun leavingDetailsCancelsLoadAndNoLateValueCanReplaceAnotherIdentity() = runTest {
        val gate = CompletableDeferred<Unit>(); var active = 0; var max = 0; var calls = 0
        val cache = PublicationDescriptions({ key -> active++; max=maxOf(max,active);calls++
            try { if (key == id(0)) gate.await(); "Synopsis ${key.localId}" } finally { active-- }
        }, StandardTestDispatcher(testScheduler))
        val first = launch { cache.get(id(0)) }; runCurrent()
        val second = async { cache.get(id(1)) }; runCurrent(); assertEquals(1, active)
        first.cancelAndJoin(); assertEquals("Synopsis 1", second.await()); gate.complete(Unit)
        assertEquals(1, max); assertEquals(1, cache.retainedEntries()); assertEquals(0, active)
        assertEquals("Synopsis 0", cache.get(id(0))); assertEquals(3, calls)
        cache.close(); cache.awaitClosed()
    }
    @Test fun failedLoadRetriesWithoutCachingFailureOrOverwritingGoodDescription() = runTest {
        var fail = true; var calls = 0
        val cache = PublicationDescriptions({ calls++; if (fail) error("controlled failure") else "Good synopsis" }, StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalStateException> { cache.get(id(0)) }; assertEquals(0, cache.retainedEntries())
        fail=false; assertEquals("Good synopsis", cache.get(id(0))); fail=true
        assertEquals("Good synopsis", cache.get(id(0))); assertEquals(2, calls)
        cache.close(); cache.awaitClosed()
    }
    @Test fun finalCloseCancelsWorkClearsCacheAndPreventsFurtherLoads() = runTest {
        val gate = CompletableDeferred<Unit>();var retired=false
        val cache = PublicationDescriptions({ try {gate.await();"late"} finally {retired=true} }, StandardTestDispatcher(testScheduler))
        val pending=async {cache.get(id(0))};runCurrent();cache.close();cache.awaitClosed()
        assertTrue(retired);assertTrue(pending.isCancelled);assertEquals(0,cache.retainedEntries())
        assertFailsWith<CancellationException>{cache.get(id(1))}
    }
    @Test fun metadataTimeoutAndOversizeAreBoundedAndNotCached()=runTest {
        val cache=PublicationDescriptions({awaitCancellation()},StandardTestDispatcher(testScheduler))
        assertFailsWith<TimeoutCancellationException>{cache.get(id(0))};assertEquals(0,cache.retainedEntries());cache.close();cache.awaitClosed()
        val oversized=PublicationDescriptions({"a".repeat(DescriptionPolicy.CHARACTERS+1)},StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalArgumentException>{oversized.get(id(0))};assertEquals(0,oversized.retainedEntries());oversized.close();oversized.awaitClosed()
    }
    @Test fun excerptPreservesParagraphsAndUnicodeAndReadActionUsesActualRecord() {
        assertEquals("First\n\nSecond",descriptionExcerpt("First\n\nSecond"))
        val text="a".repeat(599)+"📖"+"b".repeat(300)
        assertFalse(descriptionExcerpt(text).dropLast(1).last().isHighSurrogate())
        assertEquals("Start reading",readingAction(null))
        val record=ReadingProgress(ReadingProgressId(id(0),"text",PublicationFormat.TEXT),ReadingLocator.Text(0,10),0.0,1)
        assertEquals("Continue reading",readingAction(record))
    }
}
