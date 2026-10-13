// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.ReaderAppearance
import org.infinilect.app.media.*
import org.infinilect.core.*
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/** Shared UI with original generated artwork; synthetic pointer/render clocks, no sleeps. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ModernComicInterfaceTest {
    private class Comic : PageDocument {
        override val publicationId=PublicationId(SourceId("original-fixture"),"pr30")
        override val progressId=ReadingProgressId(publicationId,"page-sequence",PublicationFormat.PAGES)
        override val title="The geometric journey"
        override val pages=(0..6).map { i->PageEntry(PublicationResource(publicationId,"page-$i",PublicationFormat.PAGES,"image/png",pageDimensions=if(i==3)PageDimensions(640,360) else PageDimensions(320,480))) }
        val rasters=mutableListOf<Raster>()
        var openIndex=0
        val images=pages.mapIndexed { i,p ->
            val image=BufferedImage(p.dimensions.width,p.dimensions.height,BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let {g->
                g.color=listOf(Color(30,91,105),Color(167,65,68),Color(58,118,93),Color(59,73,125))[i%4];g.fillRect(0,0,image.width,image.height)
                g.color=Color(255,255,255,100);repeat(5){j->g.fillOval(25+j*50,160+j*35,110,110)}
                g.color=Color.WHITE;g.font=Font("SansSerif",Font.BOLD,34);g.drawString("PAGE ${i+1}",24,64)
                g.font=Font("SansSerif",Font.PLAIN,19);g.drawString(if(i==0)"COVER" else if(i==3)"WIDE PANORAMA" else if(i==6)"FINAL PAGE" else "ORIGINAL ARTWORK",24,101)
                g.drawRect(12,12,image.width-25,image.height-25);g.dispose()
            }
            rasters+=Raster(image.width,image.height,image.getRGB(0,0,image.width,image.height,null,0,image.width))
            ByteArrayOutputStream().use {out->ImageIO.write(image,"PNG",out);image.flush();out.toByteArray()}
        }
        var closes=0;var handles=0
        override suspend fun openPage(page:PageEntry):ResourceContent {
            openIndex=pages.indexOf(page);val bytes=images[openIndex];handles++
            return object:ResourceContent {override val sizeBytes=bytes.size.toLong();var at=0;var closed=false
                override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {if(at==bytes.size)return -1;val n=minOf(length,bytes.size-at);bytes.copyInto(buffer,offset,at,at+n);at+=n;return n}
                override fun close(){if(!closed){closed=true;handles--}}
            }
        }
        override fun close(){closes++}
    }
    private class Fixture(val test:TestScope,val width:Int=390,val height:Int=780,val fontScale:Float=1f):AutoCloseable {
        val document=Comic()
        // Deterministic artwork adapter isolates UI clocks from platform native decode scheduling.
        // Existing CbzPagePreparer/SpreadRaster suites retain native decode and subsampling coverage.
        val native=object:RasterDecoder {override suspend fun decode(bytes:ByteArray,mediaType:String)=document.rasters[document.openIndex]}
        val reader=PageReaderController(document,test.backgroundScope,decoder=native,decodeDispatcher=StandardTestDispatcher(test.testScheduler))
        val scene=ImageComposeScene(width,height,Density(1f,fontScale),coroutineContext=UnconfinedTestDispatcher(test.testScheduler))
        var appMode by androidx.compose.runtime.mutableStateOf(org.infinilect.app.ui.ApplicationThemeMode.SYSTEM)
        var systemDark by androidx.compose.runtime.mutableStateOf(width>=600)
        var controlsColors:androidx.compose.material.Colors?=null
        var appearance:ReaderAppearance?=null;var back:(()->Unit)?=null;var exits=0
        suspend fun start(){reader.initialize(null);scene.setContent {MaterialTheme(colors=org.infinilect.app.ui.applicationColors(systemDark)) {PageReader(reader,false,{exits++},"Back",backHandler={enabled,action->back=if(enabled)action else null},appearanceChanged={appearance=it},controlsDark=appMode.isDark(systemDark),publicationActions={val colors=MaterialTheme.colors;androidx.compose.runtime.SideEffect{controlsColors=colors}})}};await(0)}
        fun draw(n:Int=6){repeat(n){test.advanceTimeBy(16);test.runCurrent();scene.render(test.testScheduler.currentTime*1_000_000).close()};test.runCurrent()}
        fun nodes():List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+(if(n.config.isClearingSemantics)emptyList()else n.children.flatMap(::walk))
            walk(owner.unmergedRootSemanticsNode)
        }
        fun text(label:String):SemanticsNode? = nodes().lastOrNull{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true&&it.config.getOrNull(SemanticsActions.OnClick)!=null}
            ?:nodes().firstOrNull{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}
        fun art(index:Int)=nodes().firstOrNull{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Page ${index+1} of 7")==true}
        suspend fun await(anchor:Int){repeat(400){draw();if(reader.state.value.transition==null&&reader.state.value.position.index==anchor&&reader.spread().indices.all{art(it)!=null})return;test.advanceTimeBy(16);yield();Thread.yield()};fail("Spread $anchor not presented")}
        fun click(label:String){
            if(text(label)==null) {
                val panel=when(label){"Left-to-right","Right-to-left (manga)","Vertical","Webtoon","Single page","Double page"->"Reading mode";"Fit screen","Fit width","Fit height","Zoom in","Zoom out","Reset zoom"->"Page fitting";else->null}
                if(panel!=null)click(panel)
            }
            var n=assertNotNull(text(label))
            if(n.boundsInRoot.top>=height || n.boundsInRoot.bottom<=0) {
                val expand=nodes().firstOrNull{it.config.getOrNull(SemanticsActions.Expand)!=null}
                expand?.config?.getOrNull(SemanticsActions.Expand)?.action?.invoke();draw(24);n=assertNotNull(text(label))
            }
            repeat(5) { if(n.boundsInRoot.height<=0 || n.boundsInRoot.top>=height || n.boundsInRoot.bottom<=0) {
                nodes().lastOrNull{it.config.getOrNull(SemanticsActions.ScrollBy)!=null}?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f,180f)
                draw(24);n=assertNotNull(text(label))
            } }
            assertTrue(n.boundsInRoot.height>0&&n.boundsInRoot.bottom>0&&n.boundsInRoot.top<height);tap(n.boundsInRoot.center);draw(12)}
        fun tap(at:Offset){scene.sendPointerEvent(PointerEventType.Press,at);scene.sendPointerEvent(PointerEventType.Release,at);draw()}
        fun center(){tap(Offset(width*.5f,height*.5f));draw(24)}
        fun key(key:Key){scene.sendKeyEvent(KeyEvent(key=key,type=KeyEventType.KeyDown));draw()}
        fun resize(w:Int,h:Int){scene.constraints=Constraints.fixed(w,h);draw()}
        fun preview(name:String){System.getenv("INFINILECT_COMIC_PREVIEW_DIRECTORY")?.let {path->val dir=java.nio.file.Path.of(path);java.nio.file.Files.createDirectories(dir);scene.render(test.testScheduler.currentTime*1_000_000).use {image->java.nio.file.Files.write(dir.resolve(name),assertNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use{it.bytes})}}}
        override fun close(){scene.close();reader.close();test.runCurrent();assertNull(appearance);assertEquals(1,document.closes);assertEquals(0,document.handles)}
    }
    @Test fun applicationOverridesSystemAndAutomaticFollowsResolutionWithoutChangingPage()=runTest {Fixture(this).use {f->
        f.start();f.reader.navigate(2);f.await(2)
        for((mode,system,expected)in listOf(Triple(org.infinilect.app.ui.ApplicationThemeMode.LIGHT,true,true),Triple(org.infinilect.app.ui.ApplicationThemeMode.DARK,false,false),Triple(org.infinilect.app.ui.ApplicationThemeMode.SYSTEM,true,false),Triple(org.infinilect.app.ui.ApplicationThemeMode.SYSTEM,false,true))) {
            f.appMode=mode;f.systemDark=system;f.reader.hideControls();f.draw(24);f.center();f.click("Settings")
            assertEquals(expected,assertNotNull(f.controlsColors).isLight);assertEquals(2,f.reader.state.value.position.index)
            assertEquals(!expected,assertNotNull(f.appearance).dark);f.key(Key.Escape);f.draw(24)
            f.reader.hideControls();f.draw();assertEquals(ReaderAppearance(true,COMIC_BACKGROUND),f.appearance)
        }
    }}
    @Test fun iconsReplacePersistentLabelsAndHaveAccessibleTouchTargets()=runTest {Fixture(this).use {f->
        f.start();f.center()
        for(label in listOf("Back","Previous","Next","Reading mode","Page fitting","Settings")) {
            val node=assertNotNull(f.text(label));assertTrue(node.boundsInRoot.width>=47&&node.boundsInRoot.height>=47,label)
        }
        assertFalse(f.nodes().any{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text in listOf("Previous","Next","Hide")}==true})
    }}
    private fun Fixture.sheet()=nodes().first {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Reader settings sheet")==true}
    @Test fun sheetOpensHalfExpandsCollapsesAndOutsideDismissesWithoutASeek()=runTest {Fixture(this).use {f->
        f.start();val before=f.reader.state.value;f.center();f.click("Reading mode");f.draw(24)
        assertEquals("Half expanded",f.sheet().config.getOrNull(SemanticsProperties.StateDescription));val half=f.sheet().positionInRoot.y
        val expand=assertNotNull(f.nodes().firstOrNull{it.config.getOrNull(SemanticsActions.Expand)!=null})
        assertTrue(assertNotNull(expand.config.getOrNull(SemanticsActions.Expand)?.action).invoke());f.draw(24)
        assertEquals("Expanded",f.sheet().config.getOrNull(SemanticsProperties.StateDescription));assertTrue(f.sheet().positionInRoot.y<half)
        val collapse=assertNotNull(f.nodes().firstOrNull{it.config.getOrNull(SemanticsActions.Collapse)!=null})
        assertTrue(assertNotNull(collapse.config.getOrNull(SemanticsActions.Collapse)?.action).invoke());f.draw(24)
        assertEquals("Half expanded",f.sheet().config.getOrNull(SemanticsProperties.StateDescription))
        f.tap(Offset(8f,90f));f.draw(24);assertFalse(f.nodes().any{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Reader settings sheet")==true})
        assertEquals(before.ticket,f.reader.state.value.ticket);assertEquals(before.position,f.reader.state.value.position);assertEquals(before.frames,f.reader.state.value.frames)
    }}
    @Test fun sheetDragCanExpandAndDismissAndBackEscapeRespectModalPriority()=runTest {Fixture(this).use {f->
        f.start();f.center();f.click("Settings");f.draw(24)
        val start=Offset(195f,f.sheet().positionInRoot.y+10)
        f.scene.sendPointerEvent(PointerEventType.Press,start,type=PointerType.Touch,timeMillis=testScheduler.currentTime)
        advanceTimeBy(250);f.scene.sendPointerEvent(PointerEventType.Move,start-Offset(0f,300f),type=PointerType.Touch,timeMillis=testScheduler.currentTime);f.draw()
        advanceTimeBy(250);f.scene.sendPointerEvent(PointerEventType.Release,start-Offset(0f,300f),type=PointerType.Touch,timeMillis=testScheduler.currentTime);f.draw(24)
        assertEquals("Expanded",f.sheet().config.getOrNull(SemanticsProperties.StateDescription))
        val down=Offset(195f,f.sheet().positionInRoot.y+10)
        f.scene.sendPointerEvent(PointerEventType.Press,down,type=PointerType.Touch,timeMillis=testScheduler.currentTime)
        advanceTimeBy(250);f.scene.sendPointerEvent(PointerEventType.Move,down+Offset(0f,720f),type=PointerType.Touch,timeMillis=testScheduler.currentTime);f.draw()
        advanceTimeBy(250);f.scene.sendPointerEvent(PointerEventType.Release,down+Offset(0f,720f),type=PointerType.Touch,timeMillis=testScheduler.currentTime);f.draw(24)
        assertFalse(f.nodes().any{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Reader settings sheet")==true})
        f.click("Settings");f.draw(24);f.key(Key.Escape);f.draw(24);assertNotNull(f.text("Settings"));assertEquals(0,f.exits)
        f.click("Reading mode");f.draw(24);assertNotNull(f.text("Left-to-right"));assertNotNull(f.back).invoke();f.draw(24)
        assertTrue(f.reader.state.value.controlsVisible);assertNotNull(f.back).invoke();f.draw();assertFalse(f.reader.state.value.controlsVisible)
    }}
    @Test fun smallWindowLargeFontsKeepSheetActionsScrollableAndCloseReachable()=runTest {Fixture(this,320,220,2f).use {f->
        f.start();f.center();f.click("Settings");f.click("Reading mode");f.draw(24)
        val close=assertNotNull(f.text("Close settings"));assertTrue(close.boundsInRoot.top<220&&close.boundsInRoot.bottom>0)
        assertTrue(f.nodes().any{it.config.getOrNull(SemanticsActions.ScrollBy)!=null})
        f.click("Close settings");f.draw(24);assertEquals(0,f.reader.state.value.position.index)
    }}
    @Test fun sliderPointerShowsIntentAndPreviewWithoutCommittingUntilRelease()=runTest {Fixture(this).use {f->
        f.start();f.center();val slider=assertNotNull(f.nodes().firstOrNull{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Go to page")==true})
        val b=slider.boundsInRoot;val at=Offset(b.left+12,b.center.y);val end=Offset(b.left+b.width*.70f,b.center.y)
        f.scene.sendPointerEvent(PointerEventType.Press,at,type=PointerType.Touch,timeMillis=testScheduler.currentTime)
        advanceTimeBy(20);f.scene.sendPointerEvent(PointerEventType.Move,end,type=PointerType.Touch,timeMillis=testScheduler.currentTime);f.draw(1)
        val scrub=assertNotNull(f.reader.state.value.scrub);assertEquals(0,f.reader.state.value.position.index)
        assertNotNull(f.text("Preview ${scrub.requested+1} / 7"));assertTrue(f.reader.retainedPages<=3)
        repeat(40){f.draw();yield()};assertEquals(scrub.requested,f.reader.state.value.scrub?.displayed)
        assertNotNull(f.art(scrub.requested));assertEquals(0,f.reader.state.value.position.index)
        f.scene.sendPointerEvent(PointerEventType.Release,end,type=PointerType.Touch,timeMillis=testScheduler.currentTime);f.await(scrub.requested)
        assertNull(f.reader.state.value.scrub)
    }}
    @Test fun sparseOneTimesSwipeConsumesFinalPointerUpMovement()=runTest {Fixture(this).use {f->
        f.start();val at=Offset(351f,390f);val t=testScheduler.currentTime
        f.scene.sendPointerEvent(PointerEventType.Press,at,type=PointerType.Touch,timeMillis=t)
        f.scene.sendPointerEvent(PointerEventType.Release,at-Offset(120f,0f),type=PointerType.Touch,timeMillis=t+16);f.await(1)
        assertFalse(f.reader.state.value.controlsVisible)
    }}
    @Test fun doubleTapOnRtlPairUsesOneCanvasAndNoNewRasters()=runTest {Fixture(this,960,540).use {f->
        f.start();f.reader.mode(PageReadingMode.PAGED_RTL);f.reader.layout(PageLayout.DOUBLE);f.reader.seek(1);f.await(1)
        val before=f.reader.state.value.frames;val at=Offset(480f,270f);val t=testScheduler.currentTime
        for((type,time)in listOf(PointerEventType.Press to t,PointerEventType.Release to t+10,PointerEventType.Press to t+110,PointerEventType.Release to t+120)) {
            f.scene.sendPointerEvent(type,at,type=PointerType.Touch,timeMillis=time);f.draw(1)
        }
        f.draw(24);assertEquals(1,f.reader.state.value.position.index);assertEquals(before,f.reader.state.value.frames)
        val a=assertNotNull(f.art(1)).positionInRoot;val b=assertNotNull(f.art(2)).positionInRoot
        assertTrue(a.x>b.x);assertFalse(f.reader.state.value.controlsVisible);assertTrue(f.reader.retainedPages<=4)
    }}
    @Test fun controlsAndSheetNeverForwardTheirTouchesIntoArtworkNavigation()=runTest {Fixture(this).use {f->
        f.start();f.center();val counter=assertNotNull(f.text("1 / 7"))
        f.tap(counter.boundsInRoot.center);assertEquals(0,f.reader.state.value.position.index);assertNull(f.reader.state.value.transition)
        f.click("Settings");f.draw(24);f.tap(Offset(195f,760f));assertEquals(0,f.reader.state.value.position.index);assertNull(f.reader.state.value.transition)
    }}
    @Test fun deliberateLateralAndCornerDoubleTapsZoomWithoutTurningAndResetWithOrdinaryDouble()=runTest {
        for(at in listOf(Offset(10f,105f),Offset(380f,105f),Offset(10f,675f),Offset(380f,675f),Offset(20f,390f),Offset(370f,390f)))Fixture(this).use {f->
            f.start();val origin=assertNotNull(f.art(0)).positionInRoot;val ticket=f.reader.state.value.ticket
            fun double(firstHold:Long) {
                val t=testScheduler.currentTime
                f.scene.sendPointerEvent(PointerEventType.Press,at,type=PointerType.Touch,timeMillis=t)
                f.scene.sendPointerEvent(PointerEventType.Release,at,type=PointerType.Touch,timeMillis=t+firstHold)
                f.scene.sendPointerEvent(PointerEventType.Press,at,type=PointerType.Touch,timeMillis=t+firstHold+100)
                f.scene.sendPointerEvent(PointerEventType.Release,at,type=PointerType.Touch,timeMillis=t+firstHold+110);f.draw(24)
            }
            double(130);assertEquals(0,f.reader.state.value.position.index);assertNull(f.reader.state.value.transition)
            assertEquals(ticket,f.reader.state.value.ticket);assertFalse(f.reader.state.value.controlsVisible)
            assertNotEquals(origin,assertNotNull(f.art(0)).positionInRoot)
            double(10);assertEquals(origin,assertNotNull(f.art(0)).positionInRoot);assertFalse(f.reader.state.value.controlsVisible)
        }
    }
    @Test fun ordinaryLateralDoubleIsImmediateNavigationRatherThanZoom()=runTest {
        for(mode in listOf(PageReadingMode.PAGED_LTR,PageReadingMode.PAGED_RTL))Fixture(this).use {f->
            f.start();f.reader.mode(mode);f.await(0);val at=Offset(if(mode==PageReadingMode.PAGED_LTR)370f else 20f,390f)
            val t=testScheduler.currentTime
            f.scene.sendPointerEvent(PointerEventType.Press,at,type=PointerType.Touch,timeMillis=t)
            f.scene.sendPointerEvent(PointerEventType.Release,at,type=PointerType.Touch,timeMillis=t+10)
            assertEquals(1,assertNotNull(f.reader.state.value.transition).target) // no confirmation delay
            f.scene.sendPointerEvent(PointerEventType.Press,at,type=PointerType.Touch,timeMillis=t+110)
            f.scene.sendPointerEvent(PointerEventType.Release,at,type=PointerType.Touch,timeMillis=t+120)
            assertEquals(1,f.reader.state.value.tapIntent);f.await(2)
        }
    }
    @Test fun heldZoomCandidateIsRetiredByPinchAndPan()=runTest {
        for(pinch in listOf(false,true))Fixture(this).use {f->
            f.start();val a=Offset(20f,390f);val b=Offset(240f,390f);val t=testScheduler.currentTime
            f.scene.sendPointerEvent(PointerEventType.Press,a,type=PointerType.Touch,timeMillis=t)
            f.scene.sendPointerEvent(PointerEventType.Release,a,type=PointerType.Touch,timeMillis=t+130)
            assertNull(f.reader.state.value.transition)
            f.scene.sendPointerEvent(PointerEventType.Press,a,type=PointerType.Touch,timeMillis=t+230)
            if(pinch) {
                f.scene.sendPointerEvent(PointerEventType.Press,listOf(ComposeScenePointer(PointerId(1),a,true,PointerType.Touch),ComposeScenePointer(PointerId(2),b,true,PointerType.Touch)),timeMillis=t+240)
                f.scene.sendPointerEvent(PointerEventType.Release,listOf(ComposeScenePointer(PointerId(1),a,false,PointerType.Touch),ComposeScenePointer(PointerId(2),b,false,PointerType.Touch)),timeMillis=t+260)
            } else {
                f.scene.sendPointerEvent(PointerEventType.Move,a+Offset(0f,60f),type=PointerType.Touch,timeMillis=t+240)
                f.scene.sendPointerEvent(PointerEventType.Release,a+Offset(0f,60f),type=PointerType.Touch,timeMillis=t+260)
            }
            f.draw(24);assertEquals(0,f.reader.state.value.position.index);assertNull(f.reader.state.value.transition)
            assertFalse(f.reader.state.value.controlsVisible)
        }
    }
    @Test fun heldLateralDoubleOnRtlSpreadKeepsIdentityAndControlDoublesDoNotZoom()=runTest {Fixture(this,960,540).use {f->
        f.start();f.reader.mode(PageReadingMode.PAGED_RTL);f.reader.layout(PageLayout.DOUBLE);f.reader.seek(1);f.await(1)
        val frames=f.reader.state.value.frames;val origin=assertNotNull(f.art(1)).positionInRoot;val at=Offset(750f,120f);val t=testScheduler.currentTime
        for((type,time)in listOf(PointerEventType.Press to t,PointerEventType.Release to t+130,PointerEventType.Press to t+230,PointerEventType.Release to t+240))
            f.scene.sendPointerEvent(type,at,type=PointerType.Touch,timeMillis=time)
        f.draw(24);assertNotEquals(origin,assertNotNull(f.art(1)).positionInRoot);assertEquals(1,f.reader.state.value.position.index)
        assertEquals(frames,f.reader.state.value.frames);assertTrue(f.reader.retainedPages<=4)
        f.center();f.click("Settings");f.draw(24);val zoomed=assertNotNull(f.art(1)).positionInRoot
        f.click("Reading mode");f.click("Reading mode");f.draw(24)
        assertEquals(zoomed,assertNotNull(f.art(1)).positionInRoot);assertEquals(1,f.reader.state.value.position.index)
    }}
    @Test fun heldTapOutsideArtworkCannotArmZoom()=runTest {Fixture(this).use {f->
        f.start();val origin=assertNotNull(f.art(0)).positionInRoot;val at=Offset(370f,30f);val t=testScheduler.currentTime
        for((type,time)in listOf(PointerEventType.Press to t,PointerEventType.Release to t+130,PointerEventType.Press to t+230,PointerEventType.Release to t+240))
            f.scene.sendPointerEvent(type,at,type=PointerType.Touch,timeMillis=time)
        f.await(2);assertFalse(f.reader.state.value.controlsVisible)
        assertEquals(origin,assertNotNull(f.art(2)).positionInRoot)
    }}
    @Test fun orientationExplanationAndQueuedBannersAreAbsentButPendingIntentRemains()=runTest {Fixture(this).use {f->
        f.start();f.reader.tap(.9f);f.reader.tap(.9f);f.draw(1)
        assertEquals(1,f.reader.state.value.tapIntent)
        assertNull(f.text("Next queued"));assertNull(f.text("Previous queued"));f.await(2);f.center()
        assertNull(f.text("Orientation"));f.click("Settings");f.draw(24)
        assertNull(f.text("Double tap the center to zoom. Side taps navigate immediately."))
    }}
    @Test fun centralDoubleTapZoomsTowardArtworkResetsAndNeverTogglesChromeOrTurns()=runTest {Fixture(this).use {f->
        f.start();val origin=assertNotNull(f.art(0)).positionInRoot;val at=Offset(156f,312f)
        fun touch(type:PointerEventType,time:Long){f.scene.sendPointerEvent(type,at,type=PointerType.Touch,timeMillis=time);f.draw(1)}
        val base=testScheduler.currentTime;touch(PointerEventType.Press,base);touch(PointerEventType.Release,base+10)
        touch(PointerEventType.Press,base+110);touch(PointerEventType.Release,base+120);f.draw(24)
        assertEquals(0,f.reader.state.value.position.index);assertFalse(f.reader.state.value.controlsVisible);assertNull(f.reader.state.value.transition)
        assertNotEquals(origin,assertNotNull(f.art(0)).positionInRoot)
        f.tap(Offset(351f,390f));assertEquals(0,f.reader.state.value.position.index)
        val next=testScheduler.currentTime;touch(PointerEventType.Press,next);touch(PointerEventType.Release,next+10);touch(PointerEventType.Press,next+110);touch(PointerEventType.Release,next+120);f.draw(24)
        assertEquals(origin,assertNotNull(f.art(0)).positionInRoot);assertFalse(f.reader.state.value.controlsVisible)
        f.tap(Offset(351f,390f));f.await(1)
    }}
    @Test fun centralSingleTapWaitsOnlyForDoubleTapAndLongPressNeverNavigates()=runTest {Fixture(this).use {f->
        f.start();val at=Offset(195f,390f);val t=testScheduler.currentTime
        f.scene.sendPointerEvent(PointerEventType.Press,at,type=PointerType.Touch,timeMillis=t);f.scene.sendPointerEvent(PointerEventType.Release,at,type=PointerType.Touch,timeMillis=t+10);f.draw(2)
        assertFalse(f.reader.state.value.controlsVisible);f.draw(24);assertTrue(f.reader.state.value.controlsVisible)
        f.reader.hideControls();val later=testScheduler.currentTime;val side=Offset(351f,390f)
        f.scene.sendPointerEvent(PointerEventType.Press,side,type=PointerType.Touch,timeMillis=later);f.scene.sendPointerEvent(PointerEventType.Release,side,type=PointerType.Touch,timeMillis=later+600);f.draw(24)
        assertEquals(0,f.reader.state.value.position.index);assertFalse(f.reader.state.value.controlsVisible)
    }}
    @Test fun pinchAfterOneCentralTapRetiresThePendingSingleTap()=runTest {Fixture(this).use {f->
        f.start();val a=Offset(160f,390f);val b=Offset(240f,390f);val t=testScheduler.currentTime
        f.scene.sendPointerEvent(PointerEventType.Press,a,type=PointerType.Touch,timeMillis=t);f.scene.sendPointerEvent(PointerEventType.Release,a,type=PointerType.Touch,timeMillis=t+10);f.draw(1)
        fun send(type:PointerEventType,first:Boolean,second:Boolean,time:Long){f.scene.sendPointerEvent(type,listOf(ComposeScenePointer(PointerId(1),a,first,PointerType.Touch),ComposeScenePointer(PointerId(2),b,second,PointerType.Touch)),timeMillis=time);f.draw(1)}
        send(PointerEventType.Press,true,false,t+100);send(PointerEventType.Press,true,true,t+110);send(PointerEventType.Release,false,false,t+150);f.draw(24)
        assertFalse(f.reader.state.value.controlsVisible);assertNull(f.reader.state.value.transition);assertEquals(0,f.reader.state.value.position.index)
    }}
    @Test fun sevenFastSideTapsKeepTheFirstIncomingImageAndAtMostOneFollowingTurn()=runTest {
        for(mode in listOf(PageReadingMode.PAGED_LTR,PageReadingMode.PAGED_RTL))for(layout in PageLayout.entries)Fixture(this).use {f->
            f.start();f.reader.mode(mode);f.reader.layout(layout);f.await(0)
            val at=Offset(if(mode==PageReadingMode.PAGED_LTR)351f else 39f,390f);val t=testScheduler.currentTime
            var first:Long?=null
            repeat(7){i->f.scene.sendPointerEvent(PointerEventType.Press,at,type=PointerType.Touch,timeMillis=t+i*20)
                f.scene.sendPointerEvent(PointerEventType.Release,at,type=PointerType.Touch,timeMillis=t+i*20+10);f.draw(1)
                val incoming=assertNotNull(f.reader.state.value.transition);if(first==null)first=incoming.ticket
                assertEquals(first,incoming.ticket);assertEquals(1,incoming.target);assertTrue(f.reader.retainedPages<=4)
            }
            f.await(if(layout==PageLayout.DOUBLE)3 else 2);assertNull(f.reader.state.value.tapIntent)
        }
    }
    @Test fun lightDarkPortraitLandscapeAndDesktopRefinementPreviews()=runTest {
        for((w,h,name)in listOf(Triple(390,780,"portrait"),Triple(960,540,"landscape"),Triple(1280,800,"desktop")))for(dark in listOf(false,true))Fixture(this,w,h).use {f->
            f.systemDark=dark;f.start();f.reader.layout(PageLayout.DOUBLE);f.reader.seek(1);f.await(1);f.center()
            val prefix="refined-$name-${if(dark)"dark"else"light"}";f.preview("$prefix-controls.png")
            f.click("Reading mode");f.draw(24);f.preview("$prefix-sheet.png");f.key(Key.Escape);f.draw(24)
            f.reader.hideControls();f.draw();f.preview("$prefix-artwork.png")
        }
    }
    @Test fun lightApplicationControlsKeepDarkArtworkButUseLightSurfaces()=runTest {Fixture(this).use {f->
        f.start();f.center();f.click("Settings");assertTrue(assertNotNull(f.controlsColors).isLight)
    }}
    @Test fun immersiveSurfaceHasNoChromeUntilCenterAndSystemAppearanceExpires()=runTest {Fixture(this).use {f->
        f.start();assertNull(f.text("Settings"));assertNull(f.text("1 / 7"));assertEquals(ReaderAppearance(true,COMIC_BACKGROUND),f.appearance)
        val ticket=f.reader.state.value.ticket;val b=assertNotNull(f.art(0)).boundsInRoot
        f.center();assertNotNull(f.text("Settings"));assertEquals(b,assertNotNull(f.art(0)).boundsInRoot);assertEquals(ticket,f.reader.state.value.ticket)
        assertNotNull(f.back).invoke();f.draw();assertNull(f.text("Settings"));assertEquals(0,f.exits)
    }}
    @Test fun fittingControlsKeepSemanticPageAndUseAspectPreservingGeometry()=runTest {Fixture(this,960,540).use {f->
        f.start();f.reader.navigate(2);f.await(2);f.center()
        for(fit in PageFit.entries){f.click("Settings");f.click("Page fitting");f.click(fitLabel(fit));f.draw(12);f.await(2);assertEquals(fit,f.reader.state.value.settings.fit);assertEquals(2,f.reader.state.value.position.index)}
    }}
    @Test fun sliderIsAccessibleAndUsesValidatedTurnWithoutEarlyPositionChange()=runTest {Fixture(this).use {f->
        f.start();f.center();val slider=assertNotNull(f.nodes().firstOrNull{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Go to page")==true})
        assertTrue(assertNotNull(slider.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(5f));assertEquals(0,f.reader.state.value.position.index);f.await(4);assertNotNull(f.text("5 / 7"))
    }}
    @Test fun compactLandscapeKeepsCenterFreeAndSliderReachable()=runTest {Fixture(this,640,240).use {f->
        f.start();f.center();assertNotNull(f.text("1 / 7"))
        val slider=assertNotNull(f.nodes().firstOrNull {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Go to page")==true})
        assertTrue(assertNotNull(slider.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(5f))
        assertEquals(0,f.reader.state.value.position.index);f.await(4)
    }}
    @Test fun keyboardPhysicalDirectionsRespectMangaAndEscapeDismissesBeforeExit()=runTest {Fixture(this).use {f->
        f.start();f.key(Key.DirectionRight);f.await(1);f.reader.mode(PageReadingMode.PAGED_RTL);f.await(1)
        f.key(Key.DirectionLeft);f.await(2);f.key(Key.DirectionRight);f.await(1)
        f.key(Key.F10);assertNotNull(f.text("Settings"));f.key(Key.Escape);assertNull(f.text("Settings"));assertEquals(0,f.exits)
        f.key(Key.Escape);assertEquals(1,f.exits)
    }}
    @Test fun sideNavigationHidesChromeWithoutTouchingGrouping()=runTest {Fixture(this).use {f->
        f.start();f.reader.layout(PageLayout.DOUBLE);f.await(0);f.center();f.tap(Offset(f.width*.9f,f.height*.5f));f.await(1)
        assertFalse(f.reader.state.value.controlsVisible);assertNotNull(f.art(1));assertNotNull(f.art(2))
        f.reader.next();f.await(3);assertNull(f.art(4));assertTrue(f.reader.retainedPages<=4)
    }}
    @Test fun fitWidthAtOneTimesAllowsVerticalPanWithoutTurning()=runTest {Fixture(this,780,390).use {f->
        f.start();f.reader.fit(PageFit.WIDTH);f.await(0);f.center();assertNotNull(f.text("Settings"))
        val before=assertNotNull(f.art(0)).positionInRoot
        val at=Offset(390f,195f)
        f.scene.sendPointerEvent(PointerEventType.Press,at)
        f.scene.sendPointerEvent(PointerEventType.Move,at+Offset(0f,-100f));f.draw()
        f.scene.sendPointerEvent(PointerEventType.Release,at+Offset(0f,-100f));f.draw()
        assertNull(f.reader.state.value.transition);assertEquals(0,f.reader.state.value.position.index)
        assertTrue(assertNotNull(f.art(0)).positionInRoot.y<before.y)
        assertFalse(f.reader.state.value.controlsVisible);assertNull(f.text("Settings"))
    }}
    @Test fun mouseWheelTurnsOnlyAtFitAndPansWhileZoomed()=runTest {Fixture(this).use {f->
        f.start();f.scene.sendPointerEvent(PointerEventType.Scroll,Offset(190f,390f),scrollDelta=Offset(0f,1f));f.await(1)
        f.key(Key.Equals);f.scene.sendPointerEvent(PointerEventType.Scroll,Offset(190f,390f),scrollDelta=Offset(0f,1f));f.draw()
        assertNull(f.reader.state.value.transition);assertEquals(1,f.reader.state.value.position.index)
    }}
    @Test fun portraitLandscapeDesktopPreviewsKeepCompactPairsAndViewportGeometry()=runTest {
        for((w,h,name)in listOf(Triple(390,780,"comic-android-portrait.png"),Triple(960,540,"comic-android-landscape.png"),Triple(1280,800,"comic-desktop.png")))Fixture(this,w,h).use {f->
            f.start();f.reader.layout(PageLayout.DOUBLE);f.reader.seek(1);f.await(1)
            val a=assertNotNull(f.art(1)).boundsInRoot;val b=assertNotNull(f.art(2)).boundsInRoot
            assertEquals(2f,b.left-a.right,.05f);assertEquals(w/2f,(a.left+b.right)/2,.05f)
            f.preview(name);f.center();f.preview(name.replace(".png","-controls.png"));assertNotNull(f.text("2–3 / 7"))
        }
    }
    @Test fun resizeAndLayoutRoundTripRetainExactPageAndRetireZoomGeometry()=runTest {Fixture(this).use {f->
        f.start();f.reader.navigate(2);f.await(2);f.key(Key.Equals);f.reader.layout(PageLayout.DOUBLE);f.await(1)
        f.resize(780,390);f.await(1);f.reader.layout(PageLayout.SINGLE);f.await(2);assertEquals(2,f.reader.state.value.position.index)
    }}
}
