// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.infinilect.app.*
import org.infinilect.app.collections.*
import org.infinilect.app.ui.*
import org.infinilect.core.*
import java.util.Locale
import kotlin.test.*

/** Real shared Compose at Android/Desktop dimensions, not physical Android validation. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SourceVisibilityInterfaceTest {
    private fun tables()=runBlocking {
        deviceInterfaceLanguage();val previous=Locale.getDefault()
        try {interfaceLanguages.associateWith {language->applyInterfaceLanguage(language);loadInterfaceStrings(getSystemResourceEnvironment())}}
        finally {Locale.setDefault(previous)}
    }
    private fun nodes(scene:ImageComposeScene):List<SemanticsNode> {
        fun walk(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap {walk(it.unmergedRootSemanticsNode)}
    }
    private fun contains(node:SemanticsNode,label:String):Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true || node.children.any {contains(it,label)}

    @Test fun settingsRowIsLocalizedScrollableAndAccessibleAtNarrowLargeFontAndDesktopSizes()=runTest {
        val resources=tables();val dispatcher=StandardTestDispatcher(testScheduler)
        for(language in interfaceLanguages) for((width,scale) in listOf(320 to 1.8f,1000 to 1f)) for(dark in listOf(false,true)) {
            val strings=resources.getValue(language);assertFalse(strings.failed)
            var preferences by mutableStateOf(DiscoveryPreferences())
            val scene=ImageComposeScene(width,700,Density(1f,scale),coroutineContext=dispatcher)
            var frame=0L
            fun draw() {repeat(6){runCurrent();scene.render(++frame*16_666_667L).close()}}
            try {
                scene.setContent {CompositionLocalProvider(LocalAppStrings provides strings) {
                    ApplicationTheme(if(dark)ApplicationThemeMode.DARK else ApplicationThemeMode.LIGHT) {
                        ApplicationSettingsScreen(ApplicationThemeMode.SYSTEM,{},false,discovery=preferences,changeDiscovery={preferences=it})
                    }
                }}
                draw()
                fun row()=nodes(scene).single {it.config.getOrNull(SemanticsProperties.Role)==Role.Switch && contains(it,strings[UiText.SOURCE_SHOW_CATALOG_ONLY])}
                assertEquals(ToggleableState.Off,row().config[SemanticsProperties.ToggleableState])
                assertTrue(row().layoutInfo.height>=48)
                assertTrue(contains(row(),strings[UiText.SOURCE_SHOW_CATALOG_ONLY_DESCRIPTION]))
                assertTrue(nodes(scene).any {it.config.contains(SemanticsProperties.VerticalScrollAxisRange)})
                assertTrue(row().config[SemanticsActions.OnClick].action!!.invoke());draw()
                assertTrue(preferences.showCatalogOnlySources)
                assertEquals(ToggleableState.On,row().config[SemanticsProperties.ToggleableState])
                assertTrue(row().config[SemanticsActions.OnClick].action!!.invoke());draw()
                assertFalse(preferences.showCatalogOnlySources)
            } finally {scene.close()}
        }
    }

    @Test fun realSearchFiltersReactToVisibilityAndLocaleWhileHiddenSavedBooksRemainInLibrary()=runTest {
        val resources=tables();val dispatcher=StandardTestDispatcher(testScheduler)
        val readable=CatalogFixture(SourceId("new-reader"));val catalogOnly=CatalogFixture(SourceId("new-catalog"))
        val data=FakeCollections();val saved=catalogOnly.entry("already-saved").publication
        data.saved[saved.id]=LibraryEntry(PublicationSnapshot.from(saved),1)
        val owner=ApplicationSources(listOf(SourceOption("Reader",readable,textReadingEnabled=true),SourceOption("Catalog",catalogOnly)),
            collections=ApplicationCollections(data.library,data.history,dispatcher),sessionDispatcher=dispatcher) {}
        val app=owner.applicationSession();val scene=ImageComposeScene(390,800,Density(1f),coroutineContext=dispatcher)
        var language by mutableStateOf("en");var library by mutableStateOf(false);var frame=0L
        val grid=LazyGridState();val viewport=DiscoverySearchViewport()
        fun draw() {repeat(8){runCurrent();scene.render(++frame*16_666_667L).close()}}
        fun click(label:String) {
            val node=nodes(scene).last {it.config.contains(SemanticsActions.OnClick) && contains(it,label)}
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());draw()
        }
        try {
            app.navigate(Destination.SEARCH);app.discovery.edit("book");advanceTimeBy(350)
            scene.setContent {CompositionLocalProvider(LocalAppStrings provides resources.getValue(language)) {
                ApplicationTheme(ApplicationThemeMode.LIGHT) {
                    if(library) CollectionScreen(Destination.LIBRARY,app,grid) else UnifiedSearchScreen(app,grid,viewport)
                }
            }}
            draw();assertEquals(1,readable.calls);assertEquals(0,catalogOnly.calls)
            click(resources.getValue(language)[UiText.UI_SOURCES_FILTERS])
            for(code in interfaceLanguages) {
                language=code;draw()
                assertFalse(nodes(scene).any {contains(it,resources.getValue(code).format(UiText.SOURCE_CATALOG_ONLY_NAME,"Catalog"))})
                app.discovery.catalogOnlyVisibility(true);draw()
                assertTrue(nodes(scene).any {contains(it,resources.getValue(code).format(UiText.SOURCE_CATALOG_ONLY_NAME,"Catalog"))})
                app.discovery.catalogOnlyVisibility(false);draw()
                assertEquals(listOf(readable.id),app.discovery.state.value.entries.map {it.publication.id.sourceId})
            }
            assertEquals(1,readable.calls);assertEquals(1,catalogOnly.calls)
            library=true;app.collections.ensureLibrary();draw()
            assertTrue(nodes(scene).any {contains(it,saved.title)})
            assertEquals(1,data.saved.size);assertTrue(data.opened.isEmpty())
        } finally {scene.close();owner.close();advanceUntilIdle();owner.awaitProgressClosed()}
    }
}
