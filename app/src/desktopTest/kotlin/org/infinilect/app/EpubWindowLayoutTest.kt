// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class EpubWindowLayoutTest {
    private val path = EpubEntryPath("OPS/chapter.xhtml")
    private val publication = PublicationId(SourceId("original"), "window-layout")
    private inner class Doc : EpubDocument {
        override val publicationId = publication
        override val packagePath = EpubEntryPath("OPS/package.opf")
        override val metadata = EpubMetadata("original", "Original publication", listOf("en"), null)
        override val manifest = listOf(EpubManifestItem("chapter",path,"application/xhtml+xml"))
        override val spine = listOf(EpubSpineItem("chapter"))
        override val navigationItemId = "nav"
        override suspend fun openResource(path:EpubEntryPath):ResourceContent = error("No external acquisition")
        override fun close() {}
    }
    private fun text(i:Int)="Original page ${i.toString().padStart(4,'0')} — A simple original reading passage."
    private inner class Parser : EpubParser {
        var gate:CompletableDeferred<Unit>?=null
        var navigationGate:CompletableDeferred<Unit>?=null
        var failNavigation=false
        override suspend fun chapter(document:EpubDocument,path:EpubEntryPath):EpubChapter=error("Window API required")
        override suspend fun window(document:EpubDocument,path:EpubEntryPath,request:EpubWindowRequest):EpubChapter {
            val index=when(request){is EpubWindowRequest.Block->request.index;is EpubWindowRequest.Anchor->request.value.toInt();is EpubWindowRequest.Locator->request.value.elementPath.last();EpubWindowRequest.End->3000}
            if(request is EpubWindowRequest.Anchor){navigationGate?.await();if(failNavigation)throw IllegalStateException("private diagnostic")}
            if(index==256)gate?.await()
            val start=index/128*128;val points=text(0).epubCodePoints()
            return EpubChapter(path,(start until minOf(start+128,3001)).map{EpubBlock(listOf(0,it),0,EpubBlockKind.PARAGRAPH,listOf(EpubRun(text(it))),it*points)},
                mapOf("2800" to EpubPosition(listOf(0,2800),0)),start,3001,3001*points)
        }
        override suspend fun toc(document:EpubDocument)=listOf(EpubTocEntry("Later",EpubTarget(path,"2800"),0))
    }
    private class Scene(val scope:TestScope):AutoCloseable {
        val scene=ImageComposeScene(700,600,Density(1f),coroutineContext=StandardTestDispatcher(scope.testScheduler))
        var mounted by mutableStateOf(true)
        fun content(body: @Composable () -> Unit){scene.setContent{if(mounted)body()};pump()}
        fun pump(){repeat(10){scope.runCurrent();scene.render(scope.testScheduler.currentTime*1_000_000).close()};scope.runCurrent()}
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        fun nodes()=scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
        fun firstPassage():Pair<String,Float>{
            val viewport=nodes().first{it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null}
            val node=nodes().filter{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text.startsWith("Original page ") }==true && it.positionInRoot.y+it.size.height>viewport.positionInRoot.y+12}.minBy{it.positionInRoot.y}
            return node.config[SemanticsProperties.Text].single().text to node.positionInRoot.y
        }
        fun scroll(pixels:Float){
            val n=nodes().first{it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null}
            assertTrue(n.config[SemanticsActions.ScrollBy].action!!.invoke(0f,pixels))
            repeat(30){scope.advanceTimeBy(16);pump()}
        }
        fun click(label:String){
            val n=nodes().first{!it.config.contains(SemanticsProperties.Disabled) && it.config.getOrNull(SemanticsActions.OnClick)?.action!=null && walk(it).any{child->child.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}}
            assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke());pump()
        }
        fun remount(){mounted=false;pump();mounted=true;pump()}
        override fun close(){scene.close();scope.runCurrent()}
    }
    private fun ready(reader:EpubReaderController)=assertIs<EpubReaderState.Ready>(reader.state.value)
    @Test fun rollingBufferPreservesExactVisibleParagraphAndPixelPosition()=runTest {
        val parser=Parser();val reader=EpubReaderController(Doc(),ReadingProgressId(publication,"book",PublicationFormat.EPUB),backgroundScope,parser)
        try{
            reader.initialize(null)
            Scene(this).use{f->
                f.content{EpubReader(reader,false,{},"Back")}
                parser.gate=CompletableDeferred();f.scroll(6500f)
                val before=f.firstPassage();val presentation=ready(reader).presentation
                assertEquals(0,ready(reader).chapter.startBlock)
                parser.gate!!.complete(Unit);f.pump()
                val after=f.firstPassage()
                assertEquals(128,ready(reader).chapter.startBlock);assertEquals(presentation,ready(reader).presentation)
                assertEquals(before.first,after.first);assertTrue(kotlin.math.abs(before.second-after.second)<1f)
                assertTrue(reader.retainedBlocks<=256)
                f.scroll(-3000f);assertTrue(reader.retainedBlocks<=256)
                val labels=f.nodes().mapNotNull{it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text}.filter{it.startsWith("Original page ")}
                assertEquals(labels.distinct(),labels)
            }
        }finally{reader.close()}
    }
    @Test fun deepWindowSurvivesRemountFontMarginsAndRotationGeometry()=runTest {
        val reader=EpubReaderController(Doc(),ReadingProgressId(publication,"book",PublicationFormat.EPUB),backgroundScope,Parser())
        try{
            reader.initialize(null);reader.navigate(EpubTarget(path,"2800"));runCurrent()
            Scene(this).use{f->
                f.content{EpubReader(reader,false,{},"Back")}
                val initial=ready(reader);reader.report(initial.ticket,initial.initialPosition.first,8)
                val locator=initial.chapter.locator(initial.initialPosition.first,8)
                f.remount()
                for((width,height)in listOf(350 to 700,1000 to 400,700 to 600)){
                    reader.presentationChanged(EpubReaderSettings(fontSize=26,margin=32));f.scene.constraints=Constraints.fixed(width,height);f.pump()
                    val current=ready(reader);println("REFLOW $width/$height start=${current.chapter.startBlock} initial=${current.initialPosition} first=${f.firstPassage()}");assertEquals(locator,current.chapter.locator(current.initialPosition.first,current.initialPosition.second))
                    assertTrue(f.nodes().any{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==text(2800)}==true})
                }
            }
        }finally{reader.close()}
    }
    @Test fun existingPreviousNextControlsTraverseWindowsWithoutNewWindowControls()=runTest {
        val reader=EpubReaderController(Doc(),ReadingProgressId(publication,"book",PublicationFormat.EPUB),backgroundScope,Parser())
        try{
            reader.initialize(null)
            Scene(this).use{f->
                f.content{EpubReader(reader,false,{},"Back")};f.click("Next")
                assertEquals(128,ready(reader).chapter.startBlock)
                assertTrue(f.nodes().any{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==text(128)}==true})
                f.click("Previous");assertEquals(0,ready(reader).chapter.startBlock)
                assertTrue(f.nodes().any{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==text(127)}==true})
            }
        }finally{reader.close()}
    }
    @Test fun slowNavigationKeepsPassageAndShowsOnlyDelayedOverlayLoading()=runTest {
        val parser=Parser();val reader=EpubReaderController(Doc(),ReadingProgressId(publication,"book",PublicationFormat.EPUB),backgroundScope,parser)
        try{
            reader.initialize(null)
            Scene(this).use{f->
                f.content{EpubReader(reader,false,{},"Back")};val before=f.firstPassage()
                parser.navigationGate=CompletableDeferred();reader.navigate(EpubTarget(path,"2800"));f.pump()
                assertIs<EpubReaderState.Loading>(reader.state.value);assertEquals(before,f.firstPassage())
                fun loading()=f.nodes().any{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Preparing EPUB passage")==true}
                advanceTimeBy(100);f.pump();assertFalse(loading())
                advanceTimeBy(100);f.pump();assertTrue(loading());assertEquals(before,f.firstPassage())
                parser.navigationGate!!.complete(Unit);f.pump()
                assertFalse(loading());assertTrue(f.firstPassage().first.startsWith("Original page 2800"))
            }
        }finally{reader.close()}
    }
    @Test fun recoverableFailureKeepsReadablePassageAndRetryRestoresExactDestination()=runTest {
        val parser=Parser();val reader=EpubReaderController(Doc(),ReadingProgressId(publication,"book",PublicationFormat.EPUB),backgroundScope,parser)
        try{
            reader.initialize(null)
            Scene(this).use{f->
                f.content{EpubReader(reader,false,{},"Back")};val before=f.firstPassage().first
                parser.failNavigation=true;reader.navigate(EpubTarget(path,"2800"));f.pump()
                assertIs<EpubReaderState.Error>(reader.state.value);assertEquals(before,f.firstPassage().first)
                assertTrue(f.nodes().any{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text=="Retry"}==true})
                parser.failNavigation=false;f.click("Retry")
                assertTrue(f.firstPassage().first.startsWith("Original page 2800"))
                assertFalse(f.nodes().any{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text=="Retry"}==true})
            }
        }finally{reader.close()}
    }
}
