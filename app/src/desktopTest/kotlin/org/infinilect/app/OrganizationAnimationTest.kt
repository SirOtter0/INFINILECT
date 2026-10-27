// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.test.*
import org.infinilect.app.ui.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class,InternalComposeUiApi::class,kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrganizationAnimationTest {
    @Test fun sharedAnimationsAreBoundedDoNotMoveTargetsAndHonorZeroMotionScale()=runTest {
        for(glyph in listOf(LibraryGlyph.HEART,LibraryGlyph.BOOKMARK)) for(dark in listOf(false,true)) for(motion in listOf(0f,1f)) {
            var active by mutableStateOf(false)
            val scene=ImageComposeScene(96,96,Density(1f,1.8f),coroutineContext=StandardTestDispatcher(testScheduler)+object:MotionDurationScale {override val scaleFactor=motion})
            try {
                scene.setContent {ApplicationTheme(if(dark) ApplicationThemeMode.DARK else ApplicationThemeMode.LIGHT) {
                    OrganizationToggle("Original accessible action",glyph,active) {active=!active}
                }}
                fun frame() {repeat(3) {runCurrent();scene.render(testScheduler.currentTime*1_000_000).close()};runCurrent()}
                fun pixels():String = scene.render(testScheduler.currentTime*1_000_000).use {it.encodeToData()!!.use {d -> java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(d.bytes))}}
                fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
                fun button()=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}.single {it.config.getOrNull(SemanticsProperties.ContentDescription)==listOf("Original accessible action")}
                frame();advanceTimeBy(500);frame()
                val original=pixels();val bounds=button().boundsInRoot
                assertTrue(bounds.width>=48 && bounds.height>=48)
                assertFalse(button().config[SemanticsProperties.Selected])
                assertTrue(button().config[SemanticsActions.OnClick].action!!.invoke());frame()
                advanceTimeBy(70);frame();frame();val during=pixels()
                advanceTimeBy(400);frame();frame();val settled=pixels()
                assertEquals(bounds,button().boundsInRoot);assertTrue(button().config[SemanticsProperties.Selected]);assertNotEquals(original,settled)
                if(motion==0f) assertEquals(during,settled) else assertNotEquals(during,settled)
                assertTrue(button().config[SemanticsActions.OnClick].action!!.invoke());frame();advanceTimeBy(500);frame();frame()
                assertEquals(original,pixels(),"Deactivation settles: $glyph dark=$dark motion=$motion");assertEquals(bounds,button().boundsInRoot)
                assertFalse(button().config[SemanticsProperties.Selected])
            } finally {scene.close();runCurrent()}
        }
    }
}
