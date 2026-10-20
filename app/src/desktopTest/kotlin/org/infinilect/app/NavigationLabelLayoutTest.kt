// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.*
import org.infinilect.app.ui.*
import java.util.Locale
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class,ExperimentalCoroutinesApi::class)
class NavigationLabelLayoutTest {
    private fun tables()=runBlocking {
        deviceInterfaceLanguage();val old=Locale.getDefault()
        try {interfaceLanguages.associateWith{applyInterfaceLanguage(it);loadInterfaceStrings(getSystemResourceEnvironment())}}
        finally {Locale.setDefault(old)}
    }
    private fun nodes(scene:ImageComposeScene):List<SemanticsNode> {
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap {walk(it.unmergedRootSemanticsNode)}
    }
    @Test fun allFiveLabelsRemainSingleLineClickableAndAccessibleInNarrowWindowsAndLargeFonts()=runTest {
        val resources=tables();val dispatcher=StandardTestDispatcher(testScheduler)
        val measurements=mutableListOf("language,widthDp,fontScale,dark,label,lines,ellipsized")
        for((code,strings)in resources)for(width in listOf(320,360,600))for(scale in listOf(1f,1.5f,1.8f))for(dark in listOf(false,true)) {
            val scene=ImageComposeScene(width,600,Density(1f,scale),coroutineContext=dispatcher)
            var selected by mutableStateOf(Destination.SETTINGS)
            try {
                scene.setContent {CompositionLocalProvider(LocalAppStrings provides strings){
                    MaterialTheme(colors=applicationColors(dark)){ApplicationShell(selected,false,{selected=it}){}}
                }}
                repeat(3){runCurrent();scene.render(it*16_666_667L).close()};runCurrent()
                val tabs=nodes(scene).filter {it.config.getOrNull(SemanticsProperties.Role)==Role.Tab}
                assertEquals(5,tabs.size)
                for((index,tab) in tabs.withIndex()) {
                    val bounds=tab.boundsInRoot
                    assertTrue(bounds.width>=48f&&bounds.height>=48f&&bounds.left>=0&&bounds.right<=width,"$code/$width/$scale:$bounds")
                    assertTrue(tab.config[SemanticsProperties.ContentDescription].single().isNotBlank())
                    assertFalse(tab.config.contains(SemanticsProperties.Disabled))
                    assertTrue(tab.config[SemanticsActions.OnClick].action!!.invoke())
                    assertEquals(applicationDestinations[index],selected)
                }
                val labels=nodes(scene).filter {it.config.contains(SemanticsActions.GetTextLayoutResult)}
                    .filter {it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text in listOf(strings[UiText.HOME],strings[UiText.LIBRARY],strings[UiText.HISTORY],strings[UiText.SEARCH],strings[UiText.SETTINGS])}
                assertEquals(5,labels.size)
                for(label in labels) {
                    val layouts=mutableListOf<TextLayoutResult>();assertTrue(label.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                    val layout=layouts.single()
                    measurements+="$code,$width,$scale,$dark,${layout.layoutInput.text.text},${layout.lineCount},${layout.isLineEllipsized(0)}"
                    assertEquals(1,layout.lineCount,"$code/$width/$scale:${layout.layoutInput.text.text}")
                    assertTrue(label.boundsInRoot.width>0)
                    if(scale==1f)assertFalse(layout.isLineEllipsized(0),"Normal-size label should fit: $code/$width:${layout.layoutInput.text.text}")
                }
                System.getenv("INFINILECT_LOCALIZATION_PREVIEWS")?.let{directory->
                    if(code=="ca"&&width in listOf(320,360)&&scale in listOf(1f,1.8f)){
                        val path=java.nio.file.Path.of(directory);require(path.isAbsolute&&!path.startsWith(java.nio.file.Path.of(System.getProperty("user.dir"))))
                        java.nio.file.Files.createDirectories(path)
                        scene.render(100_000_000L).use{frame->frame.encodeToData()!!.use{png->
                            java.nio.file.Files.write(path.resolve("navigation-$code-$width-$scale-$dark.png"),png.bytes)
                        }}
                    }
                }
            } finally {scene.close()}
        }
        System.getenv("INFINILECT_LOCALIZATION_PREVIEWS")?.let{directory->
            val path=java.nio.file.Path.of(directory);require(path.isAbsolute&&!path.startsWith(java.nio.file.Path.of(System.getProperty("user.dir"))))
            java.nio.file.Files.createDirectories(path);java.nio.file.Files.write(path.resolve("navigation-layout.csv"),measurements)
        }
    }
}
