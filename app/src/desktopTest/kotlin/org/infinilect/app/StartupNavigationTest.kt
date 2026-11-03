// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.*
import org.infinilect.app.ui.*
import org.infinilect.app.collections.*
import org.infinilect.app.discovery.*
import org.infinilect.core.*
import java.util.Locale
import kotlin.test.*

/** Controlled shared-UI startup interleavings, not physical Android crash evidence. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class StartupNavigationTest {
    private val paths = listOf(
        listOf(Destination.LIBRARY), listOf(Destination.HISTORY), listOf(Destination.SEARCH), listOf(Destination.SETTINGS),
        listOf(Destination.LIBRARY, Destination.HOME), listOf(Destination.SETTINGS, Destination.SEARCH))
    private class Source : PublicationSource, DiscoverySource {
        override val id=SourceId("original-startup-fixture")
        override val canBrowseGenres=true
        val calls=mutableListOf<CompletableDeferred<Unit>>()
        var cancelled=0
        override suspend fun discover(request:DiscoveryRequest,token:String?):DiscoveryPage {
            val gate=CompletableDeferred<Unit>();calls+=gate
            // A transport can finish a callback after its caller was cancelled.
            try { withContext(NonCancellable) { gate.await() };currentCoroutineContext().ensureActive() }
            catch(e:CancellationException){cancelled++;throw e}
            return DiscoveryPage(listOf(DiscoveryEntry(Publication(PublicationId(id,"original"),"Original source title 李",PublicationType.BOOK))))
        }
        override suspend fun search(query:String,pageToken:String?)=SearchPage(emptyList())
        override suspend fun getPublication(publicationId:PublicationId):Publication?=error("No acquisition")
        override suspend fun loadResource(resource:PublicationResource):ResourceContent=error("No book bytes")
        fun deliver(){calls.toList().forEach { it.complete(Unit) }}
    }
    private fun nodes(scene:ImageComposeScene):List<SemanticsNode> {
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun clickTab(scene:ImageComposeScene,destination:Destination) {
        val label=current[when(destination){Destination.HOME->UiText.HOME;Destination.LIBRARY->UiText.LIBRARY;Destination.HISTORY->UiText.HISTORY;Destination.SEARCH->UiText.SEARCH;Destination.SETTINGS->UiText.SETTINGS}]
        val tab=nodes(scene).single { it.config.getOrNull(SemanticsProperties.Role)==Role.Tab &&
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()?.endsWith(label)==true }
        assertFalse(tab.config.contains(SemanticsProperties.Disabled))
        assertTrue(tab.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private var current=LocalizedStrings()
    private fun tables()=runBlocking {
        deviceInterfaceLanguage();val old=Locale.getDefault()
        try { interfaceLanguages.associateWith { applyInterfaceLanguage(it);loadInterfaceStrings(getSystemResourceEnvironment()) } }
        finally {Locale.setDefault(old)}
    }
    @Test fun shellCanBeRemovedBeforeItsFirstLayoutWithoutRequestingAnUnattachedFocusTarget()=runTest {
        val scene=ImageComposeScene(360,640,coroutineContext=StandardTestDispatcher(testScheduler))
        var visible by mutableStateOf(true)
        try {
            scene.setContent {MaterialTheme {if(visible)ApplicationShell(Destination.HOME,false,{}) {Text("Original fixture")} }}
            runCurrent() // Dispatch effects before the deferred BoxWithConstraints layout.
            visible=false;runCurrent();scene.render(0).close();runCurrent()
        } finally {scene.close()}
    }
    @Test fun actualApplicationAcceptsEveryEarlyPathWithPendingPreferencesAndCollections()=runTest {
        val old=Locale.getDefault();val dispatcher=StandardTestDispatcher(testScheduler)
        try { for(code in listOf("en","es","ca",null)) for(fail in listOf(false,true)) for(path in paths) {
            val source=Source();val data=FakeCollections().also{it.fail=fail};val gate=CompletableDeferred<Unit>()
            data.beforeRead={gate.await()}
            val collection=ApplicationCollections(data.library,data.history,dispatcher)
            val prefs=ApplicationAppearancePreferences(object:ApplicationAppearanceStore {
                override suspend fun load()=ApplicationThemeMode.LIGHT
                override suspend fun save(mode:ApplicationThemeMode)=true
                override suspend fun loadPreferences():ApplicationPreferences {gate.await();if(fail)throw java.io.IOException("Controlled preferences failure");return ApplicationPreferences(profile=LocalProfile(interfaceLanguage=code,setupHandled=true),discovery=DiscoveryPreferences(homeEnabled=true,showCatalogOnlySources=true))}
            },dispatcher)
            val owner=ApplicationSources(listOf(SourceOption("Original source",source)),collections=collection,appearance=prefs,sessionDispatcher=dispatcher) {}
            val app=owner.applicationSession();val scene=ImageComposeScene(360,640,coroutineContext=dispatcher)
            var frame=0L
            fun draw(){repeat(3){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
            try {
                scene.setContent {App(owner)}
                for(target in path){app.navigate(target);draw();assertEquals(target,app.destination.value)}
                assertFalse(prefs.state.value.loaded)
                gate.complete(Unit);draw();assertTrue(prefs.state.value.loaded)
                source.deliver();draw();assertEquals(path.last(),app.destination.value)
                app.navigate(Destination.HOME);draw();source.deliver();draw()
                assertTrue(data.saved.isEmpty() && data.opened.isEmpty())
            } finally {gate.complete(Unit);source.deliver();scene.close();owner.close();runCurrent();source.deliver();runCurrent();collection.awaitClosed();prefs.awaitClosed()}
        } } finally {Locale.setDefault(old)}
    }
    @Test fun pendingLoadedAndFailedResourceTablesDoNotOwnNavigationOrAcceptLateHomeResults()=runTest {
        val resources=tables();val old=Locale.getDefault();val dispatcher=StandardTestDispatcher(testScheduler)
        try {for(code in listOf("en","es","ca",null)) for(fail in listOf(false,true)) {
            val source=Source();val data=FakeCollections();val collections=ApplicationCollections(data.library,data.history,dispatcher)
            val owner=ApplicationSources(listOf(SourceOption("Original source",source)),collections=collections,sessionDispatcher=dispatcher) {}
            val app=owner.applicationSession();val gate=CompletableDeferred<Unit>()
            val scene=ImageComposeScene(360,640,coroutineContext=dispatcher)
            val home=LazyListState();val library=LazyGridState();val search=LazyGridState();val viewport=DiscoverySearchViewport()
            var frame=0L;var loading=true;var applied="";var retry:()->Unit={};var failure=fail
            fun draw(){repeat(3){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
            try {
                scene.setContent {InterfaceLanguage(code,loader={
                    val requested=Locale.getDefault().language;gate.await()
                    if(failure)throw java.io.IOException("Controlled initialization failure")
                    resources.getValue(requested)
                }) {
                    val strings=LocalAppStrings.current;val busy=LocalInterfaceLanguageLoading.current
                    val lang=LocalInterfaceLanguage.current;val again=LocalRetryInterfaceLanguage.current
                    SideEffect {current=strings;loading=busy;applied=lang;retry=again}
                    val destination by app.destination.collectAsState()
                    ApplicationTheme(ApplicationThemeMode.LIGHT) {ApplicationShell(destination,false,app::navigate) {
                        when(destination){
                            Destination.HOME->HomeScreen(app,ApplicationAppearanceState(loaded=true,profile=LocalProfile(setupHandled=true),discovery=DiscoveryPreferences(homeEnabled=true,showCatalogOnlySources=true)),emptyList(),null,home,null,{})
                            Destination.LIBRARY,Destination.HISTORY->CollectionScreen(destination,app,library)
                            Destination.SEARCH->UnifiedSearchScreen(app,search,viewport)
                            Destination.SETTINGS->ApplicationSettingsScreen(ApplicationThemeMode.LIGHT,{},false)
                        }
                    }}
                }}
                draw();assertTrue(loading);assertTrue(source.calls.isNotEmpty())
                for(path in paths){clickTab(scene,Destination.HOME);draw();for(target in path){clickTab(scene,target);draw();assertEquals(target,app.destination.value)}}
                val stale=app.homeDiscovery.state.value
                source.deliver();draw();assertEquals(stale,app.homeDiscovery.state.value)
                gate.complete(Unit);draw();assertFalse(loading)
                assertEquals(if(fail)"en"else resolveInterfaceLanguage(code,deviceInterfaceLanguage()),applied)
                if(fail){assertTrue(current.failed);failure=false;retry();draw();assertFalse(current.failed)}
                for(path in paths){clickTab(scene,Destination.HOME);draw();for(target in path){clickTab(scene,target);draw();assertEquals(target,app.destination.value)}}
                assertTrue(data.saved.isEmpty() && data.opened.isEmpty())
            } finally {gate.complete(Unit);source.deliver();scene.close();owner.close();runCurrent();source.deliver();runCurrent();collections.awaitClosed()}
        }} finally {Locale.setDefault(old)}
    }
}
