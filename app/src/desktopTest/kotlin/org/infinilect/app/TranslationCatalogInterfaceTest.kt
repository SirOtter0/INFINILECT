// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.*
import org.infinilect.app.ui.*
import org.infinilect.app.reader.epub.*
import java.util.Locale
import kotlin.test.*

/** Real catalog metadata with a deterministic administration provider; no native/download claim. */
@OptIn(ExperimentalComposeUiApi::class,ExperimentalCoroutinesApi::class)
class TranslationCatalogInterfaceTest {
    private class Engine:EpubTranslationEngine {
        override val identity="original-catalog-test"
        override val unavailable:String?=null
        override val models=foxletVerifiedModels
        val present=mutableSetOf(TranslationPair("en","es"),TranslationPair("ca","en"))
        var downloads=0;var failures=0;var gate=CompletableDeferred<Unit>()
        override suspend fun installed(model:TranslationModel)=model.pair in present
        override suspend fun storageBytes()=models.filter{it.pair in present}.sumOf{it.bytes}
        override suspend fun install(model:TranslationModel,progress:(Long,Long)->Unit){downloads++;progress(model.bytes/2,model.bytes);gate.await();if(failures-->0)throw EpubTranslationException(UiText.DOWNLOAD_ERROR.fallback);present+=model.pair;progress(model.bytes,model.bytes)}
        override suspend fun remove(model:TranslationModel){present-=model.pair}
        override suspend fun translate(model:TranslationModel,text:String):String=error("No inference in administration test")
        override fun close()=Unit
    }
    private class Records:TranslationModelRecords {
        var value=emptyMap<TranslationPair,ModelRetention>()
        override suspend fun load()=value
        override suspend fun save(records:Map<TranslationPair,ModelRetention>):Boolean{value=records;return true}
    }
    private fun tables()=runBlocking {
        deviceInterfaceLanguage();val old=Locale.getDefault()
        try{interfaceLanguages.associateWith{applyInterfaceLanguage(it);loadInterfaceStrings(getSystemResourceEnvironment())}}
        finally{Locale.setDefault(old)}
    }
    private fun nodes(scene:ImageComposeScene):List<SemanticsNode>{
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
    }
    @Test fun fourDirectPairsStayVisibleAcrossLocalesAndAdministrationPreservesInstalledModels()=runTest {
        val resources=tables();val dispatcher=StandardTestDispatcher(testScheduler)
        val engine=Engine();val records=Records();val service=EpubTranslationService(engine,modelRecords=records,modelDispatcher=dispatcher)
        val preferences=ApplicationAppearancePreferences(object:ApplicationAppearanceStore{
            override suspend fun load()=ApplicationThemeMode.LIGHT
            override suspend fun save(mode:ApplicationThemeMode)=true
            override suspend fun loadPreferences()=ApplicationPreferences(profile=LocalProfile(countryCode="ES",interfaceLanguage="en",setupHandled=true),readingLanguage="es")
        },dispatcher)
        val scene=ImageComposeScene(700,900,coroutineContext=dispatcher)
        var language by mutableStateOf("en");var frame=0L
        fun draw(){repeat(4){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
        fun texts()=nodes(scene).flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
        try{
            runCurrent() // Establish the stored profile before testing explicit language edits.
            scene.setContent {CompositionLocalProvider(LocalAppStrings provides resources.getValue(language),LocalInterfaceLanguage provides language){
                MaterialTheme{LanguageTranslationPanel(preferences,service){}}
            }}
            for(code in interfaceLanguages){
                language=code;preferences.changeProfile(preferences.state.value.profile.copy(interfaceLanguage=code));draw()
                val strings=resources.getValue(code)
                for(model in engine.models)assertEquals(1,texts().count{it=="${strings.language(model.pair.source)} → ${strings.language(model.pair.target)}"})
                assertTrue(strings[UiText.DIRECT_MODEL_LIMITS] in texts())
                assertEquals(foxletVerifiedModels,service.models.state.value.models.map{it.model})
                assertEquals(setOf(TranslationPair("en","es"),TranslationPair("ca","en")),service.models.state.value.models.filter{it.installed}.map{it.model.pair}.toSet())
                assertEquals("es",preferences.state.value.readingLanguage);assertEquals("ES",preferences.state.value.profile.countryCode)
                assertEquals(0,engine.downloads)
            }
            val download=resources.getValue(language)[UiText.DOWNLOAD]
            fun contains(n:SemanticsNode):Boolean=n.config.getOrNull(SemanticsProperties.Text)?.any{it.text==download}==true||n.children.any(::contains)
            val buttons=nodes(scene).filter{it.config.contains(SemanticsActions.OnClick)&&contains(it)}
            assertEquals(2,buttons.size)
            assertTrue(buttons.last().config[SemanticsActions.OnClick].action!!.invoke());draw()
            assertEquals(TranslationPair("en","ca"),service.models.state.value.busy)
            assertTrue(service.models.state.value.downloaded>0)
            assertTrue(nodes(scene).any{it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current?.let{value->value in .49f.. .51f}==true})
            assertTrue(resources.getValue(language)[UiText.CANCEL] in texts())
            service.models.cancel();draw();assertFalse(service.models.state.value.working)
            assertFalse(TranslationPair("en","ca") in engine.present)
            engine.failures=1;engine.gate.complete(Unit)
            assertTrue(nodes(scene).filter{it.config.contains(SemanticsActions.OnClick)&&contains(it)}.last().config[SemanticsActions.OnClick].action!!.invoke());draw()
            assertFalse(TranslationPair("en","ca") in engine.present)
            assertTrue(resources.getValue(language)[UiText.DOWNLOAD_ERROR] in texts())
            val retry=resources.getValue(language)[UiText.RETRY]
            fun isRetry(n:SemanticsNode):Boolean=n.config.getOrNull(SemanticsProperties.Text)?.any{it.text==retry}==true||n.children.any(::isRetry)
            assertTrue(nodes(scene).single{it.config.contains(SemanticsActions.OnClick)&&isRetry(it)}.config[SemanticsActions.OnClick].action!!.invoke());draw()
            assertTrue(TranslationPair("en","ca") in engine.present);assertEquals(3,engine.downloads)
            val before=engine.present.toSet();service.models.close();service.models.awaitClosed()
            val restored=TranslationModels(engine,records,dispatcher)
            try{
                val refreshed=restored.refresh();runCurrent();assertTrue(refreshed.await())
                assertEquals(before,restored.state.value.models.filter{it.installed}.map{it.model.pair}.toSet())
                assertTrue(restored.state.value.models.filter{it.model.pair in setOf(TranslationPair("en","es"),TranslationPair("ca","en"))}.all{it.retention.pinned})
                val target=foxletVerifiedModels.single{it.pair==TranslationPair("en","ca")}
                val removed=restored.remove(target);runCurrent();assertTrue(removed.await())
                assertEquals(before-target.pair,engine.present)
            }finally{restored.close();restored.awaitClosed()}
        }finally{engine.gate.complete(Unit);scene.close();preferences.close();service.close();runCurrent();preferences.awaitClosed();service.awaitClosed()}
    }
    @Test fun unsupportedSpanishCatalanSelectionUsesHonestLocalizedErrorAndNeverRequestsADownload()=runTest {
        val resources=tables();val engine=Engine();val service=EpubTranslationService(engine,modelDispatcher=StandardTestDispatcher(testScheduler))
        try{for(pair in listOf(TranslationPair("es","ca"),TranslationPair("ca","es"))){
            val controller=EpubTranslationController(service,backgroundScope,null,pair.source,{})
            try{
                controller.model(engine.models.firstOrNull{it.pair==pair});controller.selection("Original Unicode 李");runCurrent()
                assertNull(controller.state.value.model);assertNull(controller.state.value.selectionResult)
                assertNull(controller.state.value.requiredModel);assertFalse(controller.state.value.translated)
                for(strings in resources.values)assertEquals(strings[UiText.NO_PAIR],strings.message(assertNotNull(controller.state.value.message)))
                assertEquals(0,engine.downloads)
            }finally{controller.close()}
        }}finally{service.close();runCurrent();service.awaitClosed()}
    }
}
