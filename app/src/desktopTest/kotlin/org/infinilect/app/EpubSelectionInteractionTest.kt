// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.foundation.text.contextmenu.provider.*
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.reader.epub.*
import kotlin.test.*

/** Actual public SelectionState + pointer events. Headless Desktop is not Android acceptance. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
class EpubSelectionInteractionTest {
    private fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
    private inner class Fixture(val scope: TestScope) : AutoCloseable {
        val selection = SelectionState()
        var provider: TextContextMenuProvider? = null
        val scene = ImageComposeScene(400, 700, Density(1f, 1.8f), coroutineContext = StandardTestDispatcher(scope.testScheduler))
        var translated: String? = null
        var copied: AnnotatedString? = null
        @Suppress("DEPRECATION")
        val clipboard=object: ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) { copied=annotatedString }
            override fun getText()=copied
        }
        var layout: TextLayoutResult? = null
        var origin = Offset.Zero
        val first = "Hello 🌍. Unicode café, paraules i palabras. " + "A long paragraph with multiple lines. ".repeat(8)
        val second = "Second paragraph. Another chapter is never part of this selection."
        init {
            scene.setContent { CompositionLocalProvider(LocalClipboardManager provides clipboard) { MaterialTheme {
                EpubSelectionToolbar(selection, true, { translated = selection.selectedTexts.joinToString("\n") { it.text }; selection.clear() }) {
                    provider = LocalTextContextMenuToolbarProvider.current
                    SelectionContainer(state = selection) {
                        Column(Modifier.fillMaxSize().padding(top=80.dp)) {
                            Text(first, modifier=Modifier.onGloballyPositioned { origin=it.positionInWindow() }, fontSize = 18.sp, onTextLayout = { layout = it })
                            Text(second, fontSize = 18.sp)
                        }
                    }
                }
            } } }; pump()
        }
        fun pump() { repeat(12) { scope.runCurrent(); scene.render(scope.testScheduler.currentTime * 1_000_000).close() }; scope.runCurrent() }
        fun touch(type: PointerEventType, at: Offset) { scene.sendPointerEvent(type, at, timeMillis = scope.testScheduler.currentTime, type = PointerType.Touch); pump() }
        fun tap(at: Offset) { touch(PointerEventType.Press, at); scope.testScheduler.advanceTimeBy(30); touch(PointerEventType.Release, at) }
        fun select(start: Int, end: Int) { selection.select(TextRange(start, end)); pump() }
        fun menu(): Job = scope.launch {
            checkNotNull(provider).showTextContextMenu(object : TextContextMenuDataProvider {
                override fun position(destinationCoordinates: LayoutCoordinates) = Offset(10f, 550f)
                override fun contentBounds(destinationCoordinates: LayoutCoordinates) = Rect(10f, 550f, 150f, 580f)
                override fun data() = TextContextMenuData.Empty
            })
        }.also { pump() }
        fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        fun text() = selection.selectedTexts.joinToString("\n") { it.text }
        override fun close() { scene.close() }
    }
    @Test fun oneOutsideTapClosesMenuAndClearsRealSelection() = runTest {
        Fixture(this).use { f ->
            f.select(0,5); val menu = f.menu()
            assertEquals("Hello", f.text())
            f.tap(Offset(390f, 680f))
            assertTrue(f.selection.selectedTexts.isEmpty(), "One outside tap must clear selection, not merely close a popup")
            f.pump(); assertTrue(menu.isCompleted)
            f.select(9,16); assertEquals(f.first.substring(9,16),f.text())
        }
    }
    @Test fun publicSelectionExtendsShrinksAndCrossesParagraphsWithoutFlatteningUnicode() = runTest {
        Fixture(this).use { f ->
            f.select(0,5); assertEquals("Hello",f.text())
            f.select(0,16); assertEquals(f.first.substring(0,16),f.text())
            f.select(6,9); assertEquals("🌍.",f.text())
            f.select(9,f.first.length+6)
            assertEquals(listOf(f.first.substring(9),"Second"),f.selection.selectedTexts.map { it.text })
            f.selection.clear(); f.pump(); assertTrue(f.selection.selectedTexts.isEmpty())
        }
    }
    @Test fun longPressAndActualHandleDragsCanExtendAndReduceLargeFontSelectionWithToolbarOpen() = runTest {
        Fixture(this).use { f ->
            val start=f.first.indexOf("Unicode")
            val layout=checkNotNull(f.layout)
            val word=f.origin+layout.getBoundingBox(start+2).center
            f.touch(PointerEventType.Press,word); testScheduler.advanceTimeBy(600); f.pump()
            f.touch(PointerEventType.Release,word)
            assertEquals("Unicode",f.text())
            f.menu()
            // Desktop handles are narrow caret popups; locate their public rendered geometry,
            // not an internal SelectionHandleInfo API or Android-specific bulb coordinates.
            fun handleCenters()=f.nodes().filter { it.size.width in 1..32 && it.size.height>48 }
                .map { it.positionInWindow+Offset(it.size.width/2f,it.size.height/2f) }.distinct().sortedBy { it.y }
            assertEquals(2,handleCenters().size)
            val endHandle=handleCenters().last()
            f.touch(PointerEventType.Press,endHandle)
            f.touch(PointerEventType.Move,endHandle+Offset(20f,0f))
            val destination=f.origin+layout.getBoundingBox(f.first.indexOf("paragraph")+8).center
            f.touch(PointerEventType.Move,destination); f.touch(PointerEventType.Release,destination)
            val extended=f.text()
            assertTrue(extended.length>7,"Handle dragging must extend while the toolbar is open: $extended")
            val originalEnd=start+extended.length
            val startHandle=handleCenters().first()
            f.touch(PointerEventType.Press,startHandle)
            f.touch(PointerEventType.Move,startHandle+Offset(20f,0f))
            val shrink=f.origin+layout.getBoundingBox(start+9).center
            f.touch(PointerEventType.Move,shrink); f.touch(PointerEventType.Release,shrink)
            assertTrue(f.text().isNotEmpty()); assertTrue(f.text().length<extended.length,"Start handle must reduce selection: extended=$extended reduced=${f.text()} at=$startHandle target=$shrink")
            assertTrue(originalEnd>start+7)
            f.tap(Offset(390f,680f)); assertTrue(f.selection.selectedTexts.isEmpty())
        }
    }

    @Test fun copyAndSelectAllUseCurrentPublicSelectionAndTranslationCapturesExactFragments() = runTest {
        Fixture(this).use { f ->
            fun action(label: String) = f.nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                (walk(it).any { child->child.config.getOrNull(SemanticsProperties.Text)?.any { t->t.text==label }==true } ||
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true) }
            f.select(6,9); f.menu()
            assertTrue(checkNotNull(action("Copy").config.getOrNull(SemanticsActions.OnClick)?.action).invoke()); f.pump()
            assertEquals("🌍.",f.copied?.text); assertTrue(f.selection.selectedTexts.isEmpty())
            f.select(0,5); f.menu()
            assertTrue(checkNotNull(action("Select all").config.getOrNull(SemanticsActions.OnClick)?.action).invoke()); f.pump()
            assertEquals(listOf(f.first,f.second),f.selection.selectedTexts.map { it.text })
            assertTrue(checkNotNull(action("Translate").config.getOrNull(SemanticsActions.OnClick)?.action).invoke()); f.pump()
            assertEquals(f.first+"\n"+f.second,f.translated); assertTrue(f.selection.selectedTexts.isEmpty())
        }
    }

}
