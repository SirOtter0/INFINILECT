// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import kotlin.test.*

/** Real reader UI + deterministic engine; headless Desktop, never device/native acceptance. */
@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class EpubTranslationInterfaceTest {
    private val path = EpubEntryPath("OPS/chapter.xhtml")
    private val id = PublicationId(SourceId("fixture"), "translation-ui")
    private inner class Document : EpubDocument {
        override val publicationId = id
        override val packagePath = EpubEntryPath("OPS/package.opf")
        override val metadata = EpubMetadata("test", "Translation fixture", listOf("en"), null)
        override val manifest = listOf(EpubManifestItem("chapter", path, "application/xhtml+xml"))
        override val spine = listOf(EpubSpineItem("chapter"))
        override val navigationItemId = "nav"
        override suspend fun openResource(path: EpubEntryPath): ResourceContent = error("No resource I/O")
        override fun close() = Unit
    }
    private inner class Parser(val withImage: Boolean = false) : EpubParser {
        var calls = 0
        override suspend fun toc(document: EpubDocument) = emptyList<EpubTocEntry>()
        override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter {
            calls++
            return EpubChapter(path, listOf(EpubBlock(listOf(0, 0), 0, EpubBlockKind.PARAGRAPH,
                listOf(EpubRun("Hello 🌍", emphasis = true)), 0)) +
                (if (withImage) listOf(EpubBlock(listOf(0, 1), 0, EpubBlockKind.IMAGE, listOf(EpubRun("[Image unavailable]", translatable = false)), 7)) else emptyList()), emptyMap())
        }
    }
    private class Engine : EpubTranslationEngine {
        override val identity = "deterministic-test-v1"
        override val unavailable: String? = null
        override val models = listOf(TranslationModel(TranslationPair("en", "es"), "fixture", 10))
        override suspend fun installed(model: TranslationModel) = true
        override suspend fun install(model: TranslationModel, progress: (Long, Long) -> Unit) = Unit
        override suspend fun remove(model: TranslationModel) = Unit
        override suspend fun translate(model: TranslationModel, text: String) = "Hola 🌍"
        override fun close() = Unit
    }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    private fun visible(scene: ImageComposeScene, label: String) = nodes(scene).any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
    }
    private fun TestScope.pump(scene: ImageComposeScene) {
        repeat(12) { runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close() }; runCurrent()
    }
    @Test fun productionSelectionActionShowsRealCapturedTextResultAndCloseKeepsPosition() = runTest {
        val service = EpubTranslationService(Engine()).also { it.defaultLanguage.value = "es" }; val parser = Parser(withImage = true)
        val reader = EpubReaderController(Document(), ReadingProgressId(id, "book", PublicationFormat.EPUB), backgroundScope, parser, translationService = service)
        val selection = SelectionState()
        val scene = ImageComposeScene(400, 700, Density(1f), coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            reader.initialize(null)
            scene.setContent { EpubReader(reader, false, {}, "Back", selectionState = selection, applicationDark = false) }
            pump(scene)
            assertEquals(listOf("Hello 🌍"), selection.getSelectableTexts().map { it.text })
            selection.select(TextRange(0, "Hello 🌍".length)); pump(scene)
            assertEquals("Hello 🌍", selection.selectedTexts.single().text)
            val action = nodes(scene).flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
                .single { it.label == "Translate selection" }
            assertTrue(action.action()); pump(scene)
            assertEquals("Hello 🌍", reader.translation.state.value.selection)
            assertTrue(visible(scene, "Hola 🌍")); assertTrue(visible(scene, "Copy translation"))
            assertTrue(selection.selectedTexts.isEmpty())
            val locator = assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition
            reader.translation.dismissSelection(); pump(scene)
            assertFalse(visible(scene, "Copy translation")); assertEquals(locator, assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
            assertEquals(1, parser.calls)
        } finally { scene.close(); reader.close(); service.close() }
    }
    @Test fun translatedModeReplacesTextInsideReaderAndOriginalRestoresWithoutReparse() = runTest {
        val service = EpubTranslationService(Engine()).also { it.defaultLanguage.value = "es" }; val parser = Parser()
        val reader = EpubReaderController(Document(), ReadingProgressId(id, "book", PublicationFormat.EPUB), backgroundScope, parser, translationService = service)
        val scene = ImageComposeScene(900, 500, Density(1.5f, 1.3f), coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            reader.initialize(null)
            scene.setContent { EpubReader(reader, false, {}, "Back", applicationDark = true) }
            pump(scene); assertTrue(visible(scene, "Hello 🌍"))
            reader.translatedReading(true); pump(scene)
            assertTrue(visible(scene, "Hola 🌍")); assertFalse(visible(scene, "Hello 🌍"))
            assertTrue(reader.translation.state.value.blocks!!.single().runs.single().emphasis)
            reader.presentationChanged(reader.settings.value.copy(fontSize = 28, margin = 36)); pump(scene)
            assertTrue(visible(scene, "Hola 🌍"))
            reader.translatedReading(false); pump(scene)
            assertTrue(visible(scene, "Hello 🌍")); assertFalse(visible(scene, "Hola 🌍")); assertEquals(1, parser.calls)
        } finally { scene.close(); reader.close(); service.close() }
    }
    @Test fun activeSelectionConsumesBackEscapeAndTypographyReplacementBeforeReaderNavigation() = runTest {
        val reader = EpubReaderController(Document(), ReadingProgressId(id, "book", PublicationFormat.EPUB), backgroundScope, Parser())
        val selection = SelectionState(); var exited = 0; var backEnabled = false; var backAction: () -> Unit = {}
        val scene = ImageComposeScene(400, 700, Density(1f, 1.8f), coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            reader.initialize(null)
            scene.setContent { EpubReader(reader, false, { exited++ }, "Back", selectionState = selection,
                backHandler = { enabled, action -> SideEffect { backEnabled = enabled; backAction = action } }) }
            pump(scene); val initial = assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition
            selection.select(TextRange(0,5)); pump(scene); assertTrue(backEnabled)
            backAction(); pump(scene); assertTrue(selection.selectedTexts.isEmpty()); assertEquals(0,exited)
            selection.select(TextRange(0,5)); pump(scene)
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key=androidx.compose.ui.input.key.Key.Escape,type=androidx.compose.ui.input.key.KeyEventType.KeyDown))
            pump(scene); assertTrue(selection.selectedTexts.isEmpty()); assertEquals(0,exited)
            selection.select(TextRange(0,5)); pump(scene)
            reader.presentationChanged(reader.settings.value.copy(fontSize=30)); pump(scene)
            assertTrue(selection.selectedTexts.isEmpty()); assertEquals(initial,assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
            selection.select(TextRange(0,5)); pump(scene); assertEquals("Hello",selection.selectedTexts.single().text)
            scene.setContent {}; pump(scene); assertTrue(selection.selectedTexts.isEmpty())
            scene.setContent { EpubReader(reader,false,{},"Back",selectionState=selection) }; pump(scene)
            assertTrue(selection.selectedTexts.isEmpty()); assertEquals(initial,assertIs<EpubReaderState.Ready>(reader.state.value).initialPosition)
        } finally { scene.close(); reader.close() }
    }

    @Test fun dismissalTapDoesNotToggleControlsAndNewChapterHasNoPreviousSelection() = runTest {
        val second=EpubEntryPath("OPS/second.xhtml")
        val document=object: EpubDocument by Document() {
            override val manifest=listOf(EpubManifestItem("chapter",path,"application/xhtml+xml"),EpubManifestItem("second",second,"application/xhtml+xml"))
            override val spine=listOf(EpubSpineItem("chapter"),EpubSpineItem("second"))
        }
        val reader=EpubReaderController(document,ReadingProgressId(id,"book",PublicationFormat.EPUB),backgroundScope,Parser())
        val selection=SelectionState(); val scene=ImageComposeScene(400,700,coroutineContext=StandardTestDispatcher(testScheduler))
        try {
            reader.initialize(null); scene.setContent { EpubReader(reader,false,{},"Back",selectionState=selection) }; pump(scene)
            val ticket=assertIs<EpubReaderState.Ready>(reader.state.value).ticket
            selection.select(TextRange(0,5)); pump(scene)
            fun tap() {
                val at=androidx.compose.ui.geometry.Offset(200f,650f)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press,at,timeMillis=testScheduler.currentTime,type=androidx.compose.ui.input.pointer.PointerType.Touch)
                testScheduler.advanceTimeBy(30)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release,at,timeMillis=testScheduler.currentTime,type=androidx.compose.ui.input.pointer.PointerType.Touch); pump(scene)
            }
            tap(); assertTrue(selection.selectedTexts.isEmpty()); assertEquals(ticket,assertIs<EpubReaderState.Ready>(reader.state.value).ticket)
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Hide reading controls")==true })
            tap(); assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Hide reading controls")==true })
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key=androidx.compose.ui.input.key.Key.A,type=androidx.compose.ui.input.key.KeyEventType.KeyDown,isCtrlPressed=true)); pump(scene)
            assertEquals("Hello 🌍",selection.selectedTexts.single().text)
            reader.next(); pump(scene); assertEquals(1,assertIs<EpubReaderState.Ready>(reader.state.value).spineIndex)
            assertTrue(selection.selectedTexts.isEmpty())
        } finally { scene.close(); reader.close() }
    }

}
