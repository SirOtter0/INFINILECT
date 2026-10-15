// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.foundation.text.contextmenu.provider.*
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jetbrains.compose.resources.getString
import org.infinilect.app.ui.*
import org.infinilect.app.reader.epub.*
import java.util.Locale
import kotlin.test.*

/** Headless Desktop integration; does not claim Android-device verification. */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationUxTest {
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    private fun TestScope.pump(scene: ImageComposeScene) {
        repeat(12) { runCurrent(); scene.render(testScheduler.currentTime * 1_000_000).close() }; runCurrent()
    }
    @Test fun actualComposeResourcesResolveEnglishSpanishCatalanAndFallback() = runBlocking {
        deviceInterfaceLanguage() // Capture the system locale before the test's explicit changes.
        val original = Locale.getDefault()
        try {
            val expected = mapOf("en" to "Translate", "es" to "Traducir", "ca" to "Tradueix")
            for ((code, value) in expected) {
                applyInterfaceLanguage(code)
                assertEquals(value, getString(UiText.TRANSLATE.resource))
                assertTrue(getString(UiText.MODEL_DOWNLOAD_QUESTION.resource).contains("%1\$s"))
            }
            Locale.setDefault(Locale.FRENCH); assertEquals("Translate", getString(UiText.TRANSLATE.resource))
        } finally { Locale.setDefault(original) }
    }
    @Test fun publicSelectionProviderShowsOneDirectTranslateIconCopyAndSelectAll() = runTest {
        val selection = SelectionState(); var provider: TextContextMenuProvider? = null; var captured: String? = null
        val scene = ImageComposeScene(400, 700, Density(1f), coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            scene.setContent { MaterialTheme {
                EpubSelectionToolbar(selection, true, { captured = selection.selectedTexts.joinToString("\n") { it.text }; selection.clear() }) {
                    provider = LocalTextContextMenuToolbarProvider.current
                    SelectionContainer(state = selection) { Text("Hello 🌍", Modifier.fillMaxSize()) }
                }
            } }
            pump(scene); selection.selectAll(); pump(scene)
            assertEquals("Hello 🌍", selection.selectedTexts.single().text)
            val menu = launch { checkNotNull(provider).showTextContextMenu(object : TextContextMenuDataProvider {
                override fun position(destinationCoordinates: LayoutCoordinates) = Offset(10f, 80f)
                override fun contentBounds(destinationCoordinates: LayoutCoordinates) = Rect(10f, 80f, 150f, 110f)
                override fun data() = TextContextMenuData.Empty
            }) }
            pump(scene)
            val actions = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Translate") == true }
            assertEquals(1, actions.size)
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Copy" } == true })
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Select all" } == true })
            assertTrue(checkNotNull(actions.single().config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            pump(scene); menu.join(); assertEquals("Hello 🌍", captured); assertTrue(selection.selectedTexts.isEmpty())
        } finally { scene.close() }
    }
    @Test fun epubNavigationUsesAccessibleArrowsAndPreservesDisabledState() = runTest {
        var next = 0
        val scene = ImageComposeScene(360, 180, Density(1f, 1.4f), coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            scene.setContent { MaterialTheme {
                EpubNavigationBar(1, 26, .1, false, true, {}, { next++ }, {}, {}, FocusRequester(), FocusRequester())
            } }
            pump(scene)
            val previous = nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Previous") == true }
            assertTrue(previous.config.contains(SemanticsProperties.Disabled))
            val forward = nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Next") == true }
            assertTrue(checkNotNull(forward.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()); assertEquals(1, next)
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text in setOf("Previous", "Next") } == true })
        } finally { scene.close() }
    }
}
