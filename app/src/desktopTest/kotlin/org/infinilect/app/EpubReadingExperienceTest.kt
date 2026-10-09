// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.*

/** Real shared Compose UI, semantics and touch/keyboard traces; virtual time only. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class EpubReadingExperienceTest {
    private val publication = PublicationId(SourceId("original"), "reading-experience")
    private val path = EpubEntryPath("OPS/chapter.xhtml")
    private val second = EpubEntryPath("OPS/garden.xhtml")
    private fun passage(i: Int) = "Original passage ${i.toString().padStart(4,'0')}. The quiet garden has blue flowers and a small stone path."
    private inner class Document : EpubDocument {
        override val publicationId = publication
        override val packagePath = EpubEntryPath("OPS/package.opf")
        override val metadata = EpubMetadata("original", "The Quiet Garden", listOf("en"), null)
        override val manifest = listOf(EpubManifestItem("chapter",path,"application/xhtml+xml"), EpubManifestItem("garden",second,"application/xhtml+xml"))
        override val spine = listOf(EpubSpineItem("chapter"),EpubSpineItem("garden"))
        override val navigationItemId = "nav"
        override suspend fun openResource(path: EpubEntryPath): ResourceContent = error("No acquisition in UI tests")
        var closes = 0
        override fun close() { closes++ }
    }
    private inner class Parser : EpubParser {
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var calls = 0
        var gatedIndex: Int? = null
        var blockGate: CompletableDeferred<Unit>? = null
        var failBlock = false
        override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter = error("Window API required")
        override suspend fun window(document: EpubDocument, path: EpubEntryPath, request: EpubWindowRequest): EpubChapter {
            calls++
            val total = if (path == second) 6 else 3001
            val index = when (request) { is EpubWindowRequest.Block -> request.index; is EpubWindowRequest.Anchor -> request.value.toInt();
                is EpubWindowRequest.Locator -> request.value.elementPath.last(); EpubWindowRequest.End -> total-1 }
            if (request is EpubWindowRequest.Anchor) { gate?.await(); if (fail) error("Private diagnostic must not escape") }
            if (index == gatedIndex) { blockGate?.await(); if (failBlock) error("Controlled window failure") }
            val start = index/128*128
            val blocks = (start until minOf(start+128,total)).map { i ->
                val runs = if (i == 1) listOf(EpubRun("Visit the garden",target=EpubTarget(path,"1800"))) else listOf(EpubRun(passage(i)))
                EpubBlock(listOf(0,i),0,EpubBlockKind.PARAGRAPH,runs,i*100)
            }
            return EpubChapter(path,blocks,mapOf("1800" to EpubPosition(listOf(0,1800),0)),start,total,total*100)
        }
        override suspend fun toc(document: EpubDocument) = listOf(EpubTocEntry("The garden begins",EpubTarget(path),0),
            EpubTocEntry("A distant passage",EpubTarget(path,"1800"),1),EpubTocEntry("The evening",EpubTarget(second),0))
    }
    private class ProgressStore : ReadingProgressStore {
        var value: ReadingProgress? = null
        var saves = 0
        override suspend fun get(id: ReadingProgressId) = value?.takeIf { it.id == id }
        override suspend fun save(progress: ReadingProgress): Boolean { value=progress; saves++; return true }
        override suspend fun remove(id: ReadingProgressId): Boolean { value=null; return true }
    }
    private class SettingsStore(initial: EpubReaderPreferences = EpubReaderPreferences()) : EpubReaderSettingsStore {
        var value = initial
        override suspend fun load() = value
        override suspend fun save(preferences: EpubReaderPreferences): Boolean { value=preferences; return true }
    }
    private inner class Fixture(val scope: TestScope, width: Int = 700, height: Int = 600, savedSettings: EpubReaderPreferences = EpubReaderPreferences(), private val savedProgress: ReadingProgress? = null) : AutoCloseable {
        val parser = Parser(); val document = Document(); val progressStore = ProgressStore(); val settingsStore = SettingsStore(savedSettings)
        val writer = ProgressPersistence(progressStore,StandardTestDispatcher(scope.testScheduler)) { scope.testScheduler.currentTime + 1 }
        val preferences = EpubSettingsPersistence(settingsStore,StandardTestDispatcher(scope.testScheduler)) { scope.testScheduler.currentTime + 1 }
        val reader = EpubReaderController(document,ReadingProgressId(publication,"book",PublicationFormat.EPUB),scope.backgroundScope,parser,writer,preferences)
        val scene = ImageComposeScene(width,height,Density(1f),coroutineContext=StandardTestDispatcher(scope.testScheduler))
        var density by mutableStateOf(Density(1f))
        var exits = 0
        var platformBack: (() -> Unit)? = null
        var appearance: ReaderAppearance? = null
        var mounted by mutableStateOf(true)
        suspend fun start(showControls: Boolean = true) {
            reader.initialize(savedProgress)
            scene.setContent {
                CompositionLocalProvider(LocalDensity provides density) {
                    if (mounted) EpubReader(reader,false,{ exits++ },"Back to Library",backHandler={enabled,callback->
                        DisposableEffect(enabled,callback) { platformBack=if(enabled)callback else null; onDispose {} }
                    }, appearanceChanged={ appearance=it })
                }
            }
            pump()
            if (showControls) click("Toggle reading controls")
        }
        fun pump() { repeat(10) { scope.runCurrent(); scene.render(scope.testScheduler.currentTime*1_000_000).close() }; scope.runCurrent() }
        fun advance(ms: Long) { scope.advanceTimeBy(ms); pump() }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node)+node.children.flatMap(::walk)
        fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        fun has(node: SemanticsNode,label: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text==label }==true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true
        fun visible(label: String) = nodes().any { has(it,label) }
        fun control(label: String) = assertNotNull(nodes().firstOrNull { !it.config.contains(SemanticsProperties.Disabled) &&
            it.config.getOrNull(SemanticsActions.OnClick)?.action!=null && walk(it).any { child->has(child,label) } },"Enabled $label")
        fun click(label: String) {
            if (label == "Toggle reading controls") {
                val action=nodes().flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }.single { it.label==label }
                assertTrue(action.action())
            } else assertTrue(control(label).config[SemanticsActions.OnClick].action!!.invoke())
            pump()
        }
        fun tap(x: Float, y: Float = 300f) { val at=Offset(x,y); touch(PointerEventType.Press,at); advance(30); touch(PointerEventType.Release,at); advance(16) }
        fun scroll(delta: Float) {
            val n=nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null }
            assertTrue(n.config[SemanticsActions.ScrollBy].action!!.invoke(0f,delta)); repeat(30) { advance(16) }
        }
        fun jump(index: Int) {
            val n=nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action!=null }
            assertTrue(n.config[SemanticsActions.ScrollToIndex].action!!.invoke(index)); pump(); advance(16)
        }
        fun ready() = assertIs<EpubReaderState.Ready>(reader.state.value)
        fun firstPassage(): Pair<String,Float> {
            val viewport=nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null }
            val n=nodes().filter { it.layoutInfo.isPlaced && it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text.startsWith("Original passage") }==true &&
                it.positionInRoot.y+it.size.height>viewport.positionInRoot.y }.minBy { it.positionInRoot.y }
            return n.config[SemanticsProperties.Text].single().text to n.positionInRoot.y
        }
        fun touch(type: PointerEventType, at: Offset) { scene.sendPointerEvent(type,at,timeMillis=scope.testScheduler.currentTime,type=PointerType.Touch); pump() }
        fun key(code: Int, modifiers: Int = 0) {
            val key=when(code) { AwtKeyEvent.VK_F10->Key.F10; AwtKeyEvent.VK_LEFT->Key.DirectionLeft; AwtKeyEvent.VK_RIGHT->Key.DirectionRight; AwtKeyEvent.VK_PAGE_DOWN->Key.PageDown; AwtKeyEvent.VK_PAGE_UP->Key.PageUp; else->error("Unknown synthetic key") }
            scene.sendKeyEvent(KeyEvent(key=key,type=KeyEventType.KeyDown,isAltPressed=modifiers!=0)); pump()
        }
        override fun close() { scene.close(); reader.close(); preferences.close(); writer.close(); scope.runCurrent(); assertEquals(1,document.closes) }
    }

    @Test fun chromeToggleDoesNotChangeGeometryTicketLocatorOrProgress() = runTest {
        Fixture(this).use { f ->
            f.start(); f.reader.navigate(EpubTarget(path,"1800")); f.pump(); f.reader.flush(); runCurrent()
            val ready=f.ready(); val before=f.firstPassage(); val saved=f.progressStore.value; val calls=f.parser.calls
            repeat(4) { f.click("Hide reading controls"); assertEquals(before,f.firstPassage()); f.click("Toggle reading controls") }
            assertEquals(ready.ticket,f.ready().ticket); assertEquals(saved,f.progressStore.value); assertEquals(calls,f.parser.calls)
            assertTrue(f.visible("Section 1 of 2")); assertFalse(f.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text.contains("window",true) }==true })
        }
    }
    @Test fun shortCentralTouchTogglesButScrollAndLongPressDoNot() = runTest {
        Fixture(this).use { f ->
            f.start(); val center=Offset(350f,300f)
            f.touch(PointerEventType.Press,center); f.advance(30); f.touch(PointerEventType.Release,center)
            assertFalse(f.visible("Hide reading controls"))
            f.click("Toggle reading controls")
            f.touch(PointerEventType.Press,center); f.advance(16); f.touch(PointerEventType.Move,center-Offset(0f,100f)); f.advance(16); f.touch(PointerEventType.Release,center-Offset(0f,100f))
            assertTrue(f.visible("Hide reading controls"))
            f.touch(PointerEventType.Press,center); f.advance(600); f.touch(PointerEventType.Release,center)
            assertTrue(f.visible("Hide reading controls"))
        }
    }
    @Test fun internalLinkKeepsTapOwnershipAndReachesDeepAnchor() = runTest {
        Fixture(this).use { f ->
            f.start(); f.click("Hide reading controls")
            val link=f.nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text=="Visit the garden" }==true }
            val point=link.positionInRoot+Offset(12f,12f)
            f.touch(PointerEventType.Press,point); f.advance(30); f.touch(PointerEventType.Release,point)
            assertEquals(listOf(0,1800),f.ready().chapter.blocks[f.ready().initialPosition.first].elementPath)
            assertFalse(f.visible("Hide reading controls"))
        }
    }
    @Test fun contentsAndSettingsDismissWithoutReopeningOrSavingAnotherPosition() = runTest {
        Fixture(this).use { f ->
            f.start(); val ticket=f.ready().ticket; val before=f.firstPassage(); val calls=f.parser.calls
            f.click("Contents"); assertTrue(f.visible("A distant passage")); f.click("Done")
            f.click("Settings"); assertTrue(f.visible("Saved for all EPUBs on this device.")); f.click("Done")
            assertTrue(f.nodes().any { it.config.getOrNull(SemanticsProperties.Focused)==true && f.walk(it).any { child->f.has(child,"Settings") } })
            assertEquals(ticket,f.ready().ticket); assertEquals(before,f.firstPassage()); assertEquals(calls,f.parser.calls)
            assertEquals(0,f.exits)
        }
    }
    @Test fun typographyAndAppearanceKeepDeepSemanticPositionAndPersistExistingPreferences() = runTest {
        Fixture(this).use { f ->
            f.start(); f.reader.navigate(EpubTarget(path,"1800")); f.pump()
            val initial=f.ready(); f.reader.report(initial.ticket,initial.initialPosition.first,8)
            val locator=initial.chapter.locator(initial.initialPosition.first,8)
            f.click("Settings")
            for (label in listOf("Font size","Line spacing","Margins")) {
                f.click("Increase $label")
                val ready=f.ready(); assertEquals(locator,ready.chapter.locator(ready.initialPosition.first,ready.initialPosition.second))
            }
            f.click("Dark"); f.click("Done"); f.advance(350)
            assertEquals(EpubReaderSettings(20,160,20,EpubReadingTheme.DARK),f.reader.settings.value)
            assertEquals(f.reader.settings.value,f.settingsStore.value.settings)
            assertTrue(f.firstPassage().first.startsWith("Original passage 1800"))
        }
    }
    @Test fun contentsPreviousAndNextKeepWindowBoundariesOutOfTheProgressLabel() = runTest {
        Fixture(this).use { f ->
            f.start(); f.click("Next"); assertEquals(1,f.ready().spineIndex)
            assertTrue(f.visible("Section 2 of 2"))
            f.click("Previous"); assertEquals(0,f.ready().spineIndex); assertEquals(0,f.ready().chapter.startBlock)
            f.click("Contents"); f.click("A distant passage")
            assertTrue(f.firstPassage().first.startsWith("Original passage 1800"))
            f.click("Contents"); f.click("The evening"); assertEquals(1,f.ready().spineIndex)
            assertTrue(f.visible("Section 2 of 2"))
        }
    }
    @Test fun coldPendingNavigationRetainsPassageAndCoalescesWhileControlsAreUsed() = runTest {
        Fixture(this).use { f ->
            f.start(); val before=f.firstPassage(); f.reader.flush(); runCurrent(); val saved=f.progressStore.value
            f.parser.gate=CompletableDeferred(); f.click("Contents"); f.click("A distant passage")
            assertIs<EpubReaderState.Loading>(f.reader.state.value); val calls=f.parser.calls
            repeat(4) { f.reader.navigate(EpubTarget(path,"1800")); f.click("Hide reading controls"); f.click("Toggle reading controls") }
            assertEquals(calls,f.parser.calls); assertEquals(before,f.firstPassage()); f.advance(200)
            assertTrue(f.visible("Preparing EPUB passage")); assertEquals(saved,f.progressStore.value)
            val indicator=f.nodes().first { f.has(it,"Preparing EPUB passage") }
            f.tap(indicator.positionInRoot.x+indicator.size.width/2f,indicator.positionInRoot.y+indicator.size.height/2f)
            assertTrue(f.visible("Hide reading controls")); assertEquals(saved,f.progressStore.value)
            f.parser.gate!!.complete(Unit); f.pump()
            assertTrue(f.firstPassage().first.startsWith("Original passage 1800")); assertFalse(f.visible("Preparing EPUB passage"))
        }
    }
    @Test fun errorRetryKeepsLastReadablePassageAndCorrectDestination() = runTest {
        Fixture(this).use { f ->
            f.start(); val before=f.firstPassage(); f.parser.fail=true
            f.click("Contents"); f.click("A distant passage")
            assertIs<EpubReaderState.Error>(f.reader.state.value); assertEquals(before,f.firstPassage())
            f.click("Hide reading controls"); assertTrue(f.visible("Retry"))
            f.parser.fail=false; f.click("Retry")
            assertTrue(f.firstPassage().first.startsWith("Original passage 1800")); assertFalse(f.visible("Retry"))
        }
    }
    @Test fun keyboardControlsAndSemanticNavigationDoNotTurnOnUnmodifiedArrowKeys() = runTest {
        Fixture(this).use { f ->
            f.start(); f.key(AwtKeyEvent.VK_F10); assertFalse(f.visible("Hide reading controls"))
            f.key(AwtKeyEvent.VK_F10); assertTrue(f.visible("Hide reading controls"))
            f.key(AwtKeyEvent.VK_RIGHT,AwtKeyEvent.ALT_DOWN_MASK); assertEquals(128,f.ready().chapter.startBlock)
            f.key(AwtKeyEvent.VK_LEFT,AwtKeyEvent.ALT_DOWN_MASK); assertEquals(0,f.ready().chapter.startBlock)
            val ticket=f.ready().ticket; f.key(AwtKeyEvent.VK_RIGHT); assertEquals(ticket,f.ready().ticket)
        }
    }
    @Test fun platformBackDismissesPanelAndChromeBeforeExiting() = runTest {
        Fixture(this).use { f ->
            f.start(); f.click("Settings"); assertNotNull(f.platformBack).invoke(); f.pump()
            assertFalse(f.visible("Reading settings")); assertTrue(f.visible("Hide reading controls")); assertEquals(0,f.exits)
            assertNotNull(f.platformBack).invoke(); f.pump(); assertNull(f.platformBack)
            f.click("Toggle reading controls"); f.click("Back to Library"); assertEquals(1,f.exits)
        }
    }
    @Test fun labelledTouchTargetsAndAdaptiveModalFitCompactLandscapeAndLargeText() = runTest {
        for ((width,height,fontScale) in listOf(Triple(360,700,1f), Triple(960,400,1f), Triple(360,700,1.5f))) {
            Fixture(this,width,height).use { f ->
                f.start(); f.density=Density(1f,fontScale); f.pump()
                for (label in listOf("Back to Library","Contents","Settings","Hide reading controls")) {
                    val node=f.control(label); assertTrue(node.size.width>=48 && node.size.height>=48)
                }
                f.click("Settings"); val close=f.control("Close Reading settings")
                assertTrue(close.positionInRoot.x>=0 && close.positionInRoot.y>=0)
                assertTrue(close.positionInRoot.y+close.size.height<=height)
                f.click("Done"); val ready=f.ready(); val locator=ready.chapter.locator(ready.initialPosition.first,ready.initialPosition.second)
                f.scene.constraints=Constraints.fixed(700,600); f.pump()
                assertEquals(locator,f.ready().chapter.locator(f.ready().initialPosition.first,f.ready().initialPosition.second))
            }
        }
    }
    @Test fun readerOpensWithOnlyContentAndAccessibleNonvisualControls() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false)
            assertFalse(f.visible("Controls")); assertFalse(f.visible("Hide reading controls")); assertFalse(f.visible("0% of book"))
            assertFalse(f.visible("Hide reading controls")); assertFalse(f.visible("Next"))
            assertTrue(f.firstPassage().first.startsWith("Original passage 0000"))
            f.click("Toggle reading controls"); assertTrue(f.visible("Contents")); assertTrue(f.visible("Settings"))
        }
    }
    @Test fun keyboardScrollKeepsContinuousProgressAndDoesNotInvokeSectionNavigation() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false); val before=f.firstPassage(); val presentation=f.ready().presentation
            f.key(AwtKeyEvent.VK_PAGE_DOWN); repeat(30) { f.advance(16) }
            assertNotEquals(before.first,f.firstPassage().first); assertEquals(presentation,f.ready().presentation)
            f.reader.flush(); runCurrent(); assertNotNull(f.progressStore.value)
            f.key(AwtKeyEvent.VK_PAGE_UP); repeat(30) { f.advance(16) }; assertEquals(before.first,f.firstPassage().first)
        }
    }
    @Test fun enlargedViewportStillReportsTheFirstVisibleParagraphAndDeepOffset() = runTest {
        Fixture(this,1000,1400).use { f ->
            f.start(showControls=false)
            f.reader.presentationChanged(EpubReaderSettings(fontSize=26)); f.pump()
            val scroll=f.nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null }
            val second=f.nodes().first { f.has(it,passage(2)) }; val third=f.nodes().first { f.has(it,passage(3)) }
            val layouts=mutableListOf<TextLayoutResult>()
            assertTrue(second.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            val layout=layouts.single(); assertTrue(layout.lineCount>1)
            val delta=second.positionInRoot.y+158*(third.positionInRoot.y-second.positionInRoot.y)+layout.getLineTop(1)+1-scroll.positionInRoot.y
            assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f,delta))
            repeat(30) { f.advance(16) }
            f.reader.flush(); runCurrent()
            val saved=assertIs<ReadingLocator.Epub>(assertNotNull(f.progressStore.value).locator)
            val index=f.firstPassage().first.substringAfter("Original passage ").substringBefore('.').toInt()
            assertEquals(index,saved.elementPath.last()); assertTrue(saved.codePointOffset>0)
            assertTrue(f.reader.retainedBlocks<=256); assertTrue(f.reader.retainedTextUnits<=131072)
        }
    }

    @Test fun secondPointerCancelsCentralTapWithoutNavigationOrChromeChange() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false); val ticket=f.ready().ticket; val at=Offset(350f,300f)
            f.touch(PointerEventType.Press,at)
            f.scene.sendPointerEvent(PointerEventType.Press,listOf(
                androidx.compose.ui.scene.ComposeScenePointer(PointerId(0),at,true,PointerType.Touch),
                androidx.compose.ui.scene.ComposeScenePointer(PointerId(1),at+Offset(20f,0f),true,PointerType.Touch)),timeMillis=testScheduler.currentTime)
            f.pump(); f.advance(30)
            f.scene.sendPointerEvent(PointerEventType.Release,listOf(
                androidx.compose.ui.scene.ComposeScenePointer(PointerId(0),at,false,PointerType.Touch),
                androidx.compose.ui.scene.ComposeScenePointer(PointerId(1),at+Offset(20f,0f),false,PointerType.Touch)),timeMillis=testScheduler.currentTime)
            f.pump(); assertFalse(f.visible("Hide reading controls")); assertEquals(ticket,f.ready().ticket)
        }
    }
    @Test fun settingsAndDeepPositionRestoreThroughANewReaderOwner() = runTest {
        var settings=EpubReaderPreferences(); var progress:ReadingProgress?=null
        Fixture(this).use { f ->
            f.start(); f.reader.navigate(EpubTarget(path,"1800")); f.pump()
            f.click("Settings"); f.click("Increase Font size"); f.click("Dark"); f.click("Done"); f.advance(350)
            f.reader.flush(); runCurrent(); settings=f.settingsStore.value; progress=assertNotNull(f.progressStore.value)
        }
        Fixture(this,savedSettings=settings,savedProgress=progress).use { f ->
            f.start(showControls=false); assertEquals(settings.settings,f.reader.settings.value)
            val ready=f.ready(); assertEquals(progress!!.locator,ready.chapter.locator(ready.initialPosition.first,ready.initialPosition.second))
            assertTrue(f.firstPassage().first.startsWith("Original passage 1800"))
        }
    }

    @Test fun navigationDoesNotStealKeyboardFocusFromAnExplicitToolbarButton() = runTest {
        Fixture(this).use { f ->
            f.start(); val button=f.control("Next")
            assertTrue(assertNotNull(button.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke()); f.pump()
            f.click("Next"); assertEquals(1,f.ready().spineIndex)
            assertTrue(f.nodes().any { it.config.getOrNull(SemanticsProperties.Focused)==true && f.walk(it).any { child->f.has(child,"Next") } })
        }
    }

    @Test fun lateralTapsScrollOneViewportWithOverlapAndPersistMeasuredPassage() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false); f.reader.navigate(EpubTarget(path,"1800")); f.pump()
            val before=f.firstPassage(); val p1801=f.nodes().first { f.has(it,passage(1801)) }
            val lineHeight=p1801.positionInRoot.y-before.second
            val presentation=f.ready().presentation
            f.tap(690f); val after=f.firstPassage()
            val index=after.first.substringAfter("Original passage ").substringBefore('.').toInt()
            val movement=(index-1800)*lineHeight+before.second-after.second
            assertTrue(kotlin.math.abs(movement-510f)<2f,"Expected 85% of 600px viewport, got $movement")
            assertEquals(presentation,f.ready().presentation); assertEquals(0,f.ready().spineIndex)
            f.reader.flush(); runCurrent()
            assertEquals(index,assertIs<ReadingLocator.Epub>(assertNotNull(f.progressStore.value).locator).elementPath.last())
            f.tap(5f); assertEquals(before.first,f.firstPassage().first)
            assertTrue(kotlin.math.abs(before.second-f.firstPassage().second)<1f)
            assertFalse(f.visible("Hide reading controls"))
        }
    }
    @Test fun repeatedViewportTapsCrossWindowsWithoutMissingDuplicatedOrUnboundedContent() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false)
            var last=0
            repeat(45) {
                f.tap(690f)
                val index=f.firstPassage().first.substringAfter("Original passage ").substringBefore('.').toInt()
                assertTrue(index>last && index-last<15,"Unexpected jump from $last to $index")
                last=index
                val labels=f.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text }.filter { it.startsWith("Original passage") }
                assertEquals(labels.distinct(),labels)
                assertTrue(f.reader.retainedBlocks<=256); assertTrue(f.reader.retainedTextUnits<=131072)
            }
            assertTrue(last>256); val deepest=last
            repeat(20) { f.tap(5f) }
            val index=f.firstPassage().first.substringAfter("Original passage ").substringBefore('.').toInt()
            assertTrue(index<deepest); assertEquals(0,f.ready().spineIndex)
        }
    }
    @Test fun lateralTapsAtSpineBoundariesEnterAdjacentChapterInReadingDirection() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false); f.reader.chapter(1); f.pump()
            assertEquals(1,f.ready().spineIndex)
            // Six short paragraphs fit: forward tap is now a safe final boundary.
            f.tap(690f); assertEquals(1,f.ready().spineIndex)
            f.tap(5f); assertEquals(0,f.ready().spineIndex)
            assertEquals(3000,f.ready().chapter.blocks[f.ready().initialPosition.first].elementPath.last()); assertTrue(f.visible(passage(3000)))
            f.tap(690f); assertEquals(1,f.ready().spineIndex)
            assertTrue(f.firstPassage().first.startsWith("Original passage 0000"))
            f.reader.chapter(0); f.pump(); val before=f.firstPassage()
            f.tap(5f); assertEquals(before,f.firstPassage())
        }
    }
    @Test fun pendingBoundaryTapsKeepOldContentAndDoNotQueueOrAdvanceProgress() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false)
            f.parser.gatedIndex=256; f.parser.blockGate=CompletableDeferred()
            f.key(AwtKeyEvent.VK_PAGE_DOWN); f.jump(255); val before=f.firstPassage(); val calls=f.parser.calls
            f.reader.flush(); runCurrent(); val saved=f.progressStore.value
            assertEquals(247,assertIs<ReadingLocator.Epub>(assertNotNull(saved).locator).elementPath.last())
            // The first boundary tap promotes the pending lookahead; subsequent taps
            // must neither restart it nor commit the still-unpresented destination.
            f.tap(690f); assertTrue(f.reader.loading.value)
            repeat(15) { f.tap(690f) }
            assertEquals(before,f.firstPassage()); assertEquals(calls,f.parser.calls)
            assertEquals(saved,f.progressStore.value); assertTrue(f.reader.retainedBlocks<=256)
            f.parser.blockGate!!.complete(Unit); f.pump(); f.advance(16)
            assertTrue(f.firstPassage().first.startsWith("Original passage 0256"))
            f.reader.flush(); runCurrent()
            assertEquals(256,assertIs<ReadingLocator.Epub>(assertNotNull(f.progressStore.value).locator).elementPath.last())
            assertTrue(f.reader.retainedBlocks<=256)
        }
    }
    @Test fun lookaheadCompletionAtTemporaryBufferEndDoesNotOscillateOrRewriteProgress() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false); f.parser.gatedIndex=256; f.parser.blockGate=CompletableDeferred()
            f.key(AwtKeyEvent.VK_PAGE_DOWN); f.jump(255); val before=f.firstPassage(); val calls=f.parser.calls
            f.reader.flush(); runCurrent(); val saved=assertNotNull(f.progressStore.value)
            assertEquals(247,assertIs<ReadingLocator.Epub>(saved.locator).elementPath.last())
            f.parser.blockGate!!.complete(Unit); f.pump(); repeat(8) { f.advance(16) }
            assertEquals(before,f.firstPassage()); assertEquals(128,f.ready().chapter.startBlock)
            f.reader.flush(); runCurrent(); assertEquals(saved,f.progressStore.value); assertEquals(calls,f.parser.calls)
            f.tap(690f); assertNotEquals(before.first,f.firstPassage().first)
            assertTrue(f.reader.retainedBlocks<=256)
        }
    }
    @Test fun overlayBlankAreasAndDisabledButtonsBlockUnderlyingTapNavigation() = runTest {
        Fixture(this).use { f ->
            f.start(); val before=f.firstPassage(); val ticket=f.ready().ticket
            for (at in listOf(Offset(100f,20f),Offset(5f,580f),Offset(350f,580f))) f.tap(at.x,at.y)
            assertEquals(before,f.firstPassage()); assertEquals(ticket,f.ready().ticket)
            assertTrue(f.visible("Hide reading controls"))
            f.click("Settings"); f.tap(690f,300f); assertEquals(before,f.firstPassage())
        }
    }
    @Test fun allTapZonesExcludeVerticalDragLongPressAndPinch() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false)
            for (x in listOf(5f,350f,690f)) {
                val at=Offset(x,300f); val original=f.firstPassage()
                f.touch(PointerEventType.Press,at)
                for ((type,pressed) in listOf(PointerEventType.Press to true,PointerEventType.Move to true,PointerEventType.Release to false)) {
                    f.scene.sendPointerEvent(type,listOf(
                        androidx.compose.ui.scene.ComposeScenePointer(PointerId(0),at,pressed,PointerType.Touch),
                        androidx.compose.ui.scene.ComposeScenePointer(PointerId(1),at+Offset(0f,if(type==PointerEventType.Move)50f else 20f),pressed,PointerType.Touch)),timeMillis=testScheduler.currentTime)
                    f.advance(16)
                }
                assertEquals(original,f.firstPassage()); assertFalse(f.visible("Hide reading controls"))
                val before=f.firstPassage()
                f.touch(PointerEventType.Press,at); f.advance(600); f.touch(PointerEventType.Release,at)
                assertEquals(before,f.firstPassage()); assertFalse(f.visible("Hide reading controls"))
                f.touch(PointerEventType.Press,at); f.advance(16); f.touch(PointerEventType.Move,at-Offset(0f,50f)); f.advance(16); f.touch(PointerEventType.Release,at-Offset(0f,50f))
                assertEquals(0,f.ready().spineIndex); assertFalse(f.visible("Hide reading controls"))
            }
        }
    }
    @Test fun nativeAppearanceCallbackTracksThemeAndRestoresOnReaderExitWithoutReflow() = runTest {
        Fixture(this).use { f ->
            f.start(showControls=false); val light=assertNotNull(f.appearance)
            assertFalse(light.dark); assertEquals(epubReaderColors(false).background,light.background)
            f.click("Toggle reading controls"); val before=f.firstPassage(); val ticket=f.ready().ticket
            assertEquals(light,f.appearance); f.click("Settings"); f.click("Dark"); f.click("Done")
            assertEquals(ReaderAppearance(true,epubReaderColors(true).background),f.appearance)
            assertEquals(before.first,f.firstPassage().first)
            f.click("Settings"); f.click("Light"); f.click("Done"); assertEquals(light,f.appearance)
            f.click("Settings"); f.click("System"); f.click("Done"); assertFalse(assertNotNull(f.appearance).dark)
            f.mounted=false; f.pump(); assertNull(f.appearance)
            f.mounted=true; f.pump(); assertEquals(light,f.appearance)
            assertTrue(f.ready().ticket>ticket); assertEquals(before.first,f.firstPassage().first)
        }
    }

}
