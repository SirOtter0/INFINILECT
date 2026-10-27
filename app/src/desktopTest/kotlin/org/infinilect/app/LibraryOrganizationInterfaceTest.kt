// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.*
import org.infinilect.app.collections.*
import org.infinilect.app.ui.*
import org.infinilect.core.*
import java.util.Locale
import kotlin.test.*

/** Real shared screen, dialogs, semantic actions and retained application session.
 * Headless host evidence; Android touch/TalkBack still require physical acceptance. */
@OptIn(ExperimentalComposeUiApi::class,InternalComposeUiApi::class,ExperimentalCoroutinesApi::class)
class LibraryOrganizationInterfaceTest {
    private fun tables() = runBlocking {
        deviceInterfaceLanguage();val previous=Locale.getDefault()
        try { interfaceLanguages.associateWith { language -> applyInterfaceLanguage(language);loadInterfaceStrings(getSystemResourceEnvironment()) } }
        finally {Locale.setDefault(previous)}
    }
    private inner class Fixture(val test: TestScope,width: Int=360,fontScale: Float=1f,val strings: LocalizedStrings=LocalizedStrings(),dark: Boolean=false,val height: Int=720,count: Int=3): AutoCloseable {
        val dispatcher=StandardTestDispatcher(test.testScheduler)
        val data=FakeCollections()
        val source=object:PublicationSource {
            override val id=SourceId("original-organization")
            override suspend fun search(query:String,pageToken:String?)=error("Library must not search remotely")
            override suspend fun getPublication(publicationId:PublicationId):Publication?=error("Library must not reload metadata")
            override suspend fun loadResource(resource:PublicationResource):ResourceContent=error("Library must not acquire bytes")
        }
        val books=List(count) { index -> PublicationSnapshot(PublicationId(source.id,"$index"),if(index==0) "Ciència original 📚" else "Original $index",PublicationType.BOOK,listOf("Author $index")) }
        val collections=ApplicationCollections(data.library,data.history,dispatcher)
        val owner=ApplicationSources(listOf(SourceOption("Original",source)),collections=collections,sessionDispatcher=dispatcher) {}
        val app=owner.applicationSession()
        val position=LazyGridState()
        var destination by mutableStateOf(Destination.LIBRARY)
        val scene=ImageComposeScene(width,height,Density(1f,fontScale),coroutineContext=dispatcher)
        var frame=0L
        init {
            books.forEachIndexed { index,p -> data.saved[p.id]=LibraryEntry(p,index.toLong()) }
            app.collections.refreshLibrary();app.navigate(destination)
            scene.setContent {
                CompositionLocalProvider(LocalAppStrings provides strings,LocalInterfaceLanguage provides strings.localeTag) {
                    ApplicationTheme(if(dark) ApplicationThemeMode.DARK else ApplicationThemeMode.LIGHT) {
                        if(destination==Destination.LIBRARY) CollectionScreen(destination,app,position) else MaterialTheme { }
                    }
                }
            };draw()
        }
        fun draw() {repeat(8){test.runCurrent();scene.render(++frame*16_666_667L).close()};test.runCurrent()}
        fun nodes():List<SemanticsNode> {
            fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap {walk(it.unmergedRootSemanticsNode)}
        }
        fun has(n:SemanticsNode,label:String):Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true || n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true
        fun visible(label:String)=nodes().any {has(it,label)}
        fun click(label:String) {
            fun contains(n:SemanticsNode):Boolean = has(n,label)||n.children.any(::contains)
            val control=nodes().last {it.config.getOrNull(SemanticsActions.OnClick)?.action!=null && !it.config.contains(SemanticsProperties.Disabled) && contains(it)}
            assertTrue(control.config[SemanticsActions.OnClick].action!!.invoke());draw();test.advanceTimeBy(200);draw()
        }
        fun search(value:String) {
            val edit=nodes().single {it.config.getOrNull(SemanticsActions.SetText)?.action!=null}
            assertTrue(edit.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)));draw()
        }
        fun preview(name:String) {
            System.getenv("INFINILECT_LIBRARY_PREVIEWS")?.let { directory ->
                val path=java.nio.file.Path.of(directory);require(path.isAbsolute);java.nio.file.Files.createDirectories(path)
                scene.render(++frame*16_666_667L).use { image -> image.encodeToData()!!.use { java.nio.file.Files.write(path.resolve(name),it.bytes) } }
            }
        }
        override fun close() {scene.close();owner.close();test.runCurrent()}
    }
    @Test fun integratedSearchClearFiltersDetailsOrganizationAndTagsHaveNoRemoteWork()=runTest {
        Fixture(this).use {f ->
            val reads=f.data.libraryReads
            f.search("CIENCIA");assertTrue(f.visible(f.books[0].title));assertFalse(f.visible(f.books[1].title));assertEquals(reads,f.data.libraryReads)
            f.click(f.strings[UiText.LIB_CLEAR_SEARCH]);assertEquals("",f.app.libraryBrowser.query.value.text)
            f.click(f.books[0].title);f.click(f.strings[UiText.LIB_ORGANIZE]);f.click(f.strings[UiText.LIB_PAUSED]);f.click(f.strings[UiText.LIB_FAVORITE]);f.click(f.strings[UiText.LIB_APPLY])
            assertEquals(LibraryReadingState.PAUSED,f.data.saved.getValue(f.books[0].id).readingState);assertTrue(f.data.saved.getValue(f.books[0].id).favorite)
            f.click(f.strings[UiText.LIB_MORE]);f.click(f.strings[UiText.LIB_MANAGE_TAGS])
            val nameField=f.nodes().last {it.config.getOrNull(SemanticsActions.SetText)?.action!=null}
            assertTrue(nameField.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Fantasía")));f.draw()
            f.click(f.strings[UiText.LIB_CREATE_TAG]);assertEquals(1,f.data.tagValues.size);f.click(f.strings[UiText.CLOSE])
            f.click(f.books[0].title);f.click(f.strings[UiText.LIB_ORGANIZE]);f.click("Fantasía");f.click(f.strings[UiText.LIB_APPLY])
            assertEquals(listOf("Fantasía"),f.data.saved.getValue(f.books[0].id).tags.map {it.name})
            f.click(f.strings[UiText.LIB_FILTERS]);f.click(f.strings[UiText.LIB_ONLY_FAVORITES]);f.click(f.strings[UiText.CLOSE])
            assertEquals(1,f.app.libraryBrowser.result.value.size)
            f.destination=Destination.SETTINGS;f.app.navigate(f.destination);f.draw();f.destination=Destination.LIBRARY;f.app.navigate(f.destination);f.draw()
            assertTrue(f.app.libraryBrowser.query.value.favorites);assertEquals(1,f.app.libraryBrowser.result.value.size)
            f.search("absent");assertTrue(f.visible(f.strings[UiText.LIB_NO_MATCHES]));assertFalse(f.visible(f.strings[UiText.UI_YOUR_LIBRARY_IS_EMPTY]))
            assertEquals(3,f.data.saved.size);assertTrue(f.data.opened.isEmpty())
        }
    }
    @Test fun labelsAndFixedDialogActionsFitThreeLanguagesNarrowScreensLargeFontsAndDesktop()=runTest {
        val resources=tables()
        for(language in listOf("en","es","ca")) for((width,height,scale) in listOf(Triple(320,720,1f),Triple(360,720,1.5f),Triple(320,720,1.8f),Triple(360,400,1.6f),Triple(1280,720,1f))) {
            val strings=resources.getValue(language)
            Fixture(this,width,scale,strings,dark=scale>1f,height=height).use { f ->
                assertTrue(f.visible(strings[UiText.LIB_SEARCH]));assertTrue(f.visible(strings[UiText.LIB_FILTERS]))
                f.app.collections.select(f.books[0].id);f.draw();f.click(strings[UiText.LIB_ORGANIZE])
                val actions=f.nodes().filter {it.config.getOrNull(SemanticsActions.OnClick)?.action!=null && it.children.any {child -> f.has(child,strings[UiText.LIB_APPLY])}}
                val apply=assertNotNull(actions.lastOrNull());assertTrue(apply.boundsInRoot.width>0 && apply.boundsInRoot.height>=48)
                assertTrue(apply.boundsInRoot.bottom<=height && apply.boundsInRoot.right<=width)
                val close=f.nodes().last {f.has(it,strings[UiText.CLOSE])};assertTrue(close.boundsInRoot.bottom<=height)
                f.preview("organization-$language-$width-$height-$scale.png")
                f.click(strings[UiText.CLOSE]);assertEquals(3,f.data.saved.size)
            }
        }
    }
    @Test fun deletingATagClearsOnlyItsFilterAndObsoletePendingAssignments()=runTest {
        Fixture(this).use {f ->
            f.app.collections.createTag("Temporary");f.draw();val tag=f.app.collections.tags.value.single()
            f.app.libraryBrowser.change(LibraryQuery(text="Original",tags=setOf(tag.id),favorites=true));f.draw()
            f.app.collections.deleteTag(tag.id);f.draw()
            assertTrue(f.app.libraryBrowser.query.value.tags.isEmpty());assertEquals("Original",f.app.libraryBrowser.query.value.text);assertTrue(f.app.libraryBrowser.query.value.favorites)
            f.app.libraryBrowser.change(LibraryQuery());f.draw();f.app.collections.createTag("Temporary");f.draw()
            val another=f.app.collections.tags.value.single()
            f.app.collections.select(f.books[0].id);f.draw();f.click(f.strings[UiText.LIB_ORGANIZE]);f.click("Temporary")
            f.app.collections.deleteTag(another.id);f.draw();f.click(f.strings[UiText.LIB_APPLY])
            assertEquals(true,f.app.collections.organizationResult.value);assertTrue(f.data.saved.getValue(f.books[0].id).tags.isEmpty())
        }
    }
    @Test fun fiveThousandLocalEntriesRemainLazyThroughScrollSearchAndTabRestoration()=runTest {
        Fixture(this,count=5000).use {f ->
            val reads=f.data.libraryReads
            for(index in listOf(0,100,1000,4998,1000,100,0)) {
                f.position.scrollToItem(index);f.draw()
                val tiles=f.nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label -> label.startsWith("Cover for ") } == true }
                assertTrue(tiles in 1..29,"Only viewport covers should be composed")
            }
            f.search("Original 4999");assertEquals(1,f.app.libraryBrowser.result.value.size)
            f.destination=Destination.SETTINGS;f.app.navigate(f.destination);f.draw()
            f.destination=Destination.LIBRARY;f.app.navigate(f.destination);f.draw()
            assertEquals("Original 4999",f.app.libraryBrowser.query.value.text)
            assertEquals(1,f.app.libraryBrowser.result.value.size);assertEquals(reads,f.data.libraryReads)
            assertEquals(5000,f.data.saved.size);assertTrue(f.data.opened.isEmpty())
        }
    }
    @Test fun escapeAndKeyboardCloseDismissOnlyThePanelWithoutChangingOrganization()=runTest {
        Fixture(this).use {f ->
            f.click(f.strings[UiText.LIB_FILTERS]);assertTrue(f.visible(f.strings[UiText.LIB_ALL_STATES]))
            f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp));f.draw()
            assertFalse(f.visible(f.strings[UiText.LIB_ALL_STATES]));assertEquals(Destination.LIBRARY,f.app.destination.value)
            f.app.collections.selectAll();f.draw();f.click(f.strings[UiText.LIB_ORGANIZE]);f.click(f.strings[UiText.LIB_PAUSED])
            f.scene.sendKeyEvent(KeyEvent(Key.Enter,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Enter,KeyEventType.KeyUp));f.draw()
            assertTrue(f.data.saved.values.all {it.readingState==LibraryReadingState.NOT_STARTED})
            assertFalse(f.visible(f.strings[UiText.LIB_APPLY]))
            f.click(f.strings[UiText.LIB_MORE]);assertTrue(f.visible(f.strings[UiText.UI_REMOVE_FROM_LIBRARY]))
            f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp));f.draw()
            advanceTimeBy(250);f.draw() // Material popup exit transition, not a real-time sleep.
            assertEquals(3,f.app.collections.selection.value.size)
            assertFalse(f.visible(f.strings[UiText.UI_REMOVE_FROM_LIBRARY]))
            f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp));f.draw()
            assertTrue(f.app.collections.selection.value.isEmpty())
        }
    }
    @Test fun batchOrganizationKeepsUntouchedFieldsAndFailuresAreVisible()=runTest {
        Fixture(this).use {f ->
            f.app.collections.selectAll();f.draw();f.click(f.strings[UiText.LIB_ORGANIZE]);f.click(f.strings[UiText.LIB_COMPLETED]);f.click(f.strings[UiText.LIB_APPLY])
            assertTrue(f.data.saved.values.all {it.readingState==LibraryReadingState.COMPLETED && !it.favorite})
            f.data.fail=true;f.app.collections.organize(f.books.map{it.id}.toSet(),LibraryOrganizationChange(favorite=true));f.draw()
            assertTrue(f.visible(f.strings[UiText.LIB_SAVE_ERROR]));assertTrue(f.data.saved.values.none {it.favorite})
        }
    }
    @Test fun quickChipsShareAdvancedFiltersAndSortWithoutReloadingLibrary()=runTest {
        Fixture(this).use {f ->
            val reads=f.data.libraryReads
            f.click(f.strings[UiText.LIB_READING]);assertEquals(LibraryReadingState.READING,f.app.libraryBrowser.query.value.state)
            f.click(f.strings[UiText.LIB_FILTERS])
            fun includes(n:SemanticsNode):Boolean=f.has(n,f.strings[UiText.LIB_READING]) || n.children.any(::includes)
            val choice=f.nodes().last {includes(it) && it.config.getOrNull(SemanticsProperties.Selected)==true}
            assertTrue(choice.boundsInRoot.width>0)
            f.click(f.strings[UiText.LIB_PAUSED]);f.click(f.strings[UiText.CLOSE])
            assertEquals(LibraryReadingState.PAUSED,f.app.libraryBrowser.query.value.state)
            f.click(f.strings[UiText.LIB_FAVORITES]);assertTrue(f.app.libraryBrowser.query.value.favorites)
            f.click(f.strings[UiText.LIB_ALL]);assertFalse(f.app.libraryBrowser.query.value.filtered)
            f.click(f.strings[UiText.LIB_SORT]);f.click(f.strings[UiText.LIB_TITLE]);f.click(f.strings[UiText.LIB_ASCENDING]);f.click(f.strings[UiText.CLOSE])
            assertEquals(LibrarySort.TITLE,f.app.libraryBrowser.query.value.sort);assertFalse(f.app.libraryBrowser.query.value.descending)
            assertEquals(reads,f.data.libraryReads);assertTrue(f.data.opened.isEmpty())
        }
    }
    @Test fun detailsPrimaryActionAndOverflowPreserveConfirmationAndQuickFavorite()=runTest {
        Fixture(this).use {f ->
            f.click(f.books[0].title)
            assertFalse(f.visible(f.strings[UiText.UI_REMOVE_FROM_LIBRARY]))
            f.click(f.strings[UiText.LIB_FAVORITE]);assertTrue(f.data.saved.getValue(f.books[0].id).favorite)
            assertTrue(f.visible(f.strings[UiText.UI_START_READING]))
            f.preview("details-dark.png")
            f.click(f.strings[UiText.LIB_MORE]);f.click(f.strings[UiText.UI_REMOVE_FROM_LIBRARY])
            assertTrue(f.visible(f.strings[UiText.UI_REMOVE_FROM_LIBRARY_CONFIRM]));f.click(f.strings[UiText.CANCEL])
            assertEquals(3,f.data.saved.size);assertTrue(f.data.opened.isEmpty())
        }
    }
    @Test fun savedConfirmationIsAnOverlayAndExpiresWithoutMovingTheGrid()=runTest {
        Fixture(this,count=30).use {f ->
            val before=f.position.firstVisibleItemIndex to f.position.firstVisibleItemScrollOffset
            f.app.collections.organize(setOf(f.books[0].id),LibraryOrganizationChange(favorite=true));f.draw()
            assertTrue(f.visible(f.strings[UiText.LIB_SAVED]))
            assertEquals(before,f.position.firstVisibleItemIndex to f.position.firstVisibleItemScrollOffset)
            f.preview("saved-overlay.png")
            advanceTimeBy(6000);f.draw();assertFalse(f.visible(f.strings[UiText.LIB_SAVED]))
            assertNull(f.app.collections.organizationResult.value)
            f.destination=Destination.HISTORY;f.app.navigate(f.destination);f.draw();f.destination=Destination.LIBRARY;f.app.navigate(f.destination);f.draw()
            assertFalse(f.visible(f.strings[UiText.LIB_SAVED]));assertTrue(f.data.saved.getValue(f.books[0].id).favorite)
        }
    }
    @Test fun compactScreensAndOrganizationPreviewsUseRealUiInBothThemesAndThreeLanguages()=runTest {
        val resources=tables()
        for(language in listOf("es","en","ca")) for(dark in listOf(false,true)) {
            Fixture(this,360,1f,resources.getValue(language),dark,height=800,count=12).use {f ->
                f.app.collections.createTag("Estudi 📚");f.draw()
                val tag=f.app.collections.tags.value.single()
                f.app.collections.organize(setOf(f.books[8].id,f.books[9].id),LibraryOrganizationChange(LibraryReadingState.COMPLETED,true,addTags=setOf(tag.id)));f.draw()
                advanceTimeBy(6000);f.draw()
                assertEquals(3,libraryColumns(360f))
                val first=f.nodes().first {it.config.getOrNull(SemanticsProperties.ContentDescription)?.any {it.startsWith(f.strings.format(UiText.UI_COVER_FOR_1_S,""))}==true}
                assertTrue(first.boundsInRoot.width<120)
                f.preview("library-$language-$dark.png")
                f.click(f.strings[UiText.LIB_FILTERS]);f.preview("filters-$language-$dark.png");f.click(f.strings[UiText.CLOSE])
                f.click(f.strings[UiText.LIB_SORT]);f.preview("sort-$language-$dark.png");f.click(f.strings[UiText.CLOSE])
                f.click(f.strings[UiText.LIB_MORE]);f.click(f.strings[UiText.LIB_MANAGE_TAGS]);f.preview("tags-$language-$dark.png");f.click(f.strings[UiText.CLOSE])
                f.click(f.books.last().title);f.preview("details-$language-$dark.png");f.click(f.strings[UiText.LIB_ORGANIZE]);f.preview("organize-$language-$dark.png");f.click(f.strings[UiText.CLOSE])
                f.app.collections.select(f.books.last().id);f.app.collections.toggleSelection(f.books[f.books.lastIndex-1].id);f.draw()
                f.preview("selection-$language-$dark.png")
                assertTrue(f.visible(f.strings.selectedCount(2)))
                f.click(f.strings[UiText.LIB_FAVORITE]);assertTrue(f.data.saved.getValue(f.books.last().id).favorite)
            }
        }
    }

}
