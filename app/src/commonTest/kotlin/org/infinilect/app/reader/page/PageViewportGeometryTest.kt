// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.infinilect.core.PageDimensions
import kotlin.test.*

class PageViewportGeometryTest {
    @Test fun equalPortraitsShareScaleAndOnlyTinyGutterInEveryViewport() {
        for (viewport in listOf(Size(320f, 480f), Size(1280f, 720f), Size(640f, 180f))) {
            val fit = fitPageSpread(listOf(PageDimensions(768, 1152), PageDimensions(768, 1152)), viewport, 2f)
            assertEquals(fit.pages[0], fit.pages[1]); assertEquals(2f, fit.gutter)
            assertEquals(768f / 1152, fit.pages[0].width / fit.pages[0].height, .0001f)
            assertTrue(fit.size.width <= viewport.width + .001f && fit.size.height <= viewport.height + .001f)
            assertEquals(fit.size.width, fit.pages.sumOf { it.width.toDouble() }.toFloat() + 2f)
        }
    }
    @Test fun mixedPortraitGeometryUsesOneScaleWithoutStretchOrCrop() {
        val pages = listOf(PageDimensions(600, 1000), PageDimensions(500, 800))
        for (viewport in listOf(Size(300f, 600f), Size(1400f, 300f))) {
            val fit = fitPageSpread(pages, viewport, 2f)
            assertEquals(fit.pages[0].height / 1000, fit.pages[1].height / 800, .0001f)
            for ((source, fitted) in pages.zip(fit.pages))
                assertEquals(source.width.toFloat() / source.height, fitted.width / fitted.height, .0001f)
            assertTrue(fit.size.width <= viewport.width && fit.size.height <= viewport.height)
        }
    }
    @Test fun singleWideAndSinglePortraitRemainOrdinaryFullViewportFit() {
        val viewport = Size(640f, 420f)
        assertEquals(Size(640f, 320f), fitPageSpread(listOf(PageDimensions(8, 4)), viewport, 2f).size)
        assertEquals(Size(210f, 420f), fitPageSpread(listOf(PageDimensions(4, 8)), viewport, 2f).size)
        assertEquals(0f, fitPageSpread(listOf(PageDimensions(4, 8)), viewport, 2f).gutter)
        assertEquals(Size.Zero, fitPageSpread(listOf(PageDimensions(4, 8)), Size.Zero, 2f).size)
    }
    @Test fun boundsUseFittedContentRatherThanViewportOrRawSource() {
        val viewport = Size(640f, 420f)
        val fit = fitPageSpread(listOf(PageDimensions(4, 8), PageDimensions(4, 8)), viewport, 2f)
        assertEquals(Offset(0f, 105f), pagePanBounds(fit.size, viewport, 1.5f))
        assertEquals(Offset(102f, 210f), pagePanBounds(fit.size, viewport, 2f))
        val rotated = Size(640f, 180f)
        val compact = fitPageSpread(listOf(PageDimensions(4, 8), PageDimensions(4, 8)), rotated, 2f)
        assertEquals(Offset(0f, 90f), pagePanBounds(compact.size, rotated, 2f))
    }
    @Test fun sameGestureConsumesPanBeforeAccumulatingOnlyExcessBothDirections() {
        for (sign in listOf(-1f, 1f)) {
            val g = PageEdgePanGesture(10f, 640f); val bound = Offset(100f, 200f)
            var pan = g.move(Offset.Zero, Offset(sign * 60, 0f), bound)
            assertEquals(sign * 60, pan.x); assertEquals(0f, g.overscroll); assertFalse(g.canHandoff)
            pan = g.move(pan, Offset(sign * 80, 0f), bound)
            assertEquals(sign * 100, pan.x); assertEquals(sign * 40, g.overscroll); assertTrue(g.canHandoff)
            pan = g.move(pan, Offset(-sign * 10, 0f), bound)
            assertEquals(sign * 100, pan.x); assertEquals(sign * 30, g.overscroll)
            pan = g.move(pan, Offset(-sign * 50, 0f), bound)
            assertEquals(sign * 80, pan.x); assertEquals(0f, g.overscroll)
        }
    }
    @Test fun narrowerContentHasNoFakePanAndCanDeliberatelyOverscroll() {
        val g = PageEdgePanGesture(10f, 640f)
        val pan = g.move(Offset.Zero, Offset(-60f, 0f), Offset(0f, 200f))
        assertEquals(0f, pan.x); assertEquals(-60f, g.overscroll); assertTrue(g.canHandoff)
        assertFalse(pageDragCompletes(g.overscroll / 640, 0f))
        assertTrue(pageDragCompletes(g.overscroll / 640, -1f))
    }
    @Test fun pureVerticalAndPredominantlyVerticalIntentsStayPanOnly() {
        for (delta in listOf(Offset(0f, 80f), Offset(40f, 80f), Offset(40f, 36f))) {
            val g = PageEdgePanGesture(10f, 640f)
            val pan = g.move(Offset.Zero, delta, Offset(0f, 200f))
            assertEquals(delta.y, pan.y); assertEquals(0f, g.overscroll); assertFalse(g.canHandoff)
            g.move(pan, Offset(1000f, 0f), Offset(0f, 200f)); assertFalse(g.canHandoff)
        }
    }
    @Test fun nearBoundaryEpsilonDoesNotRequireExactFloatingPointEquality() {
        val step = consumePagePan(99.75f, 30f, 100f, 0f)
        assertEquals(100f, step.first); assertEquals(30f, step.second)
    }
    @Test fun oversizedMotionCannotExceedOneViewportAndDirectionReversalConsumesItFirst() {
        val g = PageEdgePanGesture(10f, 640f)
        var pan = g.move(Offset.Zero, Offset(-10000f, 0f), Offset(100f, 200f))
        assertEquals(-100f, pan.x); assertEquals(-640f, g.overscroll)
        pan = g.move(pan, Offset(700f, 0f), Offset(100f, 200f))
        assertEquals(-40f, pan.x); assertEquals(0f, g.overscroll)
    }
}
