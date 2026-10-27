// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.ui.*
import org.infinilect.app.updates.*
import org.jetbrains.compose.resources.*
import java.util.Locale
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class,InternalComposeUiApi::class,ExperimentalCoroutinesApi::class)
class UpdateInterfaceTest {
    private class Backend : UpdateBackend {
        override val installed=InstalledUpdateBuild("0.0.1-SNAPSHOT",1,true,35)
        override val trustedInstallationConfigured=false
        var checks=0;var downloads=0
        override suspend fun loadPreferences()=UpdatePreferences()
        override suspend fun savePreferences(value: UpdatePreferences)=Unit
        override suspend fun latest(channel: UpdateChannel): OfficialRelease {checks++;return OfficialRelease("v1.0.0",ReleaseVersion(1,0,0),false,"<script>synthetic plain text</script>\n".repeat(200),emptyList())}
        override suspend fun prepare(release: OfficialRelease,progress: (Long,Long,Boolean)->Unit): PreparedUpdate {downloads++;error("Trust absent")}
        override suspend fun install(value: PreparedUpdate)=error("Trust absent")
        override suspend fun discard()=Unit
        override suspend fun automaticAllowed()=true
        override fun requestInstallPermission()=Unit
        override fun openOfficialPage()=Unit
        override fun close()=Unit
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap {walk(it.unmergedRootSemanticsNode)}
    }
    private fun has(n: SemanticsNode,text: String): Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any {it.text==text}==true || n.children.any {has(it,text)}
    private fun TestScope.pump(scene: ImageComposeScene) {repeat(12) {runCurrent();scene.render(testScheduler.currentTime*1_000_000).close()};runCurrent()}
    @Test fun localizedNarrowLargeFontSettingsKeepTrustBlockedAndNotesSafeAndScrollable()=runTest {
        deviceInterfaceLanguage();val previous=Locale.getDefault()
        val tables=try {interfaceLanguages.associateWith {language -> applyInterfaceLanguage(language);loadInterfaceStrings(getSystemResourceEnvironment())}} finally {Locale.setDefault(previous)}
        for((language,strings) in tables) for(dark in listOf(false,true)) for(font in listOf(1f,1.8f)) {
            val b=Backend();val c=UpdateController(b,{100_000},StandardTestDispatcher(testScheduler))
            val scene=ImageComposeScene(320,440,Density(1f,font),coroutineContext=StandardTestDispatcher(testScheduler))
            try {
                scene.setContent {CompositionLocalProvider(LocalAppStrings provides strings) {ApplicationTheme(if(dark) ApplicationThemeMode.DARK else ApplicationThemeMode.LIGHT) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {UpdateSettings(c)}
                }}}
                pump(scene);assertEquals(0,b.checks);assertTrue(nodes(scene).any {has(it,strings[UiText.UPDATES_BLOCKED])},language)
                fun button(label: String)=nodes(scene).last {it.config.contains(SemanticsActions.OnClick) && has(it,label)}
                fun click(label: String) {assertTrue(button(label).config[SemanticsActions.OnClick].action!!.invoke());pump(scene)}
                click(strings[UiText.UPDATES_CHECK]);assertEquals(1,b.checks);assertEquals(0,b.downloads)
                assertTrue(button(strings[UiText.UPDATES_UPDATE]).config.contains(SemanticsProperties.Disabled))
                click(strings[UiText.UPDATES_NOTES])
                val close=button(strings[UiText.CLOSE]);assertTrue(close.boundsInRoot.top>=0 && close.boundsInRoot.bottom<=440,"Close remains visible: $language / $font")
                assertTrue(nodes(scene).any {it.config.contains(SemanticsActions.ScrollBy) && has(it,"<script>synthetic plain text</script>\n".repeat(200))})
                assertTrue(close.size.height>=48)
                click(strings[UiText.CLOSE]);assertEquals(0,b.downloads)
                click(strings[UiText.UPDATES_BETA]);assertEquals(UpdateChannel.BETA,c.state.value.preferences.channel)
                assertTrue(button(strings[UiText.UPDATES_BETA]).config[SemanticsProperties.Selected])
                assertEquals(1,b.checks,"Channel change never downloads or queries without another action")
            } finally {scene.close();c.close();runCurrent();c.awaitClosed()}
        }
    }
}
