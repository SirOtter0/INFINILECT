// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.ui.graphics.luminance
import kotlin.test.*

class EpubReaderInteractionTest {
    @Test fun controlsStartDiscoverableAndTogglingIsPresentationOnly() {
        val controls = EpubReaderControls()
        assertFalse(controls.visible); assertNull(controls.panel)
        controls.toggle()
        repeat(50) { controls.toggle(); assertFalse(controls.visible); controls.toggle(); assertTrue(controls.visible) }
    }
    @Test fun settingsAndContentsAreMutuallyExclusive() {
        val controls = EpubReaderControls()
        controls.open(EpubReaderPanel.SETTINGS); assertTrue(controls.visible)
        controls.open(EpubReaderPanel.CONTENTS); assertEquals(EpubReaderPanel.CONTENTS, controls.panel)
        controls.toggle(); assertTrue(controls.visible)
        controls.closePanel(); assertNull(controls.panel); assertTrue(controls.visible)
    }
    @Test fun backClosesPanelThenChromeBeforeLeavingThePublication() {
        val controls = EpubReaderControls(); controls.open(EpubReaderPanel.SETTINGS)
        assertTrue(controls.dismiss()); assertNull(controls.panel); assertTrue(controls.visible)
        assertTrue(controls.dismiss()); assertFalse(controls.visible)
        assertFalse(controls.dismiss())
    }
    private fun tap(x: Float = 150f, y: Float = 300f) = EpubReaderTap(x,y,300f,600f,100,12f,500)
    @Test fun stationaryCentralTapQualifies() { assertTrue(tap().observe(155f,305f,200,false,1)) }
    @Test fun consumedLinkOrSelectionNeverTogglesControls() {
        val tap = tap(); assertFalse(tap.observe(150f,300f,110,true,1)); assertFalse(tap.observe(150f,300f,200,false,1))
    }
    @Test fun scrollingCannotBecomeATapWhenReturningToStart() {
        val tap = tap(); assertFalse(tap.observe(150f,340f,150,false,1)); assertFalse(tap.observe(150f,300f,200,false,1))
    }
    @Test fun longPressAndMultitouchRetainOwnershipUntilRelease() {
        assertFalse(tap().observe(150f,300f,600,false,1))
        val tap = tap(); assertFalse(tap.observe(150f,300f,110,false,2)); assertFalse(tap.observe(150f,300f,200,false,1))
    }
    @Test fun sideAndTopBottomTapsDoNotIntroducePageTurning() {
        for ((x,y) in listOf(10f to 300f, 290f to 300f, 150f to 20f, 150f to 590f)) assertFalse(tap(x,y).observe(x,y,200,false,1))
    }
    @Test fun progressLabelsClampExistingContinuousProgressWithoutPageNumbers() {
        assertEquals(0,epubProgressPercent(-.1)); assertEquals(37,epubProgressPercent(.375)); assertEquals(100,epubProgressPercent(1.2))
    }
    @Test fun readingTextAndLinksMeetNormalTextContrastInBothThemes() {
        for (dark in listOf(false,true)) {
            val colors = epubReaderColors(dark)
            for (foreground in listOf(colors.onSurface,colors.primary)) {
                val a=foreground.luminance(); val b=colors.surface.luminance()
                assertTrue((maxOf(a,b)+.05f)/(minOf(a,b)+.05f)>=4.5f)
            }
        }
    }
}
