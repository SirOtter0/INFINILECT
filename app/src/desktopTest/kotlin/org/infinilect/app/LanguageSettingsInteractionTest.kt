// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.*
import org.infinilect.app.ui.*
import java.util.Locale
import java.io.IOException
import kotlin.test.*

/** Packaged Android assets are additionally checked by verifyDebugLanguageResources. */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageSettingsInteractionTest {
    private fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    private fun TestScope.pump(scene: ImageComposeScene) {
        repeat(16) { runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close() }; runCurrent()
    }
    private fun tables(): Map<String, LocalizedStrings> = runBlocking {
        deviceInterfaceLanguage()
        val original = Locale.getDefault()
        try {
            interfaceLanguages.associateWith { code ->
                applyInterfaceLanguage(code); loadInterfaceStrings(getSystemResourceEnvironment())
            }
        } finally { Locale.setDefault(original) }
    }
    @Test fun runtimeLanguageChangeAppliesRealResourcesAtomicallyAndKeepsReaderContentMounted() = runTest {
        val resources = tables(); val original = Locale.getDefault()
        var preference by mutableStateOf<String?>("en")
        var applied = ""; var title = ""; var mounts = 0
        val scene = ImageComposeScene(400,700,coroutineContext=StandardTestDispatcher(testScheduler))
        try {
            scene.setContent {
                InterfaceLanguage(preference, loader = { env -> LocalizedStrings(UiText.entries.associateWith { getString(env,it.resource) }) }) {
                    val language=LocalInterfaceLanguage.current; val strings=LocalAppStrings.current
                    SideEffect { applied=language; title=strings[UiText.LANGUAGE_TRANSLATION] }
                    DisposableEffect(Unit) { mounts++; onDispose {} }
                    Text(title)
                }
            }
            for (code in listOf("en","es","ca","en")) {
                preference=code; pump(scene)
                assertEquals(code,applied); assertEquals(resources.getValue(code)[UiText.LANGUAGE_TRANSLATION],title)
            }
            assertEquals(1,mounts)
        } finally { scene.close(); Locale.setDefault(original) }
    }
    @Test fun capturedResourceEnvironmentDoesNotMixLanguagesWhenGlobalLocaleChanges() = runBlocking {
        deviceInterfaceLanguage(); val original=Locale.getDefault()
        try {
            applyInterfaceLanguage("es"); val spanish=getSystemResourceEnvironment()
            applyInterfaceLanguage("ca")
            val result=loadInterfaceStrings(spanish)
            assertEquals("Traducir",result[UiText.TRANSLATE]); assertEquals("Ajustes",result[UiText.SETTINGS])
        } finally { Locale.setDefault(original) }
    }
    @Test fun loadFailureRetainsLastGoodLanguageAndRetryAppliesRequestedPreference() = runTest {
        val resources=tables(); val original=Locale.getDefault()
        var preference by mutableStateOf("es"); var failure=false
        var applied=""; var strings=LocalizedStrings(); var retry: () -> Unit = {}
        val scene=ImageComposeScene(400,700,coroutineContext=StandardTestDispatcher(testScheduler))
        try {
            scene.setContent { InterfaceLanguage(preference, loader = {
                if(failure) throw IOException("Synthetic missing resource, never logged")
                resources.getValue(Locale.getDefault().language)
            }) {
                val current=LocalAppStrings.current; val language=LocalInterfaceLanguage.current; val again=LocalRetryInterfaceLanguage.current
                SideEffect { strings=current; applied=language; retry=again }
                Text(current[UiText.SETTINGS])
            } }
            pump(scene); assertEquals("es",applied)
            failure=true; preference="ca"; pump(scene)
            assertEquals("es",applied); assertTrue(strings.failed); assertEquals("Ajustes",strings[UiText.SETTINGS])
            failure=false; retry(); pump(scene)
            assertEquals("ca",applied); assertFalse(strings.failed); assertEquals("Configuració",strings[UiText.SETTINGS])
        } finally { scene.close(); Locale.setDefault(original) }
    }
    @Test fun settingsAccessRowsAreWholeRowButtonsAndLanguagePanelCanScrollWithoutCoveringDone() = runTest {
        val store=object: ApplicationAppearanceStore {
            override suspend fun load()=ApplicationThemeMode.LIGHT
            override suspend fun save(mode: ApplicationThemeMode)=true
        }
        val preferences=ApplicationAppearancePreferences(store, StandardTestDispatcher(testScheduler))
        val scene=ImageComposeScene(320,440,Density(1f,1.8f),coroutineContext=StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            scene.setContent { MaterialTheme { ApplicationSettingsScreen(ApplicationThemeMode.LIGHT,{},false,preferences=preferences) } }
            pump(scene)
            val access=nodes(scene).single { it.config.getOrNull(SemanticsProperties.Role)==Role.Button &&
                walk(it).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text=="Language and translation" }==true } }
            assertTrue(access.size.height>=64)
            assertTrue(checkNotNull(access.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()); pump(scene)
            fun done()=nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && walk(it).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text=="Done" }==true } }
            assertTrue(done().boundsInRoot.bottom<=440f); assertTrue(done().boundsInRoot.top>=0)
            val scroll=nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) && walk(it).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text=="Interface language" }==true } }
            assertTrue(checkNotNull(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f,2000f))
            testScheduler.advanceTimeBy(500); pump(scene)
            assertTrue(done().boundsInRoot.bottom<=440f)
            assertTrue(checkNotNull(done().config.getOrNull(SemanticsActions.OnClick)?.action).invoke()); pump(scene)
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text=="Done" }==true })
        } finally { scene.close(); preferences.close(); runCurrent(); preferences.awaitClosed() }
    }
    @Test fun obsoleteResourceLoadCannotReplaceNewLanguageOrClearItsLoadingIndicator() = runTest {
        val resources=tables(); val original=Locale.getDefault()
        val old=CompletableDeferred<Unit>(); val latest=CompletableDeferred<Unit>()
        var preference by mutableStateOf("es"); var applied=""; var loading=false
        val scene=ImageComposeScene(400,700,coroutineContext=StandardTestDispatcher(testScheduler))
        try {
            scene.setContent { InterfaceLanguage(preference, loader = {
                val requested=Locale.getDefault().language
                if(requested=="es") withContext(NonCancellable) { old.await() } else latest.await()
                resources.getValue(requested)
            }) {
                val current=LocalInterfaceLanguage.current; val busy=LocalInterfaceLanguageLoading.current
                SideEffect { applied=current; loading=busy }
                Text(LocalAppStrings.current[UiText.SETTINGS])
            } }
            pump(scene); assertTrue(loading)
            preference="ca"; pump(scene); old.complete(Unit); pump(scene)
            assertTrue(loading,"An obsolete cancelled load must not clear the newest loading state")
            assertEquals("en",applied)
            latest.complete(Unit); pump(scene); assertEquals("ca",applied); assertFalse(loading)
        } finally { old.complete(Unit); latest.complete(Unit); scene.close(); Locale.setDefault(original) }
    }

}
