// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.*
import org.infinilect.app.ui.*
import org.infinilect.app.discovery.*
import org.infinilect.app.collections.*
import org.infinilect.app.reader.pdf.*
import org.infinilect.core.*
import java.util.Locale
import kotlin.test.*

/** Headless shared UI/semantics at Android/Desktop sizes; not physical Android evidence. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class LocalizationNavigationTest {
    private fun tables() = runBlocking {
        deviceInterfaceLanguage(); val previous=Locale.getDefault()
        try { interfaceLanguages.associateWith { language -> applyInterfaceLanguage(language); loadInterfaceStrings(getSystemResourceEnvironment()) } }
        finally { Locale.setDefault(previous) }
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun click(scene: ImageComposeScene,label:String, firstRadio:Boolean=false) {
        fun contains(n:SemanticsNode):Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text==label }==true || n.children.any(::contains)
        val candidates=nodes(scene).filter { it.config.contains(SemanticsActions.OnClick) && contains(it) }
        val target=if(firstRadio) candidates.first { it.config.getOrNull(SemanticsProperties.Role)==Role.RadioButton } else candidates.last()
        assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke())
    }
    @Test fun generatedDesktopResourcesResolveEveryKeyAndPluralInAllThreeLanguages() {
        val tables=tables()
        for ((language,strings) in tables) {
            assertEquals(language,strings.localeTag);assertFalse(strings.failed)
            val factory=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            }
            val file=java.nio.file.Path.of("src/commonMain/composeResources",if(language=="en")"values"else"values-$language","strings.xml")
            val entries=factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("string")
            val expected=(0 until entries.length).associate { i ->
                val e=entries.item(i) as org.w3c.dom.Element;e.getAttribute("name") to e.textContent
            }
            for (key in UiText.entries) {
                assertTrue(strings[key].isNotBlank(),"$language:$key")
                assertEquals(expected.getValue(key.name.lowercase()),strings[key],"Runtime resource: $language:$key")
            }
        }
        assertEquals("1 fuente seleccionada",tables.getValue("es").sourceCount(1))
        assertEquals("2 fonts seleccionades",tables.getValue("ca").sourceCount(2))
        assertEquals("2 selected",tables.getValue("en").selectedCount(2))
    }
    @Test fun fiveDestinationsAndAccessibilityReactToLanguageWithoutLosingLocalData() = runTest {
        val resources=tables(); val previous=Locale.getDefault(); val dispatcher=StandardTestDispatcher(testScheduler)
        val data=FakeCollections()
        val source=object:PublicationSource {
            override val id=SourceId("localization-original-fixture")
            override suspend fun search(query:String,pageToken:String?)=SearchPage(emptyList())
            override suspend fun getPublication(publicationId:PublicationId):Publication?=error("No metadata acquisition")
            override suspend fun loadResource(resource:PublicationResource):ResourceContent=error("No book bytes")
        }
        val book=Publication(PublicationId(source.id,"identity"),"Títol original %2\$s 李 📚",PublicationType.BOOK,listOf("Auteur original"))
        val snapshot=PublicationSnapshot.from(book)
        data.saved[book.id]=LibraryEntry(snapshot,1)
        val collections=ApplicationCollections(data.library,data.history,dispatcher)
        val owner=ApplicationSources(listOf(SourceOption("Original source",source)),collections=collections,sessionDispatcher=dispatcher) {}
        val app=owner.applicationSession();var language by mutableStateOf("en");var destination by mutableStateOf(Destination.HOME)
        val scene=ImageComposeScene(390,720,Density(1f),coroutineContext=dispatcher)
        val library=LazyGridState();val home=LazyListState();val search=LazyGridState();val viewport=DiscoverySearchViewport()
        var frame=0L;fun draw(){repeat(8){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
        try {
            scene.setContent { InterfaceLanguage(language,loader={ resources.getValue(Locale.getDefault().language) }) {
                ApplicationTheme(ApplicationThemeMode.LIGHT) {
                    ApplicationShell(destination,false,{destination=it}) {
                        when(destination) {
                            Destination.HOME -> HomeScreen(app,ApplicationAppearanceState(loaded=true,profile=LocalProfile("Nom original","ES",language,true)),emptyList(),null,home,null,{})
                            Destination.LIBRARY,Destination.HISTORY -> CollectionScreen(destination,app,library)
                            Destination.SEARCH -> UnifiedSearchScreen(app,search,viewport)
                            Destination.SETTINGS -> ApplicationSettingsScreen(ApplicationThemeMode.LIGHT,{},false)
                        }
                    }
                }
            } }
            for(code in listOf("en","es","ca","en")) {
                language=code;draw()
                for(d in Destination.entries) {
                    destination=d;app.navigate(d);draw()
                    val key=when(d){Destination.HOME->UiText.HOME;Destination.LIBRARY->UiText.LIBRARY;Destination.HISTORY->UiText.HISTORY;Destination.SEARCH->UiText.SEARCH;Destination.SETTINGS->UiText.SETTINGS}
                    assertTrue(resources.getValue(code)[key] in texts(scene),"$code:$d")
                    assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(resources.getValue(code).format(UiText.NAVIGATE_TO,resources.getValue(code)[key]))==true })
                }
                destination=Destination.LIBRARY;draw()
                assertTrue(book.title in texts(scene));assertEquals(snapshot,data.saved[book.id]?.publication)
                assertEquals(0,library.firstVisibleItemIndex);assertTrue(data.opened.isEmpty())
            }
        } finally {scene.close();owner.close();runCurrent();Locale.setDefault(previous)}
    }
    @Test fun openDetailsUpdatesLabelsButKeepsExpandedSynopsisAndOriginalMetadata() = runTest {
        val resources=tables();val previous=Locale.getDefault();val dispatcher=StandardTestDispatcher(testScheduler)
        var language by mutableStateOf("en");var mounted=0
        val book=Publication(PublicationId(SourceId("original"),"book"),"Títol original 李 %1\$s",PublicationType.BOOK,listOf("Auteur original"),rights="Original rights")
        val synopsis=("Original paragraph unchanged. ".repeat(100))
        val scene=ImageComposeScene(320,480,Density(1f,1.6f),coroutineContext=dispatcher)
        var frame=0L;fun draw(){repeat(10){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
        try {
            scene.setContent {InterfaceLanguage(language,loader={resources.getValue(Locale.getDefault().language)}) {
                DisposableEffect(Unit){mounted++;onDispose{}}
                MaterialTheme { PublicationDetails(book,"Original source",emptyList(),null,{},suppliedSynopsis=synopsis) {Text(LocalAppStrings.current[UiText.CONTINUE_READING])} }
            }}
            draw();click(scene,resources.getValue("en")[UiText.UI_READ_MORE]);draw()
            for(code in listOf("es","ca","en")) {
                language=code;draw();val strings=resources.getValue(code)
                assertTrue(strings[UiText.UI_SYNOPSIS] in texts(scene));assertTrue(strings[UiText.UI_SHOW_LESS] in texts(scene))
                assertTrue(book.title in texts(scene));assertTrue("Auteur original" in texts(scene));assertTrue(synopsis in texts(scene))
                val close=nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && it.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any {it.text==strings[UiText.CLOSE]}==true } }
                assertTrue(close.boundsInRoot.top>=0 && close.boundsInRoot.bottom<=480)
            }
            assertEquals(1,mounted)
        }finally{scene.close();Locale.setDefault(previous)}
    }
    @Test fun settingsLanguageChoiceUpdatesAnOpenPanelAndPersistsIndependentReadingPreference() = runTest {
        val resources=tables();val previous=Locale.getDefault();val dispatcher=StandardTestDispatcher(testScheduler)
        var stored=ApplicationPreferences(profile=LocalProfile("Nom original","ES","en",true),readingLanguage="es")
        val preferences=ApplicationAppearancePreferences(object:ApplicationAppearanceStore {
            override suspend fun load()=stored.mode
            override suspend fun save(mode:ApplicationThemeMode)=true
            override suspend fun loadPreferences()=stored
            override suspend fun savePreferences(value:ApplicationPreferences):Boolean {stored=value;return true}
        },dispatcher)
        val scene=ImageComposeScene(360,520,Density(1f,1.5f),coroutineContext=dispatcher)
        var frame=0L;fun draw(){repeat(12){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
        try {
            scene.setContent {
                val state by preferences.state.collectAsState()
                InterfaceLanguage(state.profile.interfaceLanguage,loader={resources.getValue(Locale.getDefault().language)}) {
                    MaterialTheme {LanguageTranslationPanel(preferences,null,{})}
                }
            }
            draw();click(scene,resources.getValue("en")[UiText.SPANISH],firstRadio=true);draw()
            assertEquals("es",stored.profile.interfaceLanguage);assertEquals("es",stored.readingLanguage)
            assertTrue(resources.getValue("es")[UiText.INTERFACE_LANGUAGE] in texts(scene))
            assertTrue(resources.getValue("es")[UiText.DONE] in texts(scene))
            click(scene,resources.getValue("es")[UiText.CATALAN],firstRadio=true);draw()
            assertEquals("ca",stored.profile.interfaceLanguage);assertEquals("es",stored.readingLanguage)
            assertEquals("Nom original",stored.profile.displayName);assertEquals("ES",stored.profile.countryCode)
            assertTrue(resources.getValue("ca")[UiText.INTERFACE_LANGUAGE] in texts(scene))
            assertTrue(resources.getValue("ca")[UiText.DONE] in texts(scene))
        } finally {scene.close();preferences.close();runCurrent();preferences.awaitClosed();Locale.setDefault(previous)}
    }

    @Test fun pdfLocalizedActionsRemainVisibleOnNarrowScreensWithLargeFontsAndKeepThePage() = runTest {
        val resources=tables();val dispatcher=StandardTestDispatcher(testScheduler)
        val document=TestPdfDocument(12);val reader=PdfReaderController(document,backgroundScope)
        val scene=ImageComposeScene(320,520,Density(1f,1.8f),coroutineContext=dispatcher)
        var language by mutableStateOf("en");var frame=0L
        fun draw(){repeat(8){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
        try {
            reader.initialize(null);reader.navigate(6);runCurrent()
            val initial=reader.state.value
            scene.setContent {CompositionLocalProvider(LocalAppStrings provides resources.getValue(language)) {
                MaterialTheme {PdfReader(reader,false,{},LocalAppStrings.current[UiText.BACK_RESULTS])}
            }}
            for(code in interfaceLanguages) {
                language=code;draw()
                for(key in listOf(UiText.BACK_RESULTS,UiText.PREVIOUS,UiText.NEXT)) {
                    val label=resources.getValue(code)[key]
                    val text=nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true }
                    val bounds=text.boundsInRoot
                    assertTrue(bounds.width>0f && bounds.height>0f && bounds.left>=0 && bounds.right<=320 && bounds.bottom<=520,"$code:$label:$bounds")
                }
                assertEquals(6,reader.state.value.index);assertEquals(initial.ticket,reader.state.value.ticket)
                assertSame((initial.frame as PdfFrame.Ready).raster,(reader.state.value.frame as PdfFrame.Ready).raster)
            }
        } finally {scene.close();reader.close();runCurrent()}
    }

}
