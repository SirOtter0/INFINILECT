// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import org.infinilect.app.collections.CollectionsController
import org.infinilect.app.imports.*
import org.infinilect.app.search.*
import org.infinilect.core.*
import kotlin.test.*

/** Real headless Compose measurement/semantics using the existing Desktop runtime.
 * Dimensions are scenario inputs, not expected pixel coordinates. This is not a device/IME test. */
@OptIn(ExperimentalComposeUiApi::class)
class SearchLayoutTest {
    private class Source : PublicationSource {
        override val id=SourceId("layout-fixture")
        val books=List(40) { Publication(PublicationId(id,"book-$it"),"Publication $it",PublicationType.BOOK) }
        override suspend fun search(query:String,pageToken:String?)=SearchPage(books,"opaque-next")
        override suspend fun getPublication(publicationId:PublicationId)=books.find {it.id==publicationId}
        override suspend fun loadResource(resource:PublicationResource):ResourceContent=error("Layout does not acquire bytes")
    }
    private class Fixture(width:Int,val height:Int,fontScale:Float=1f,sourceCount:Int=12) : AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val source=Source()
        val options=List(sourceCount) { SourceOption("Source $it catalog",source,true,true,true,true) }
        val session=ReadingSession(source,scope,true,epubReadingEnabled=true,pageReadingEnabled=true,pdfReadingEnabled=true)
        val collections=CollectionsController(null,scope){0L}
        val result=SearchResult("books",SearchPage(source.books,"opaque-next"),1)
        var state by mutableStateOf<SearchState>(SearchState.Results(result))
        private val viewport=SearchResultsViewport { LazyListState() }
        val position get()=viewport.forState(state)
        val scene=ImageComposeScene(width,height,Density(1f,fontScale))
        private var frame=0L
        init {
            scene.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize()) {
                        Text("App controls above Search")
                        SearchScreen(session,state,position,options,0,{},collections,
                            modifier=Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            draw()
        }
        fun draw(frames:Int=4) { repeat(frames) { scene.render(++frame*16_666_667L).close() } }
        fun nodes():List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun walk(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::walk)
            walk(owner.unmergedRootSemanticsNode)
        }
        fun text(value:String)=nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any {it.text==value}==true
        }
        fun reachable(node:SemanticsNode) {
            val bounds=node.boundsInRoot
            assertTrue(bounds.height>0 && bounds.bottom>0 && bounds.top<height,"Node must intersect the viewport: $bounds")
        }
        fun scrollTo(index:Int) { runBlocking { position.scrollToItem(index) };draw() }
        fun reachText(value:String) {
            repeat(8) {
                val bounds=text(value).boundsInRoot
                if(bounds.height>0 && bounds.bottom>0 && bounds.top<height) {reachable(text(value));return}
                runBlocking {position.scrollBy(height/2f)};draw()
            }
            fail("Feedback must remain reachable by scrolling: $value")
        }
        override fun close() { scene.close();session.close();collections.close();scope.cancel() }
    }

    @Test fun smallAndKeyboardReducedHeightsKeepResultsScrollableWithLongHeader() {
        for ((height,fontScale) in listOf(420 to 1f,280 to 1f,360 to 1.6f)) {
            Fixture(360,height,fontScale).use { f ->
                assertTrue(f.position.layoutInfo.viewportEndOffset>f.position.layoutInfo.viewportStartOffset)
                f.scrollTo(1);f.reachable(f.text("Publication 0"))
                f.scrollTo(40);f.reachable(f.text("Publication 39"))
                val scrollOwners=f.nodes().filter {it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null}
                assertEquals(2,scrollOwners.size) // Independent header and lazy result viewport.
                fun descendants(node:SemanticsNode):List<Int> = node.children.flatMap {listOf(it.id)+descendants(it)}
                assertTrue(scrollOwners[0].id !in descendants(scrollOwners[1]))
                assertTrue(scrollOwners[1].id !in descendants(scrollOwners[0]))
                val header=scrollOwners.first {it.config.getOrNull(SemanticsActions.ScrollToIndex)==null}
                assertTrue(header.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()>0)
                assertTrue(assertNotNull(header.config[SemanticsActions.ScrollBy].action).invoke(0f,10_000f))
                f.draw(60);f.reachable(f.text("Title, author, or keyword"))
            }
        }
    }
    @Test fun idleLoadingEmptyAndErrorFeedbackRemainReachable() {
        Fixture(360,280).use { f ->
            for ((state,message) in listOf(
                SearchState.Idle to "Enter a search to discover publications.",
                SearchState.Loading() to "Searching…",
                SearchState.Empty(f.result.copy(page=SearchPage(emptyList()))) to "No publications found for “books”.",
                SearchState.Error("Please try again.") to "Please try again.",
            )) {
                f.state=state;f.draw();f.scrollTo(0);f.reachText(message)
                assertTrue(f.position.layoutInfo.viewportEndOffset>f.position.layoutInfo.viewportStartOffset)
            }
        }
    }
    @Test fun loadingFailedPaginationAndNewResultsPreserveExistingViewportRules() {
        Fixture(360,420).use { f ->
            f.scrollTo(20);val old=f.position
            f.state=SearchState.Loading(f.result);f.draw();assertSame(old,f.position);assertEquals(20,f.position.firstVisibleItemIndex)
            f.state=SearchState.Error("Retry page.",f.result);f.draw();assertSame(old,f.position);assertEquals(20,f.position.firstVisibleItemIndex)
            f.scrollTo(41);f.reachable(f.text("Next page"))
            f.state=SearchState.Results(f.result.copy(generation=2));f.draw()
            assertNotSame(old,f.position);assertEquals(0,f.position.firstVisibleItemIndex)
        }
    }
    @Test fun desktopHasBoundedScrollableResultsAndAppKeepsImportControlsReachable() {
        Fixture(1280,800,sourceCount=2).use { f ->
            assertTrue(f.position.layoutInfo.viewportEndOffset>f.position.layoutInfo.viewportStartOffset)
            f.scrollTo(40);f.reachable(f.text("Publication 39"))
        }
        Fixture(360,280).use { f ->
            val importer=object:LocalPublicationImporter {
                override suspend fun import(selection:LocalFileSelection):Publication=error("No import action in layout test")
                override fun close(){}
                override suspend fun awaitClosed(){}
            }
            val owner=ApplicationSources(f.options,localImports=importer){}
            try {
                f.scene.setContent { App(owner,localFilePicker=object:LocalFilePicker {override suspend fun pick():LocalFileSelection?=null}) }
                f.draw();f.reachable(f.text("Import local file"))
                val list=f.nodes().first {it.config.getOrNull(SemanticsActions.ScrollToIndex)!=null}
                f.reachable(list)
                assertTrue(list.boundsInRoot.height>0)
            } finally { f.scene.setContent {};f.draw();owner.close();runBlocking {owner.awaitProgressClosed()} }
        }
    }
}
