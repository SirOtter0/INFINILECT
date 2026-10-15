// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import kotlin.test.*

/** Public Compose API feasibility checks, not a translator or a reader integration claim. */
@OptIn(ExperimentalComposeUiApi::class)
class EpubSelectionFoundationTest {
    private fun ImageComposeScene.layout(frame: Long) {
        repeat(4) { render((frame + it) * 16_666_667L).close() }
    }

    @Test fun selectedAnnotatedWordCanBeReadWithoutCopyingOrReadingClipboard() {
        val selection = SelectionState()
        val source = buildAnnotatedString {
            append("Hello ")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("🌍world") }
            append(". Original paragraph.")
        }
        val scene = ImageComposeScene(640, 320)
        try {
            scene.setContent { SelectionContainer(selection) { BasicText(source) } }
            scene.layout(1)
            assertTrue(selection.selectedTexts.isEmpty())
            selection.select(TextRange(6, 13)) // complete surrogate pair + word, not chapter text
            scene.layout(5)
            val selected = selection.selectedTexts.single()
            assertEquals("🌍world", selected.text)
            assertTrue(selected.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
            assertEquals("Hello 🌍world. Original paragraph.", source.text)
            selection.clear(); scene.layout(9)
            assertTrue(selection.selectedTexts.isEmpty())
        } finally { scene.close() }
    }

    @Test fun selectionFragmentsStayInCurrentContainerAndNewChapterStartsUnselected() {
        var chapter by mutableIntStateOf(0)
        var active: SelectionState? = null
        val scene = ImageComposeScene(640, 480)
        try {
            scene.setContent {
                key(chapter) {
                    val selection = rememberSelectionState()
                    SideEffect { active = selection }
                    SelectionContainer(selection) {
                        Column {
                            if (chapter == 0) {
                                BasicText("First paragraph.")
                                BasicText("Second paragraph 🌍.")
                            } else BasicText("Different chapter: no prior selection.")
                        }
                    }
                }
            }
            scene.layout(1)
            val original = assertNotNull(active)
            original.select(TextRange(6, "First paragraph.".length + 6))
            scene.layout(5)
            assertEquals(listOf("paragraph.", "Second"), original.selectedTexts.map { it.text })
            original.clear(); chapter = 1; scene.layout(9)
            val replacement = assertNotNull(active)
            assertNotSame(original, replacement)
            assertTrue(replacement.selectedTexts.isEmpty())
            assertEquals(listOf("Different chapter: no prior selection."), replacement.getSelectableTexts().map { it.text })
        } finally { scene.close() }
    }
}
