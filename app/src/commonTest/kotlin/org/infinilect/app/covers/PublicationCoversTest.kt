// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.covers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.core.*
import org.infinilect.app.ui.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PublicationCoversTest {
    private fun id(number: Int, source: String = "original") = PublicationId(SourceId(source), "book-$number")
    @Test fun phonesUseTwoColumnsAndDesktopColumnsGrowWithoutHugeCovers() {
        assertEquals(1, libraryColumns(280f)); assertEquals(2, libraryColumns(360f)); assertEquals(2, libraryColumns(420f))
        assertEquals(4, libraryColumns(800f)); assertEquals(6, libraryColumns(1120f))
        for (width in listOf(600f,800f,1120f)) assertTrue((width - 32 - 12 * (libraryColumns(width)-1)) / libraryColumns(width) < 220)
    }
    @Test fun fallbackColorsAreDeterministicAndPublicationSpecific() {
        val first=coverPaletteIndex(id(0)); assertEquals(first, coverPaletteIndex(id(0)))
        assertTrue((0..100).map { coverPaletteIndex(id(it)) }.toSet().size == 4)
    }
    @Test fun typographicInitialPreservesUnicodeAndSkipsLeadingWhitespace() {
        assertEquals("📚", coverInitial("📚 Original book")); assertEquals("É", coverInitial("  élan"))
        assertEquals("", coverInitial(""))
    }
    @Test fun coverTitlesKeepContrastEvenOverWhiteArtwork() {
        for (art in listOf(Color.White,Color.Yellow,Color.Black)) {
            val backdrop=Color.Black.copy(alpha=COVER_TITLE_MIN_SCRIM).compositeOver(art)
            assertTrue((Color.White.luminance()+.05f)/(backdrop.luminance()+.05f)>=4.5f)
        }
    }
    @Test fun samplingNeverExceedsThumbnailBudget() {
        for ((w,h) in listOf(800 to 1200,1024 to 1024,2048 to 512,96 to 64,1 to 2048)) {
            val sample=CoverPolicy.sample(w,h)
            assertTrue((w+sample-1)/sample<=192); assertTrue((h+sample-1)/sample<=288)
        }
    }
    @Test fun duplicateLibraryAndHistoryLeasesLoadOnce() = runTest {
        var calls=0
        val cache=PublicationCovers({ calls++;CoverArtwork(PublicationFormat.TEXT) },StandardTestDispatcher(testScheduler))
        val a=cache.acquire(id(0));val b=cache.acquire(id(0));runCurrent()
        assertEquals(1,calls);assertSame(a.state,b.state);assertEquals(PublicationFormat.TEXT,a.state.value.format)
        cache.release(a);cache.release(b);val again=cache.acquire(id(0));runCurrent();assertEquals(1,calls)
        cache.release(again);cache.close();cache.awaitClosed();assertEquals(0,cache.retainedEntries())
    }
    @Test fun pinnedAndPendingEntriesCannotExceedHardBound() = runTest {
        val gate=CompletableDeferred<Unit>();var active=0;var maximum=0
        val cache=PublicationCovers({ active++;maximum=maxOf(maximum,active);try {gate.await();null} finally {active--} },StandardTestDispatcher(testScheduler))
        val leases=(0..200).map {cache.acquire(id(it))};runCurrent()
        assertEquals(24,cache.retainedEntries());assertEquals(1,maximum)
        leases.forEach {cache.release(it)};runCurrent();assertEquals(0,active)
        cache.close();cache.awaitClosed()
    }
    @Test fun lruEvictionRetainsRecentHitsAndReloadsOldest() = runTest {
        val calls=mutableListOf<PublicationId>()
        val cache=PublicationCovers({ calls+=it;null },StandardTestDispatcher(testScheduler))
        suspend fun visit(i:Int) {val lease=cache.acquire(id(i));runCurrent();cache.release(lease)}
        repeat(24){visit(it)};visit(0);visit(24);visit(0);assertEquals(25,calls.size)
        visit(1);assertEquals(26,calls.size);assertEquals(24,cache.retainedEntries())
        cache.close();cache.awaitClosed()
    }
    @Test fun leavingViewportCancelsObsoleteWorkAndAllowsNextItem() = runTest {
        val entered=CompletableDeferred<Unit>();var cancelled=false;var second=false
        val cache=PublicationCovers({ if(it==id(0)) {entered.complete(Unit);try {awaitCancellation()} finally {cancelled=true}} else {second=true;null} },StandardTestDispatcher(testScheduler))
        val first=cache.acquire(id(0));runCurrent();assertTrue(entered.isCompleted)
        val next=cache.acquire(id(1));cache.release(first);runCurrent()
        assertTrue(cancelled);assertTrue(second);cache.release(next);cache.close();cache.awaitClosed()
    }
    @Test fun removalInvalidatesPositiveOrNegativeResult() = runTest {
        var calls=0
        val cache=PublicationCovers({ calls++;CoverArtwork(PublicationFormat.EPUB) },StandardTestDispatcher(testScheduler))
        val old=cache.acquire(id(0));runCurrent();cache.invalidate(id(0));assertNull(old.state.value.format)
        cache.release(old);val next=cache.acquire(id(0));runCurrent();assertEquals(2,calls)
        cache.release(next);cache.close();cache.awaitClosed()
    }
    @Test fun sourceIdentityAndChangedDigestNeverShareResults() = runTest {
        var calls=0
        val cache=PublicationCovers({ calls++;null },StandardTestDispatcher(testScheduler))
        val leases=listOf(id(0),id(0,"other"),id(1)).map {cache.acquire(it)};runCurrent();assertEquals(3,calls)
        leases.forEach {cache.release(it)};cache.close();cache.awaitClosed()
    }
    @Test fun failedAndTimedOutLoadsRemainFallbacksWithoutRepeatedScrollWork() = runTest {
        var calls=0
        val cache=PublicationCovers({ calls++;if(it==id(0)) error("private failure") else awaitCancellation() },StandardTestDispatcher(testScheduler))
        val a=cache.acquire(id(0));val b=cache.acquire(id(1));runCurrent();advanceTimeBy(10_001);runCurrent()
        assertNull(a.state.value.image);assertNull(b.state.value.image);assertEquals(2,calls)
        cache.release(a);cache.release(b);val retry=cache.acquire(id(1));runCurrent();assertEquals(2,calls)
        cache.release(retry);cache.close();cache.awaitClosed()
    }
    @Test fun closeCancelsJobClearsArtworkAndRejectsNewWork() = runTest {
        var cancelled=false
        val cache=PublicationCovers({ try {awaitCancellation()} finally {cancelled=true} },StandardTestDispatcher(testScheduler))
        val lease=cache.acquire(id(0));runCurrent();cache.close();cache.close();cache.awaitClosed()
        assertTrue(cancelled);assertEquals(0,cache.retainedEntries());assertNull(cache.acquire(id(1)).entry)
        cache.release(lease)
    }
    @Test fun longBrowsingSessionRetainsOnlyBoundedRecentResults() = runTest {
        val cache=PublicationCovers({null},StandardTestDispatcher(testScheduler))
        repeat(300) {val lease=cache.acquire(id(it));runCurrent();cache.release(lease);assertTrue(cache.retainedEntries()<=24)}
        cache.close();cache.awaitClosed()
    }
}
