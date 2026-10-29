// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.infinilect.app.discovery.*
import org.infinilect.app.ui.*
import org.infinilect.core.*
import java.util.Locale
import kotlin.test.*

/** Headless real shared Compose only; does not execute Android browser/installer APIs. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class OapenInterfaceTest {
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node)+node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap {walk(it.unmergedRootSemanticsNode)}
    }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap {it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map {it.text}
    private fun click(scene: ImageComposeScene, label: String) {
        fun contains(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true || node.children.any(::contains)
        val node=assertNotNull(nodes(scene).lastOrNull {it.config.contains(SemanticsActions.OnClick) && contains(it)})
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    @Test fun officialLinksAndMetadataRemainLocalizedAtSmallLargeFontAndDesktopSizes()=runTest {
        val previous=Locale.getDefault()
        val tables=try {interfaceLanguages.associateWith {language -> applyInterfaceLanguage(language);loadInterfaceStrings(getSystemResourceEnvironment())}}
            finally {Locale.setDefault(previous)}
        val id=PublicationId(OAPEN_SOURCE_ID,"20.500.12657/25287")
        val bytes=checkNotNull(javaClass.getResourceAsStream("/oapen/public-record.xml")).use {it.readBytes()}
        val data=assertNotNull(oapenMetadata(bytes,id))
        for(language in interfaceLanguages) for(mode in listOf(ApplicationThemeMode.LIGHT,ApplicationThemeMode.DARK))
            for((width,scale) in listOf(320 to 1.8f,390 to 1f,1280 to 1.5f)) {
                val strings=tables.getValue(language)
                val scene=ImageComposeScene(width,900,Density(1f,scale),coroutineContext=StandardTestDispatcher(testScheduler))
                var loads=0
                val source=object:PublicationBibliographySource {override suspend fun bibliography(id:PublicationId)=data.also {loads++}}
                var code by mutableStateOf(language)
                try {
                    scene.setContent {
                        CompositionLocalProvider(LocalAppStrings provides tables.getValue(code)) {ApplicationTheme(mode) {
                            PublicationDetails(data.publication,"OAPEN",close={},bibliographySource=source) {}
                        }}
                    }
                    var frame=0L
                    fun draw() {repeat(6) {runCurrent();scene.render(++frame*16_666_667L).close()}}
                    draw()
                    assertTrue(data.publication.title in texts(scene),"title: $language $width $scale")
                    assertTrue(strings[UiText.OAPEN_OPEN_RECORD] in texts(scene),"official action: $language $width $scale")
                    assertTrue(strings[UiText.OAPEN_PUBLISHER] in texts(scene),"publisher: $language $width $scale")
                    assertTrue(strings[UiText.OAPEN_IDENTIFIERS] in texts(scene),"identifiers: $language $width $scale")
                    assertTrue(strings[UiText.OAPEN_LIMITED] in texts(scene),"limit notice: $language $width $scale")
                    assertEquals(1,loads)
                    code=if(language=="es") "ca" else "es";draw()
                    assertTrue(tables.getValue(code)[UiText.OAPEN_OPEN_RECORD] in texts(scene))
                    assertEquals(1,loads,"Language changes must not requery metadata")
                } finally {scene.close();runCurrent()}
            }
    }
    @Test fun browserActionUsesOnlyTheCanonicalPageAndExposesBrowserFailure()=runTest {
        val id=PublicationId(OAPEN_SOURCE_ID,"20.500.12657/25287")
        val scene=ImageComposeScene(390,700,Density(1f),coroutineContext=StandardTestDispatcher(testScheduler))
        val opened=mutableListOf<String>();var failure=false
        val strings=LocalizedStrings() // English fallback; no provider data is localized.
        try {
            scene.setContent {CompositionLocalProvider(LocalUriHandler provides object:UriHandler {
                override fun openUri(uri:String) {if(failure) error("private browser diagnostic");opened+=uri}
            }) {ApplicationTheme(ApplicationThemeMode.LIGHT) {OapenNotice(id)}}}
            var frame=0L
            fun draw(){repeat(6){runCurrent();scene.render(++frame*16_666_667L).close()}}
            draw();click(scene,strings[UiText.OAPEN_OPEN_RECORD]);draw()
            assertEquals(listOf(oapenPage(id)),opened)
            failure=true;click(scene,strings[UiText.OAPEN_OPEN_RECORD]);draw()
            assertTrue(strings[UiText.OAPEN_OPEN_FAILED] in texts(scene))
            assertEquals(1,opened.size)
        } finally {scene.close();runCurrent()}
    }
}
