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
    private class Source(count: Int = 30) : PublicationSource {
        override val id = SourceId("original-ui-fixture")
        val books = List(count) { i ->
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
    private inner class Fixture(val scope: TestScope, width: Int = 360, val height: Int = 640, fontScale: Float = 1f, count: Int = 3, artwork: Boolean = false, firstRun: Boolean = false) : AutoCloseable {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val source = Source(maxOf(30, count)); val data = FakeCollections()
        val collections = ApplicationCollections(data.library,data.history,dispatcher)
        var preferenceValue = ApplicationPreferences(profile = LocalProfile(setupHandled = !firstRun))
        val settings = ApplicationAppearancePreferences(object : ApplicationAppearanceStore {
            override suspend fun load() = ApplicationThemeMode.SYSTEM
            override suspend fun save(mode: ApplicationThemeMode): Boolean { preferenceValue = preferenceValue.copy(mode=mode); return true }
            override suspend fun loadPreferences() = preferenceValue
            override suspend fun savePreferences(value: ApplicationPreferences): Boolean { preferenceValue=value; return true }
        }, dispatcher)
        val saved = mutableListOf<ReadingProgress>()
        val progress = ProgressPersistence(object : ReadingProgressStore, org.infinilect.app.progress.PublicationProgressLookup {
            override suspend fun get(id: ReadingProgressId) = saved.lastOrNull { it.id==id }
            override suspend fun save(progress: ReadingProgress): Boolean { saved += progress; return true }
            override suspend fun remove(id: ReadingProgressId) = true
            override suspend fun recentPublications(ids: List<PublicationId>) = saved.filter { it.id.publicationId in ids }.groupBy { it.id.publicationId }.map { (_,values) -> values.maxBy { it.updatedAtEpochMillis } }
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
        var coverLoads = 0
        val covers = org.infinilect.app.covers.PublicationCovers({ id ->
            coverLoads++
            val i=id.localId.substringAfterLast('-').toInt()
            if (i%4==3) org.infinilect.app.covers.CoverArtwork(PublicationFormat.TEXT)
            else org.infinilect.app.covers.CoverArtwork(if(i%2==0) PublicationFormat.EPUB else PublicationFormat.CBZ,
                org.infinilect.app.media.Raster(192,288,IntArray(192*288){0xff294d55.toInt()}))
        },dispatcher,convert={ raster ->
            val image=androidx.compose.ui.graphics.ImageBitmap(raster.width,raster.height)
            val canvas=androidx.compose.ui.graphics.Canvas(image)
            val paint=androidx.compose.ui.graphics.Paint()
            paint.color=androidx.compose.ui.graphics.Color(0xff263f55)
            canvas.drawRect(0f,0f,192f,288f,paint)
            paint.color=androidx.compose.ui.graphics.Color(0xffdab483)
            canvas.drawCircle(androidx.compose.ui.geometry.Offset(132f,72f),42f,paint)
            paint.color=androidx.compose.ui.graphics.Color(0xff588d89)
            for(i in 0..5) canvas.drawRect(0f,120f+i*22,192f-i*24,132f+i*22,paint)
            image
        })
        val owner = ApplicationSources(listOf(SourceOption("Original fixtures",source,textReadingEnabled=true)),
            progress=progress, collections=collections, localImports=importer, appearance=settings, covers=if(artwork) covers else null, sessionDispatcher=dispatcher) {}
        val app = owner.applicationSession()
        var back: (() -> Unit)? = null
        var appAppearance: ReaderAppearance? = null
        var readerAppearance: ReaderAppearance? = null
        val scene = ImageComposeScene(width,height,Density(1f,fontScale),coroutineContext=dispatcher)
        init {
            repeat(count) { val p=PublicationSnapshot.from(source.books[it]);data.saved[p.id]=LibraryEntry(p,1);data.opened[p.id]=HistoryEntry(p,if(artwork) 1_790_000_000_000L+it*60_000 else it.toLong()) }
            if(artwork) source.books.take(count).takeLast(2).forEach { book ->
                progress.submit(ReadingProgress(ReadingProgressId(book.id,"text",PublicationFormat.TEXT),ReadingLocator.Text(8,10),.8,1))
            }
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
            val control=assertNotNull(activeNodes.lastOrNull { !it.config.contains(SemanticsProperties.Disabled) && it.config.getOrNull(SemanticsActions.OnClick)?.action!=null && contains(it) },label)
            assertTrue(control.config[SemanticsActions.OnClick].action!!.invoke());pump()
            // Advance the controlled clock through Material's menu exit; a fading Popup still
            // owns input above a newly opened Dialog until its animation has finished.
            scope.advanceTimeBy(200);pump()
        }
        fun nav(destination: Destination) = click("Navigate to ${destination.label()}")
        fun reach(index: Int) {
            val scroll=nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action!=null }
            assertTrue(scroll.config[SemanticsActions.ScrollToIndex].action!!.invoke(index));pump()
        }
        override fun close() { scene.close();owner.close();covers.close();scope.runCurrent() }
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
            f.reach(29)
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
            f.nav(Destination.LIBRARY);f.click("Original publication 0")
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
    @Test fun appearanceChangesDoNotRecreateSessionAndSettingsBackRestoresHome() = runTest {
        Fixture(this).use { f ->
            val session=f.app.searchSession.value;f.nav(Destination.SETTINGS);f.click("Dark")
            assertTrue(assertNotNull(f.appAppearance).dark);assertSame(session,f.app.searchSession.value)
            f.click("Light");assertFalse(assertNotNull(f.appAppearance).dark)
            assertNotNull(f.back).invoke();f.pump();assertEquals(Destination.HOME,f.app.destination.value)
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
            f.scene.sendKeyEvent(KeyEvent(Key.Five,KeyEventType.KeyDown,isAltPressed=true));f.pump()
            assertEquals(Destination.SETTINGS,f.app.destination.value)
            f.scene.sendKeyEvent(KeyEvent(Key.Two,KeyEventType.KeyDown,isAltPressed=true));f.pump();assertEquals(Destination.LIBRARY,f.app.destination.value)
            f.click("Original publication 0")
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
            Fixture(this,width,800,count=12,artwork=true).use { f ->
                f.progress.submit(ReadingProgress(ReadingProgressId(f.source.books[0].id,"text",PublicationFormat.TEXT),ReadingLocator.Text(3,10),.3,2));f.pump()
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

    @Test fun coverGridHasTwoPhoneColumnsAndNoPermanentActionRows() = runTest {
        Fixture(this,count=12).use { f ->
            f.nav(Destination.LIBRARY)
            fun tile(i:Int)=f.nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)!=null && it.children.any { n -> f.has(n,"Original publication $i") } }
            val a=tile(0).boundsInRoot;val b=tile(1).boundsInRoot
            assertEquals(a.top,b.top);assertTrue(b.left>=a.right)
            assertEquals(2f/3f,a.width/a.height,.02f)
            assertFalse(f.visible("Open"));assertFalse(f.visible("Remove"));assertFalse(f.visible("Publication details"))
            assertFalse(f.visible("Actions for Original publication 0"));assertFalse(f.visible("EPUB"));assertFalse(f.visible("CBZ"))
        }
    }
    @Test fun primaryCoverTapOpensDetailsAndReadingRequiresExplicitAction() = runTest {
        Fixture(this).use { f ->
            f.nav(Destination.LIBRARY);f.click("Original publication 0")
            assertTrue(f.visible("Publication details"));assertIs<OpenPublicationState.Idle>(f.app.opening.value)
            assertEquals(0,f.source.acquisitions);f.click("Open")
            assertIs<OpenPublicationState.Ready>(f.app.opening.value);assertEquals(1,f.source.acquisitions)
        }
    }
    @Test fun longPressAndAccessibleActionsExposeDetailsWithoutAcquisition() = runTest {
        Fixture(this).use { f ->
            f.nav(Destination.LIBRARY)
            val tile=f.nodes().first {it.config.getOrNull(SemanticsActions.OnLongClick)!=null}
            assertTrue(tile.config[SemanticsActions.OnLongClick].action!!.invoke());f.pump()
            assertTrue(f.visible("1 selected"));assertEquals(0,f.source.acquisitions)
            assertEquals(setOf(f.source.books[0].id),f.app.collections.selection.value)
            assertNotNull(f.back).invoke();f.pump();assertEquals(Destination.LIBRARY,f.app.destination.value)
            val action=f.nodes().flatMap {it.config.getOrNull(SemanticsActions.CustomActions).orEmpty()}.first {it.label=="Publication details"}
            assertTrue(action.action());f.pump();assertTrue(f.visible("Publication details"));assertEquals(0,f.source.acquisitions)
        }
    }
    @Test fun libraryRemovalRequiresConfirmationAndKeepsProgressAndImport() = runTest {
        Fixture(this).use { f ->
            val record=ReadingProgress(ReadingProgressId(f.source.books[0].id,"text",PublicationFormat.TEXT),ReadingLocator.Text(8,10),.8,1)
            f.progress.submit(record);f.pump();f.nav(Destination.LIBRARY)
            f.click("Original publication 0");f.click("Remove from Library")
            assertTrue(f.visible("Remove from Library?"));assertEquals(3,f.data.saved.size)
            f.click("Cancel");assertEquals(3,f.data.saved.size)
            f.click("Original publication 0");f.click("Remove from Library");f.click("Remove")
            assertEquals(2,f.data.saved.size);assertEquals(3,f.source.books.take(3).size);assertEquals(record,f.saved.last())
        }
    }
    @Test fun historyRowsKeepNewestFirstAndResumeDirectly() = runTest {
        Fixture(this).use { f ->
            f.nav(Destination.HISTORY)
            val titles=(0..2).map {i->f.nodes().first {f.has(it,"Original publication $i")}.boundsInRoot.top}
            assertTrue(titles[2]<titles[1]);assertTrue(titles[1]<titles[0]);assertFalse(f.visible("Continue reading"))
            f.click("Original publication 2");assertIs<OpenPublicationState.Ready>(f.app.opening.value)
            assertEquals(f.source.books[2].id,assertNotNull(assertIs<OpenPublicationState.Ready>(f.app.opening.value).publication).id)
        }
    }
    @Test fun artworkLoadingIsLazyAndSharedAcrossLibraryHistory() = runTest {
        Fixture(this,count=30,artwork=true).use { f ->
            f.nav(Destination.LIBRARY);val initially=f.coverLoads
            assertTrue(initially in 1..23);assertFalse(f.visible("EPUB"));assertFalse(f.visible("TEXT"))
            f.nav(Destination.HISTORY);assertTrue(f.coverLoads<30)
            assertEquals(0,f.source.acquisitions)
        }
    }
    @Test fun desktopRightClickOpensMenuWithoutOpeningReader() = runTest {
        Fixture(this,1280,800).use {f ->
            f.nav(Destination.LIBRARY)
            val tile=f.nodes().first {it.config.getOrNull(SemanticsActions.OnLongClick)!=null}
            val at=tile.boundsInRoot.center
            f.scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press,at,
                buttons=androidx.compose.ui.input.pointer.PointerButtons(2),button=androidx.compose.ui.input.pointer.PointerButton.Secondary)
            f.scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release,at,
                buttons=androidx.compose.ui.input.pointer.PointerButtons(0),button=androidx.compose.ui.input.pointer.PointerButton.Secondary);f.pump()
            assertTrue(f.visible("Publication details"));assertIs<OpenPublicationState.Idle>(f.app.opening.value)
            f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp));f.pump();advanceTimeBy(200);f.pump()
            assertFalse(f.visible("Publication details"))
        }
    }
    @Test fun desktopKeyboardCanOpenCoverContextMenu() = runTest {
        Fixture(this,1280,800).use {f ->
            f.nav(Destination.LIBRARY)
            repeat(20) {
                if(f.nodes().none {it.config.getOrNull(SemanticsProperties.Focused)==true && it.config.getOrNull(SemanticsActions.OnLongClick)!=null}) {
                    f.scene.sendKeyEvent(KeyEvent(Key.Tab,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Tab,KeyEventType.KeyUp));f.pump()
                }
            }
            assertTrue(f.nodes().any {it.config.getOrNull(SemanticsProperties.Focused)==true && it.config.getOrNull(SemanticsActions.OnLongClick)!=null})
            f.scene.sendKeyEvent(KeyEvent(Key.F10,KeyEventType.KeyDown,isShiftPressed=true));f.pump()
            assertTrue(f.visible("Publication details"));assertEquals(0,f.source.acquisitions)
        }
    }

    @Test fun historyPreviewContainsOnlyOriginalFixtureArtwork() = runTest {
        Fixture(this,360,800,count=8,artwork=true).use {f ->
            val today=java.time.Instant.ofEpochMilli(kotlin.time.Clock.System.now().toEpochMilliseconds()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            f.data.opened.entries.toList().forEachIndexed { i, (id, entry) ->
                val days=(7-i)/2L
                f.data.opened[id]=entry.copy(lastOpenedAtEpochMillis=today.minusDays(days).atTime(12,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()+i)
            }
            f.nav(Destination.HISTORY)
            System.getenv("INFINILECT_UI_PREVIEW_DIRECTORY")?.let { path ->
                val directory=java.nio.file.Path.of(path);require(directory.isAbsolute&&!directory.startsWith(java.nio.file.Path.of("/workspace/INFINILECT")))
                java.nio.file.Files.createDirectories(directory)
                f.scene.render(testScheduler.currentTime*1_000_000).use { image ->
                    val bytes=assertNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use {it.bytes}
                    java.nio.file.Files.write(directory.resolve("history-compact.png"),bytes)
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
    @Test fun longPressSelectionSupportsTapToggleSelectAllAndClearWithoutOpening() = runTest {
        Fixture(this).use { f ->
            f.nav(Destination.LIBRARY)
            val tile=f.nodes().first {it.config.getOrNull(SemanticsActions.OnLongClick)!=null}
            assertTrue(tile.config[SemanticsActions.OnLongClick].action!!.invoke());f.pump()
            assertTrue(f.visible("1 selected"));assertEquals(setOf(f.source.books[0].id),f.app.collections.selection.value)
            f.click("Original publication 1");assertTrue(f.visible("2 selected"))
            f.click("Original publication 0");assertEquals(setOf(f.source.books[1].id),f.app.collections.selection.value)
            f.click("Select all");assertTrue(f.visible("3 selected"))
            assertIs<OpenPublicationState.Idle>(f.app.opening.value);assertEquals(0,f.source.acquisitions)
            assertFalse(f.visible("Mark as read"));assertFalse(f.visible("Mark as unread"))
            f.click("Clear selection");assertTrue(f.app.collections.selection.value.isEmpty());assertFalse(f.visible("3 selected"))
        }
    }
    @Test fun selectionBackAndEscapeKeepGridPositionProgressAndDestination() = runTest {
        Fixture(this,1280,800,count=30).use { f ->
            f.nav(Destination.LIBRARY);f.reach(20)
            fun offset()=f.nodes().first {it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null}.config[SemanticsProperties.VerticalScrollAxisRange].value()
            val before=offset();val records=f.saved.toList()
            fun selectVisible() { assertTrue(f.nodes().first {it.config.getOrNull(SemanticsActions.OnLongClick)!=null}.config[SemanticsActions.OnLongClick].action!!.invoke());f.pump() }
            selectVisible();assertNotNull(f.back).invoke();f.pump()
            assertTrue(f.app.collections.selection.value.isEmpty());assertEquals(Destination.LIBRARY,f.app.destination.value);assertEquals(before,offset())
            selectVisible();f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp));f.pump()
            assertTrue(f.app.collections.selection.value.isEmpty());assertEquals(before,offset());assertEquals(records,f.saved)
        }
    }
    @Test fun batchConfirmationCancelAndCommitKeepHistoryFilesAndSemanticProgress() = runTest {
        Fixture(this,artwork=true).use { f ->
            f.nav(Destination.LIBRARY);val records=f.saved.toList()
            f.app.collections.select(f.source.books[0].id);f.pump();f.click("Select all");f.click("Remove from Library")
            assertTrue(f.visible("Remove 3 from Library?"));assertEquals(3,f.data.saved.size)
            f.click("Cancel");assertEquals(3,f.data.saved.size);assertTrue(f.visible("3 selected"))
            f.click("Remove from Library");f.click("Remove")
            assertTrue(f.data.saved.isEmpty());assertEquals(3,f.data.opened.size);assertEquals(records,f.saved)
            assertEquals(30,f.source.books.size);assertTrue(f.visible("Your Library is empty"));assertEquals(0,f.source.acquisitions)
        }
    }
    @Test fun partialBatchFailureKeepsFailedCoverSelectedAndRetryAvailable() = runTest {
        Fixture(this).use { f ->
            f.nav(Destination.LIBRARY);f.data.failedLibraryRemovals+=f.source.books[1].id
            f.app.collections.selectAll();f.pump();f.click("Remove from Library");f.click("Remove")
            assertEquals(setOf(f.source.books[1].id),f.data.saved.keys);assertTrue(f.visible("1 selected"))
            assertTrue(f.visible("2 removed; 1 could not be removed. Remaining items are kept. Try again."))
            f.data.failedLibraryRemovals.clear();f.click("Remove from Library");f.click("Remove")
            assertTrue(f.data.saved.isEmpty());assertEquals(3,f.data.opened.size)
        }
    }
    @Test fun historyCoverOpensDetailsAndCanRestoreLibraryWithoutDuplicatingPublication() = runTest {
        Fixture(this).use { f ->
            val id=f.source.books[2].id;f.data.saved.remove(id);f.app.collections.refreshLibrary();f.pump();f.nav(Destination.HISTORY)
            f.click("Details for Original publication 2");assertTrue(f.visible("Publication details"));assertEquals(0,f.source.acquisitions)
            assertTrue(f.visible("Add to Library"));f.click("Add to Library");assertEquals(3,f.data.saved.size)
            assertEquals(1,f.data.saved.values.count {it.publication.id==id});assertTrue(f.visible("Remove from Library"))
            f.click("Close");f.click("Original publication 2");assertIs<OpenPublicationState.Ready>(f.app.opening.value)
            assertEquals(id,assertNotNull(assertIs<OpenPublicationState.Ready>(f.app.opening.value).publication).id)
        }
    }
    @Test fun historyDeleteIsConfirmedAndKeepsLibraryAndSavedLocator() = runTest {
        Fixture(this,artwork=true).use { f ->
            f.nav(Destination.HISTORY);val records=f.saved.toList();val id=f.source.books[2].id
            f.click("Remove Original publication 2 from History");assertTrue(f.visible("Remove from History?"));assertEquals(3,f.data.opened.size)
            f.click("Cancel");assertEquals(3,f.data.opened.size)
            f.click("Remove Original publication 2 from History");f.click("Remove")
            assertFalse(id in f.data.opened);assertTrue(id in f.data.saved);assertEquals(records,f.saved)
            assertEquals(0,f.source.acquisitions)
        }
    }
    @Test fun historyGroupsActualDatesAndDoesNotInventEpubPages() = runTest {
        Fixture(this).use { f ->
            val zone=java.time.ZoneId.systemDefault();val now=java.time.Instant.ofEpochMilli(kotlin.time.Clock.System.now().toEpochMilliseconds())
            val today=now.atZone(zone).toLocalDate()
            listOf(0L,1L,4L).forEachIndexed { i,days ->
                val p=PublicationSnapshot.from(f.source.books[i]);f.data.opened[p.id]=HistoryEntry(p,today.minusDays(days).atTime(12,0).atZone(zone).toInstant().toEpochMilli())
            }
            f.nav(Destination.HISTORY);assertTrue(f.visible("Today"));assertTrue(f.visible("Yesterday"))
            assertEquals(f.source.books.map {it.id}.take(3),f.app.collections.history.value.entries.map {it.publication.id})
            assertFalse(f.nodes().any {it.config.getOrNull(SemanticsProperties.Text).orEmpty().any {t->t.text.startsWith("Page ") || t.text.startsWith("Chapter ")}})
        }
    }
    @Test fun selectedCoversHaveAccessibleStateActionsAndBoundedToolbarOnSmallDarkScreen() = runTest {
        Fixture(this,360,480,1.6f,count=12).use { f ->
            f.settings.change(ApplicationThemeMode.DARK);f.pump();f.nav(Destination.LIBRARY)
            val tile=f.nodes().first {it.config.getOrNull(SemanticsActions.OnLongClick)!=null}
            val select=assertNotNull(tile.config.getOrNull(SemanticsActions.CustomActions)).first {it.label=="Select publication"}
            assertTrue(select.action());f.pump()
            val selected=f.nodes().first {it.config.getOrNull(SemanticsProperties.Selected)==true && it.config.getOrNull(SemanticsActions.OnLongClick)!=null}
            assertTrue(selected.config[SemanticsProperties.StateDescription].contains("Selected"))
            assertTrue(f.visible("1 selected"));assertTrue(assertNotNull(f.appAppearance).dark)
            val nav=f.nodes().first {f.has(it,"Navigate to Settings")};assertTrue(nav.boundsInRoot.bottom<=480);assertTrue(nav.boundsInRoot.height>=48)
            assertTrue(f.nodes().any {it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null && it.boundsInRoot.height>0})
        }
    }
    @Test fun keyboardContextSelectionAndEscapeAreEquivalentToLongPress() = runTest {
        Fixture(this,1280,800).use { f ->
            f.nav(Destination.LIBRARY)
            repeat(20) {
                if(f.nodes().none {it.config.getOrNull(SemanticsProperties.Focused)==true && it.config.getOrNull(SemanticsActions.OnLongClick)!=null}) {
                    f.scene.sendKeyEvent(KeyEvent(Key.Tab,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Tab,KeyEventType.KeyUp));f.pump()
                }
            }
            f.scene.sendKeyEvent(KeyEvent(Key.F10,KeyEventType.KeyDown,isShiftPressed=true));f.pump();f.click("Select publication")
            assertTrue(f.visible("1 selected"));assertEquals(0,f.source.acquisitions)
            f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp));f.pump()
            assertTrue(f.app.collections.selection.value.isEmpty());assertEquals(Destination.LIBRARY,f.app.destination.value)
        }
    }
    @Test fun selectionAndDetailsPreviewsUseOnlyOriginalArtwork() = runTest {
        Fixture(this,390,800,count=12,artwork=true).use { f ->
            f.nav(Destination.LIBRARY);f.app.collections.select(f.source.books[0].id);f.app.collections.select(f.source.books[3].id);f.pump()
            fun preview(name: String) {
                System.getenv("INFINILECT_UI_PREVIEW_DIRECTORY")?.let { path ->
                    val directory=java.nio.file.Path.of(path);require(directory.isAbsolute&&!directory.startsWith(java.nio.file.Path.of("/workspace/INFINILECT")))
                    java.nio.file.Files.createDirectories(directory)
                    f.scene.render(testScheduler.currentTime*1_000_000).use { image ->
                        val bytes=assertNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use {it.bytes}
                        java.nio.file.Files.write(directory.resolve(name),bytes)
                    }
                }
            }
            assertTrue(f.visible("2 selected"));preview("library-selection.png")
            f.click("Clear selection");f.click("Original publication 0");assertTrue(f.visible("Open"));preview("publication-details.png")
        }
    }

    @Test fun thousandEntryLibraryStaysLazyThroughSelectionAndDistantScrolling() = runTest {
        Fixture(this,count=1000,artwork=true).use { f ->
            f.nav(Destination.LIBRARY);assertTrue(f.coverLoads in 1..23)
            f.app.collections.selectAll();f.pump();assertTrue(f.visible("1000 selected"))
            assertEquals(1000,f.app.collections.selection.value.size)
            assertTrue(f.coverLoads<30);assertTrue(f.covers.retainedEntries()<=24)
            f.click("Clear selection");f.reach(950)
            assertTrue(f.coverLoads<60);assertTrue(f.covers.retainedEntries()<=24)
            assertEquals(0,f.source.acquisitions);assertEquals(0,f.source.metadataCalls)
            assertEquals(1000,f.data.saved.size)
        }
    }

    private fun Fixture.preview(name:String) {
        System.getenv("INFINILECT_UI_PREVIEW_DIRECTORY")?.let { path ->
            val directory=java.nio.file.Path.of(path);require(directory.isAbsolute&&!directory.startsWith(java.nio.file.Path.of("/workspace/INFINILECT")))
            java.nio.file.Files.createDirectories(directory)
            scene.render(scope.testScheduler.currentTime*1_000_000).use { image ->
                java.nio.file.Files.write(directory.resolve(name),assertNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use {it.bytes})
            }
        }
    }
    private fun Fixture.edit(label:String,value:String) {
        assertTrue(visible(label));val control=nodes().last { it.config.getOrNull(SemanticsActions.SetText)?.action!=null }
        assertTrue(control.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString(value)));pump()
    }
    @Test fun homeIsDefaultAndEmptyStateOffersImportWithoutInventedRecommendations()=runTest {
        Fixture(this,count=0).use { f ->
            assertEquals(Destination.HOME,f.app.destination.value);assertFalse(f.app.handlesBack())
            assertTrue(f.visible("Hello!"));assertTrue(f.visible("Your next read starts here"));assertTrue(f.visible("Import local file"))
            assertFalse(f.visible("Continue reading"));assertFalse(f.visible("Recently added"));assertEquals(0,f.source.acquisitions)
            f.click("Import local file");assertEquals(1,f.picked);assertEquals(Destination.HOME,f.app.destination.value)
        }
    }
    @Test fun firstRunSetupIsOptionalAndDeferredChoiceDoesNotBlockLibraryOrReader()=runTest {
        Fixture(this,390,800,firstRun=true).use { f ->
            assertTrue(f.visible("Set up profile"));f.preview("home-first-run.png")
            f.click("Set up profile");assertTrue(f.visible("Your local profile"));f.preview("profile-onboarding.png")
            f.click("Set up later");assertTrue(f.preferenceValue.profile.setupHandled);assertNull(f.preferenceValue.profile.countryCode)
            assertFalse(f.visible("Set up profile"));assertFalse(f.visible("Your local profile"))
            f.nav(Destination.LIBRARY);f.click("Original publication 0");f.click("Open")
            assertIs<OpenPublicationState.Ready>(f.app.opening.value)
            f.app.back();f.pump();assertEquals(Destination.LIBRARY,f.app.destination.value)
            f.nav(Destination.HOME);assertFalse(f.visible("Set up profile"))
        }
    }
    @Test fun profileCountrySelectorStoresIsoNameIsOptionalAndSettingsEditsGreeting()=runTest {
        Fixture(this,390,900,firstRun=true).use { f ->
            val readerSession=f.app.searchSession.value;val records=f.saved.toList()
            f.click("Set up profile");f.edit("Display name (optional)","Ana")
            assertTrue(f.nodes().any{it.config.contains(SemanticsProperties.Disabled)&&it.children.any{n->f.has(n,"Save profile")}})
            f.click("Country of residence");f.edit("Find country","ES");f.preview("profile-country-selector.png");f.click("Choose country ES")
            f.click("English");f.click("Save profile")
            assertEquals(LocalProfile("Ana","ES","en",true),f.preferenceValue.profile)
            assertTrue(f.visible("Hello, Ana!"));assertEquals(records,f.saved);assertSame(readerSession,f.app.searchSession.value)
            f.nav(Destination.SETTINGS);assertTrue(f.visible("Ana"));f.preview("settings-profile.png")
            f.click("Edit profile");f.preview("profile-editor.png");f.edit("Display name (optional)","");f.click("Save profile")
            assertNull(f.preferenceValue.profile.displayName);assertEquals("ES",f.preferenceValue.profile.countryCode)
            f.nav(Destination.HOME);assertTrue(f.visible("Hello!"));assertEquals(0,f.source.acquisitions)
        }
    }
    @Test fun cancelledProfileAndEscapeDoNotChangePreferencesOrOpenReader()=runTest {
        Fixture(this,1280,800).use { f ->
            val before=f.preferenceValue;f.nav(Destination.SETTINGS);f.click("Edit profile");f.edit("Display name (optional)","Unsaved")
            f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Escape,KeyEventType.KeyUp));f.pump()
            assertFalse(f.visible("Display name (optional)"));assertEquals(before,f.preferenceValue)
            assertIs<OpenPublicationState.Idle>(f.app.opening.value)
        }
    }
    @Test fun homeResumeRestoresActualLocatorAndBackReturnsHomeWithoutWritingOnBrowsing()=runTest {
        Fixture(this,390,900,artwork=true).use { f ->
            val id=f.source.books[2].id
            f.progress.submit(ReadingProgress(ReadingProgressId(id,"text",PublicationFormat.TEXT),ReadingLocator.Text(8,51),8.0/51,2));f.pump()
            val before=f.saved.toList()
            assertTrue(f.visible("Continue reading"));assertTrue(f.visible("Continue Original publication 2"))
            assertEquals(0,f.source.acquisitions);f.click("Continue Original publication 2")
            val ready=assertIs<OpenPublicationState.Ready>(f.app.opening.value)
            assertEquals(id,assertNotNull(ready.publication).id);assertEquals(8,assertNotNull(ready.reading).codePointOffset.value)
            f.app.back();f.pump();assertEquals(Destination.HOME,f.app.destination.value)
            assertEquals(before,f.saved);assertTrue(f.visible("Continue reading"))
        }
    }
    @Test fun recentlyAddedOpensDetailsAndRemovalIsConfirmedWithoutChangingHistoryOrProgress()=runTest {
        Fixture(this,390,900,artwork=true).use { f ->
            // Give distinct insertion times; reopening/history ordering must not reorder this section.
            f.data.saved.entries.toList().forEachIndexed{ i,(id,entry)->f.data.saved[id]=entry.copy(addedAtEpochMillis=i.toLong())}
            f.app.collections.refreshLibrary();f.pump();f.reach(2)
            val before=f.saved.toList();f.click("Details for Original publication 2")
            assertTrue(f.visible("Publication details"));assertEquals(0,f.source.acquisitions)
            f.click("Remove from Library");assertTrue(f.visible("Remove from Library?"));f.click("Cancel")
            assertEquals(3,f.data.saved.size);f.click("Remove from Library");f.click("Remove")
            assertEquals(2,f.data.saved.size);assertEquals(3,f.data.opened.size);assertEquals(before,f.saved)
            assertIs<OpenPublicationState.Idle>(f.app.opening.value)
        }
    }
    @Test fun homeNavigationFiveAccessibleIconsAndBrandingFallbackHaveResponsiveBounds()=runTest {
        for(width in listOf(320,390,1280)) Fixture(this,width,800).use { f ->
            assertTrue(f.visible("INFINILECT"));assertFalse(f.visible("Make this space yours"))
            for(destination in applicationDestinations) {
                val tab=f.nodes().single{it.config.getOrNull(SemanticsProperties.Role)==Role.Tab&&f.has(it,"Navigate to ${destination.label()}")}
                assertTrue(tab.boundsInRoot.width>0);assertTrue(tab.boundsInRoot.height>=48)
                assertTrue(tab.boundsInRoot.left>=0&&tab.boundsInRoot.right<=width)
                f.nav(destination);assertEquals(destination,f.app.destination.value)
            }
            f.scene.sendKeyEvent(KeyEvent(Key.One,KeyEventType.KeyDown,isAltPressed=true));f.pump();assertEquals(Destination.HOME,f.app.destination.value)
            f.scene.constraints=androidx.compose.ui.unit.Constraints.fixed(360,480);f.pump()
            assertEquals(Destination.HOME,f.app.destination.value);assertEquals(0,f.source.acquisitions)
        }
    }
    @Test fun homePreviewsKeepSharedCoversThemesAndBoundedOwnership()=runTest {
        for((width,height,name,dark) in listOf(
            listOf(390,900,"home-android.png",false),listOf(1280,850,"home-desktop.png",true))) {
            Fixture(this,width as Int,height as Int,count=12,artwork=true).use { f ->
                f.settings.changeProfile(LocalProfile("Ana","ES",null,true))
                if(dark as Boolean)f.settings.change(ApplicationThemeMode.DARK)
                f.pump();assertTrue(f.visible("Hello, Ana!"));assertTrue(f.visible("Continue reading"))
                assertTrue(f.covers.retainedEntries()<=24);assertEquals(0,f.source.acquisitions)
                f.preview(name as String)
            }
        }
    }
    @Test fun libraryAndHistoryKeepAccessiblePaneTitlesWithoutRedundantHeadings()=runTest {
        Fixture(this).use {f->
            for(destination in listOf(Destination.LIBRARY,Destination.HISTORY)) {
                f.nav(destination)
                assertTrue(f.nodes().any{it.config.getOrNull(SemanticsProperties.PaneTitle)==destination.label()})
                assertFalse(f.nodes().any{it.config.contains(SemanticsProperties.Heading)&&f.has(it,destination.label())})
            }
        }
    }

}
