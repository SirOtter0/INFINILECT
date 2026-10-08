// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.ui.geometry.Offset
import kotlin.test.*

class PageEdgeVelocityTest {
    @Test fun twoTimedSamplesQualifyActualFastNextAndPreviousEdgeMovement() {
        for (sign in listOf(-1f, 1f)) {
            val v = PageEdgeVelocity(1000)
            v.add(1016, sign * 80)
            assertEquals(sign * 5000, v.pixelsPerSecond(), .01f)
            assertTrue(pageDragCompletes(sign * 80 / 640, v.pixelsPerSecond() / 640))
        }
    }
    @Test fun reachingEdgeMidGestureCreditsOnlyExcessAndNoEarlierPanTimeOrSpeed() {
        val g = PageEdgePanGesture(10f, 640f); val v = PageEdgeVelocity(1000)
        var pan = g.move(Offset.Zero, Offset(-80f, 0f), Offset(100f, 200f)); v.add(1016, g.overscroll)
        assertEquals(-80f, pan.x); assertEquals(0f, v.pixelsPerSecond()); assertFalse(g.canHandoff)
        pan = g.move(pan, Offset(-70f, 0f), Offset(100f, 200f)); v.add(1032, g.overscroll)
        assertEquals(-100f, pan.x); assertEquals(-50f, g.overscroll)
        assertEquals(-50f * 1000 / 16, v.pixelsPerSecond(), .01f)
        assertTrue(pageDragCompletes(g.overscroll / 640, v.pixelsPerSecond() / 640))
    }
    @Test fun veryFastPanWithoutEdgeMovementNeverArmsOrFlingsPager() {
        val g = PageEdgePanGesture(10f, 640f); val v = PageEdgeVelocity(1000)
        val pan = g.move(Offset.Zero, Offset(-400f, 0f), Offset(1000f, 200f)); v.add(1008, g.overscroll)
        assertEquals(-400f, pan.x); assertEquals(0f, g.overscroll); assertFalse(g.canHandoff)
        assertEquals(0f, v.pixelsPerSecond()); assertFalse(pageDragCompletes(0f, -100f))
    }
    @Test fun highTotalVelocityWithInsufficientPagerDisplacementStillReturns() {
        val g = PageEdgePanGesture(10f, 640f); val v = PageEdgeVelocity(1000)
        g.move(Offset.Zero, Offset(-220f, 0f), Offset(200f, 200f)); v.add(1008, g.overscroll)
        assertTrue(g.canHandoff); assertEquals(-20f, g.overscroll)
        assertTrue(v.pixelsPerSecond() < -640f)
        assertFalse(pageDragCompletes(g.overscroll / 640, v.pixelsPerSecond() / 640))
    }
    @Test fun fastPanCannotSupplyVelocityToSubsequentSlowButSufficientEdgeDistance() {
        val g = PageEdgePanGesture(10f, 640f); val v = PageEdgeVelocity(1000)
        var pan = g.move(Offset.Zero, Offset(-300f, 0f), Offset(300f, 200f)); v.add(1008, g.overscroll)
        pan = g.move(pan, Offset(-40f, 0f), Offset(300f, 200f)); v.add(1088, g.overscroll)
        assertEquals(-300f, pan.x); assertEquals(-40f, g.overscroll)
        assertTrue(340f / .088f / 640 > .9f, "Total gesture average was fast")
        assertEquals(-500f, v.pixelsPerSecond(), .01f)
        assertFalse(pageDragCompletes(g.overscroll / 640, v.pixelsPerSecond() / 640))
    }
    @Test fun sparseDownMoveUpUsesAllActualExcessWithoutArtificialDistance() {
        val v = PageEdgeVelocity(1000); v.add(1008, -20f); v.add(1016, -80f)
        assertEquals(-5000f, v.pixelsPerSecond(), .01f)
        assertTrue(pageDragCompletes(-80f / 640, v.pixelsPerSecond() / 640))
        assertFalse(pageDragCompletes(-20f / 640, v.pixelsPerSecond() / 640))
    }
    @Test fun reversalCannotInheritOutwardVelocityAndReturningToPanClearsIt() {
        val v = PageEdgeVelocity(1000); v.add(1010, -100f); v.add(1020, -60f)
        assertEquals(4000f, v.pixelsPerSecond(), .01f)
        assertFalse(pageDragCompletes(-60f / 640, v.pixelsPerSecond() / 640))
        v.add(1030, 0f); assertEquals(0f, v.pixelsPerSecond())
        v.add(1040, 60f); assertTrue(pageDragCompletes(60f / 640, v.pixelsPerSecond() / 640))
    }
    @Test fun stationaryReleaseAndRecentHorizonDoNotKeepAnOldFlingAlive() {
        val v = PageEdgeVelocity(1000); v.add(1008, -60f); v.add(1048, -60f)
        assertEquals(0f, v.pixelsPerSecond()); assertFalse(pageDragCompletes(-60f / 640, v.pixelsPerSecond() / 640))
        v.add(1200, -80f); assertEquals(0f, v.pixelsPerSecond())
    }
    @Test fun zeroElapsedBackwardsAndNonFiniteSamplesDoNotManufactureVelocity() {
        val v = PageEdgeVelocity(1000); v.add(1000, -60f); assertEquals(0f, v.pixelsPerSecond())
        v.add(999, -100f); v.add(1010, Float.NaN); assertEquals(0f, v.pixelsPerSecond())
    }
    @Test fun scalarHistoryIsHardBoundedAndInheritedAnimationOffsetIsNotMovement() {
        val v = PageEdgeVelocity(1000, -100f); assertEquals(0f, v.pixelsPerSecond())
        repeat(1000) { v.add(1001L + it, -101f - it); assertTrue(v.sampleCount <= 8) }
        assertEquals(-1000f, v.pixelsPerSecond(), .01f)
    }
}
