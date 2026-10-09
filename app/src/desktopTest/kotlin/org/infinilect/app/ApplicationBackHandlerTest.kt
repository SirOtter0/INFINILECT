// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.epub.EpubReaderController
import org.infinilect.core.*

/** Real Compose snapshot/recomposition behavior without UI nodes, a display or Android.
 * This covers the shared binding, not the device's system Back dispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ApplicationBackHandlerTest {
    private val publication = Publication(PublicationId(SourceId("fixture"), "book"), "Title", PublicationType.BOOK)
    private class NoUiApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }
    private inner class Fixture(private val scope: TestScope) {
        val opening = MutableStateFlow<OpenPublicationState>(OpenPublicationState.Idle)
        val destination = MutableStateFlow(Destination.HOME)
        var enabled: Boolean? = null
        var backCalls = 0
        var callback: () -> Unit = {}
        var rendered: OpenPublicationState? = null
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val runner = scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        private val composition = Composition(NoUiApplier(), recomposer)
        private var frame = 0L
        private val doc = object : EpubDocument {
            override val publicationId = publication.id
            override val packagePath = EpubEntryPath("OPS/book.opf")
            override val metadata = EpubMetadata("fixture", "Title", listOf("en"), "2026-10-05T00:00:00Z")
            override val manifest = listOf(EpubManifestItem("chapter", EpubEntryPath("OPS/chapter.xhtml"), "application/xhtml+xml"))
            override val spine = listOf(EpubSpineItem("chapter"))
            override val navigationItemId = "nav"
            override suspend fun openResource(path: EpubEntryPath): ResourceContent = error("Binding test does not read bytes")
            override fun close() {}
        }
        private val reader = EpubReaderController(doc, ReadingProgressId(publication.id, "epub", PublicationFormat.EPUB), scope.backgroundScope)
        val epub = OpenPublicationState.EpubReady(reader, publication)
        private val pageReader = org.infinilect.app.reader.page.PageReaderController(org.infinilect.app.reader.page.TestPageDocument(), scope.backgroundScope)
        val page = OpenPublicationState.PageReady(pageReader, publication)
        @Composable fun Handler(isEnabled: Boolean, onBack: () -> Unit) {
            SideEffect { enabled = isEnabled; callback = onBack }
        }
        fun install() = composition.setContent {
            ApplicationBackHandler(opening, destination, { backCalls++ }, { enabled, back -> Handler(enabled, back) })
        }
        fun installOldBinding() = composition.setContent {
            OldBindingWithReader(opening, { enabled, back -> Handler(enabled, back) }, { rendered = it })
        }
        fun settle() {
            // Flush state collectors, snapshot notifications and frame-driven recompositions.
            repeat(4) {
                scope.runCurrent()
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                clock.sendFrame(++frame)
                scope.runCurrent()
            }
        }
        suspend fun close() {
            composition.dispose(); recomposer.cancel(); runner.cancelAndJoin(); reader.close(); pageReader.close()
        }
    }
    @Composable private fun OldBindingWithReader(
        flow: MutableStateFlow<OpenPublicationState>,
        handler: @Composable (Boolean, () -> Unit) -> Unit,
        rendered: (OpenPublicationState) -> Unit,
    ) {
        val observed = flow.collectAsState()
        // Reproduces App's previous unobserved ApplicationSession.handlesBack() read.
        handler(flow.value.handlesBack(), {})
        ReaderChild(observed, rendered)
    }
    @Composable private fun ReaderChild(state: State<OpenPublicationState>, rendered: (OpenPublicationState) -> Unit) {
        val current = state.value
        SideEffect { rendered(current) }
    }
    private suspend fun TestScope.use(action: suspend Fixture.() -> Unit) {
        val fixture = Fixture(this)
        try { fixture.action() } finally { fixture.close() }
    }

    @Test fun unobservedFlowReadLeavesOldHandlerDisabledWhenOnlyReaderRecomposes() = runTest { use {
        installOldBinding(); settle(); assertEquals(false, enabled)
        opening.value = epub; settle()
        assertSame(epub, rendered)
        assertEquals(false, enabled, "The reader updated but the old parent callback did not")
    } }
    @Test fun epubReadyEnablesPlatformBackAndReturningToRootDisablesIt() = runTest { use {
        install(); settle(); assertEquals(false, enabled)
        opening.value = epub; settle(); assertEquals(true, enabled)
        callback(); assertEquals(1, backCalls)
        opening.value = OpenPublicationState.Idle; settle(); assertEquals(false, enabled)
    } }
    @Test fun pageReadyEnablesReactiveAndroidBackAndRootStillExits() = runTest { use {
        install(); settle(); assertEquals(false, enabled)
        opening.value = page; settle(); assertEquals(true, enabled)
        callback(); assertEquals(1, backCalls)
        opening.value = OpenPublicationState.Idle; settle(); assertEquals(false, enabled)
    } }
    @Test fun textLoadingAndErrorKeepExistingBackSemantics() = runTest { use {
        install(); settle()
        for (state in listOf(OpenPublicationState.Loading(publication),
            OpenPublicationState.Ready(TextDocument(publication.id, "Title", "Text")),
            OpenPublicationState.Error(publication, "Safe error"))) {
            opening.value = state; settle(); assertEquals(true, enabled)
            callback()
            opening.value = OpenPublicationState.Idle; settle(); assertEquals(false, enabled)
        }
        assertEquals(3, backCalls)
    } }
    @Test fun collectionDestinationsHandleBackButHomeRootKeepsSystemExit() = runTest { use {
        install(); settle()
        for (target in listOf(Destination.LIBRARY, Destination.HISTORY)) {
            destination.value = target; settle(); assertEquals(true, enabled)
            opening.value = epub; settle(); assertEquals(true, enabled)
            opening.value = OpenPublicationState.Idle; settle(); assertEquals(true, enabled)
            destination.value = Destination.HOME; settle(); assertEquals(false, enabled)
        }
        assertEquals(0, backCalls)
    } }
    @Test fun rapidReaderReplacementUsesCurrentNavigationState() = runTest { use {
        install(); settle()
        opening.value = epub
        opening.value = OpenPublicationState.Idle
        settle(); assertEquals(false, enabled)
        opening.value = OpenPublicationState.Loading(publication)
        opening.value = epub
        settle(); assertEquals(true, enabled)
        callback(); assertEquals(1, backCalls)
    } }
}
