// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.collections.*
import org.infinilect.app.imports.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.ui.*
import org.infinilect.core.*
import kotlin.test.*

/** Shared Android-sized and Desktop-sized layouts in a real headless Compose scene.
 * This is UI/semantics evidence, not physical Android or native Desktop acceptance. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ApplicationInterfaceTest {
    private class Source : PublicationSource {
        override val id = SourceId("original-ui-fixture")
        val books = List(30) { i ->
            val id = PublicationId(id, "book-$i")
            Publication(id, "Original publication $i", PublicationType.BOOK, listOf("Fixture author $i"),
                listOf(PublicationResource(id,"text",PublicationFormat.TEXT,"text/plain")), listOf("en"), rights = "Original test material")
        }
        var metadataCalls = 0; var acquisitions = 0; var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun search(query: String, pageToken: String?) = SearchPage(books)
        override suspend fun getPublication(publicationId: PublicationId): Publication? { metadataCalls++; return books.firstOrNull { it.id == publicationId } }
        override suspend fun loadResource(resource: PublicationResource): ResourceContent {
            acquisitions++; gate?.await(); if (fail) error("Private diagnostics")
            return object : ResourceContent {
                val data = "Original text. A reader can return to this passage.".encodeToByteArray(); var at=0
                override val sizeBytes=data.size.toLong()
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (at == data.size) return -1
                    val count = minOf(length,data.size-at); data.copyInto(buffer,offset,at,at+count);at+=count;return count
                }
                override fun close() {}
            }
        }
    }
    private inner class Fixture(val scope: TestScope, width: Int = 360, val height: Int = 640, fontScale: Float = 1f, count: Int = 3) : AutoCloseable {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val source = Source(); val data = FakeCollections()
        val collections = ApplicationCollections(data.library,data.history,dispatcher)
        val settings = ApplicationAppearancePreferences(object : ApplicationAppearanceStore {
            override suspend fun load() = ApplicationThemeMode.SYSTEM
            override suspend fun save(mode: ApplicationThemeMode) = true
        }, dispatcher)
        val saved = mutableListOf<ReadingProgress>()
        val progress = ProgressPersistence(object : ReadingProgressStore {
            override suspend fun get(id: ReadingProgressId) = saved.lastOrNull { it.id==id }
            override suspend fun save(progress: ReadingProgress): Boolean { saved += progress; return true }
            override suspend fun remove(id: ReadingProgressId) = true
        }, dispatcher)
        var picked = 0
        var selectFile = false
        var importGate: CompletableDeferred<Unit>? = null
        var importFailure = false
        val importer = object : LocalPublicationImporter {
            override suspend fun import(selection: LocalFileSelection): Publication {
                importGate?.await(); if (importFailure) error("Do not expose a local path")
                return source.books[0]
            }
            override fun close() {}
            override suspend fun awaitClosed() {}
        }
        // Cancelled selection tests the real application import state without external bytes.
        val picker = object : LocalFilePicker { override suspend fun pick(): LocalFileSelection? { picked++; importGate?.await(); return if (selectFile) LocalFileSelection("original.txt") { error("No bytes required by this controlled importer") } else null } }
        val owner = ApplicationSources(listOf(SourceOption("Original fixtures",source,textReadingEnabled=true)),
            progress=progress, collections=collections, localImports=importer, appearance=settings, sessionDispatcher=dispatcher) {}
        val app = owner.applicationSession()
        var back: (() -> Unit)? = null
        var appAppearance: ReaderAppearance? = null
        var readerAppearance: ReaderAppearance? = null
        val scene = ImageComposeScene(width,height,Density(1f,fontScale),coroutineContext=dispatcher)
        init {
            repeat(count) { val p=PublicationSnapshot.from(source.books[it]);data.saved[p.id]=LibraryEntry(p,1);data.opened[p.id]=HistoryEntry(p,it.toLong()) }
            app.collections.refreshLibrary()
            scene.setContent { App(owner, backHandler={ enabled, callback ->
                DisposableEffect(enabled, callback) { back=if(enabled)callback else null;onDispose {} }
            }, localFilePicker=picker, applicationAppearance={appAppearance=it}, readerAppearance={readerAppearance=it}) }
            pump()
        }
        fun pump() { repeat(12) { scope.runCurrent(); scene.render(scope.testScheduler.currentTime*1_000_000).close() }; scope.runCurrent() }
        fun nodes(): List<SemanticsNode> {
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
        fun has(node: SemanticsNode, label: String): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true || node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true
        fun visible(label: String) = nodes().any {has(it,label)}
        fun click(label: String) {
            fun contains(n: SemanticsNode): Boolean = has(n,label)||n.children.any(::contains)
            // Modal owners consume input before the underlying screen, just like real pointers.
            fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
            val activeNodes=scene.semanticsOwners.lastOrNull()?.let { walk(it.unmergedRootSemanticsNode) }.orEmpty()
            val control=assertNotNull(activeNodes.firstOrNull { !it.config.contains(SemanticsProperties.Disabled) && it.config.getOrNull(SemanticsActions.OnClick)?.action!=null && contains(it) },label)
            assertTrue(control.config[SemanticsActions.OnClick].action!!.invoke());pump()
        }
        fun nav(destination: Destination) = click("Navigate to ${destination.label()}")
        fun reach(index: Int) {
            val scroll=nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action!=null }
            assertTrue(scroll.config[SemanticsActions.ScrollToIndex].action!!.invoke(index));pump()
        }
        override fun close() { scene.close();owner.close();scope.runCurrent() }
    }

    @Test fun compactAndWideNavigationUseSelectedTabsAndRetainSearchState() = runTest {
        for (width in listOf(360,1280)) Fixture(this,width).use { f ->
            f.app.searchSession.value.editQuery("keep query");f.app.searchSession.value.submitSearch();f.pump()
            val search = f.app.searchSession.value;val result=search.search.state.value
            for (destination in applicationDestinations) {
                f.nav(destination);assertEquals(destination,f.app.destination.value)
                val selected=f.nodes().filter {it.config.getOrNull(SemanticsProperties.Role)==Role.Tab && it.config.getOrNull(SemanticsProperties.Selected)==true}
                assertEquals(1,selected.size);assertTrue(f.has(selected.single(),"Navigate to ${destination.label()}"))
                assertSame(search,f.app.searchSession.value);assertSame(result,search.search.state.value);assertEquals("keep query",search.query.value)
            }
        }
    }
    @Test fun responsivePublicationGridAndLargeTextRemainReachable() = runTest {
        for ((width,height,scale) in listOf(Triple(360,640,1f),Triple(360,400,1.6f),Triple(1280,800,1f))) Fixture(this,width,height,scale,count=30).use { f ->
            f.nav(Destination.LIBRARY)
            val scroll=f.nodes().first {it.config.getOrNull(SemanticsActions.ScrollToIndex)!=null}
            assertTrue(scroll.boundsInRoot.height>0)
            f.reach(30)
            val title=f.nodes().first {f.has(it,"Original publication 29")}
            assertTrue(title.boundsInRoot.bottom>0 && title.boundsInRoot.top<height)
            val nav=f.nodes().first { f.has(it,"Navigate to Settings") }
            assertTrue(nav.boundsInRoot.bottom<=height)
        }
    }
    @Test fun emptyStorageFailureAndRetryNeverInventPublicationData() = runTest {
        Fixture(this,count=0).use { f ->
            f.nav(Destination.LIBRARY);assertTrue(f.visible("Your Library is empty"));f.click("Browse Search")
            f.nav(Destination.HISTORY);assertTrue(f.visible("No reading history yet"))
            f.data.fail=true;f.app.collections.refreshHistory();f.pump();assertTrue(f.visible("Local storage is unavailable"))
            f.data.fail=false;f.click("Try again");assertTrue(f.visible("No reading history yet"))
        }
    }
    @Test fun detailsUseKnownMetadataDoNotAcquireAndDismissWithoutLosingScreen() = runTest {
        Fixture(this).use { f ->
            f.nav(Destination.LIBRARY);f.click("Details for Original publication 0")
            assertTrue(f.visible("Publication details"));assertTrue(f.visible("Fixture author 0"))
            assertTrue(f.visible("Format checked when opening"));assertTrue(f.visible("Original test material"))
            assertEquals(0,f.source.metadataCalls);assertEquals(0,f.source.acquisitions)
            f.click("Close");assertEquals(Destination.LIBRARY,f.app.destination.value)
            assertFalse(f.visible("Publication details"))
        }
    }
    @Test fun historyClearConfirmationKeepsLibraryAndProgress() = runTest {
        Fixture(this).use { f ->
            val id=f.source.books[0].id
            val record=ReadingProgress(ReadingProgressId(id,"text",PublicationFormat.TEXT),ReadingLocator.Text(8,10),.8,1)
            f.progress.submit(record);f.pump();f.nav(Destination.HISTORY);f.reach(4);f.click("Clear history")
            assertTrue(f.visible("Clear reading history?"));f.click("Cancel");assertEquals(3,f.data.opened.size)
            f.click("Clear history");f.click("Clear history");assertTrue(f.data.opened.isEmpty());assertEquals(3,f.data.saved.size)
            assertEquals(record,f.saved.last())
        }
    }
    @Test fun appearanceChangesDoNotRecreateSessionAndSettingsBackRestoresSearch() = runTest {
        Fixture(this).use { f ->
            val session=f.app.searchSession.value;f.nav(Destination.SETTINGS);f.click("Dark")
            assertTrue(assertNotNull(f.appAppearance).dark);assertSame(session,f.app.searchSession.value)
            f.click("Light");assertFalse(assertNotNull(f.appAppearance).dark)
            assertNotNull(f.back).invoke();f.pump();assertEquals(Destination.SEARCH,f.app.destination.value)
        }
    }
    @Test fun importBusyCancellationAndPickerCancelKeepNavigationAndSession() = runTest {
        Fixture(this).use { f ->
            val session=f.app.searchSession.value;f.importGate=CompletableDeferred();f.click("Import local file")
            assertTrue(f.visible("Importing…"));assertTrue(f.app.importing.value.busy)
            assertNotNull(f.back).invoke();f.pump();assertFalse(f.app.importing.value.busy);assertSame(session,f.app.searchSession.value)
            f.importGate=null;f.click("Import local file");assertFalse(f.app.importing.value.busy);assertEquals(2,f.picked)
        }
    }
    @Test fun openReaderReturnRestoresDestinationProgressAndGridViewport() = runTest {
        Fixture(this,count=30).use { f ->
            f.nav(Destination.LIBRARY);f.reach(20)
            val before=f.nodes().first {it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null}.config[SemanticsProperties.VerticalScrollAxisRange].value()
            f.app.openSaved(PublicationSnapshot.from(f.source.books[20]));f.pump()
            assertIs<OpenPublicationState.Ready>(f.app.opening.value)
            assertFalse(f.visible("Navigate to Library"));assertEquals(false,f.readerAppearance?.dark)
            val reading=assertNotNull(assertIs<OpenPublicationState.Ready>(f.app.opening.value).reading)
            reading.report(12);f.app.flushProgress();f.pump();val saved=f.saved.lastOrNull()
            f.app.back();f.pump();assertEquals(Destination.LIBRARY,f.app.destination.value)
            assertEquals(before,f.nodes().first {it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null}.config[SemanticsProperties.VerticalScrollAxisRange].value())
            assertEquals(saved,f.saved.lastOrNull());assertNull(f.readerAppearance)
        }
    }
    @Test fun openingErrorHasControlledMessageAndRetryRetainsOrigin() = runTest {
        Fixture(this).use { f ->
            f.nav(Destination.HISTORY);f.source.fail=true;f.app.openSaved(PublicationSnapshot.from(f.source.books[0]));f.pump()
            assertIs<OpenPublicationState.Error>(f.app.opening.value);assertTrue(f.visible("Unable to open"));assertFalse(f.visible("Private diagnostics"))
            f.source.fail=false;f.click("Try again");assertIs<OpenPublicationState.Ready>(f.app.opening.value)
            f.app.back();f.pump();assertEquals(Destination.HISTORY,f.app.destination.value)
        }
    }
    @Test fun desktopKeyboardDestinationsAndEscapeDetailsRemainAccessible() = runTest {
        Fixture(this,1280,800).use { f ->
            f.scene.sendKeyEvent(KeyEvent(Key.Four,KeyEventType.KeyDown,isAltPressed=true));f.pump()
            assertEquals(Destination.SETTINGS,f.app.destination.value)
            f.scene.sendKeyEvent(KeyEvent(Key.One,KeyEventType.KeyDown,isAltPressed=true));f.pump();assertEquals(Destination.LIBRARY,f.app.destination.value)
            f.click("Details for Original publication 0")
            f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));f.pump();assertFalse(f.visible("Publication details"))
        }
    }

    @Test fun importFailureIsVisibleAndRetrySuccessAddsMembershipAndHistory() = runTest {
        Fixture(this,count=0).use { f ->
            f.selectFile=true;f.importFailure=true;f.click("Import local file")
            assertTrue(f.visible("Import could not finish"));assertFalse(f.visible("Do not expose a local path"))
            assertTrue(f.data.saved.isEmpty());assertTrue(f.data.opened.isEmpty())
            f.importFailure=false;f.click("Import local file");assertIs<OpenPublicationState.Ready>(f.app.opening.value)
            assertTrue(f.source.books[0].id in f.data.saved);assertTrue(f.source.books[0].id in f.data.opened)
            f.app.back();f.pump();assertFalse(f.visible("Import could not finish"))
        }
    }
    @Test fun previewLightLibraryAndDarkDesktopUseOnlyOriginalFixtureMaterial() = runTest {
        val directory=System.getenv("INFINILECT_UI_PREVIEW_DIRECTORY")?.let { java.nio.file.Path.of(it) }
        for ((width,name,dark) in listOf(Triple(390,"library-light-compact.png",false),Triple(1280,"library-dark-wide.png",true))) {
            Fixture(this,width,800).use { f ->
                f.nav(Destination.LIBRARY)
                if (dark) { f.settings.change(ApplicationThemeMode.DARK);f.pump() }
                assertTrue(f.visible("Original publication 0"))
                if (directory!=null) {
                    require(directory.isAbsolute && !directory.startsWith(java.nio.file.Path.of("/workspace/INFINILECT")))
                    java.nio.file.Files.createDirectories(directory)
                    f.scene.render(testScheduler.currentTime*1_000_000).use { image ->
                        val bytes=assertNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use { it.bytes }
                        java.nio.file.Files.write(directory.resolve(name),bytes)
                    }
                }
            }
        }
    }

    @Test fun resizingChangesNavigationGeometryWithoutReopeningOrResettingSession() = runTest {
        Fixture(this,1280,800).use { f ->
            f.nav(Destination.LIBRARY);val session=f.app.searchSession.value
            for ((width,height) in listOf(360 to 640,1280 to 800,360 to 280)) {
                f.scene.constraints=androidx.compose.ui.unit.Constraints.fixed(width,height);f.pump()
                val tab=f.nodes().first {f.has(it,"Navigate to Settings")}
                assertTrue(tab.boundsInRoot.height>=48)
                assertTrue(tab.boundsInRoot.bottom<=height)
                assertEquals(Destination.LIBRARY,f.app.destination.value);assertSame(session,f.app.searchSession.value)
                assertEquals(0,f.source.acquisitions);assertEquals(0,f.source.metadataCalls)
            }
        }
    }
}
