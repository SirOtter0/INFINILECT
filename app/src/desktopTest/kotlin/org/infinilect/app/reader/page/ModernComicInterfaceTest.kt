// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
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
    private class Fixture(val test:TestScope,val width:Int=390,val height:Int=780):AutoCloseable {
        val document=Comic()
        // Deterministic artwork adapter isolates UI clocks from platform native decode scheduling.
        // Existing CbzPagePreparer/SpreadRaster suites retain native decode and subsampling coverage.
        val native=object:RasterDecoder {override suspend fun decode(bytes:ByteArray,mediaType:String)=document.rasters[document.openIndex]}
        val reader=PageReaderController(document,test.backgroundScope,decoder=native,decodeDispatcher=StandardTestDispatcher(test.testScheduler))
        val scene=ImageComposeScene(width,height,Density(1f),coroutineContext=UnconfinedTestDispatcher(test.testScheduler))
        var appearance:ReaderAppearance?=null;var back:(()->Unit)?=null;var exits=0
        suspend fun start(){reader.initialize(null);scene.setContent {MaterialTheme(colors=org.infinilect.app.ui.applicationColors(width>=600)) {PageReader(reader,false,{exits++},"Back",backHandler={enabled,action->back=if(enabled)action else null},appearanceChanged={appearance=it})}};await(0)}
        fun draw(n:Int=6){repeat(n){test.advanceTimeBy(16);test.runCurrent();scene.render(test.testScheduler.currentTime*1_000_000).close()};test.runCurrent()}
        fun nodes():List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
            walk(owner.unmergedRootSemanticsNode)
        }
        fun text(label:String)=nodes().firstOrNull{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}
        fun art(index:Int)=nodes().firstOrNull{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Page ${index+1} of 7")==true}
        suspend fun await(anchor:Int){repeat(400){draw();if(reader.state.value.transition==null&&reader.state.value.position.index==anchor&&reader.spread().indices.all{art(it)!=null})return;test.advanceTimeBy(16);yield();Thread.yield()};fail("Spread $anchor not presented")}
        fun click(label:String){val n=assertNotNull(text(label));assertTrue(n.boundsInRoot.bottom>0&&n.boundsInRoot.top<height);tap(n.boundsInRoot.center);draw(12)}
        fun tap(at:Offset){scene.sendPointerEvent(PointerEventType.Press,at);scene.sendPointerEvent(PointerEventType.Release,at);draw()}
        fun center(){tap(Offset(width*.5f,height*.5f))}
        fun key(key:Key){scene.sendKeyEvent(KeyEvent(key=key,type=KeyEventType.KeyDown));draw()}
        fun resize(w:Int,h:Int){scene.constraints=Constraints.fixed(w,h);draw()}
        fun preview(name:String){System.getenv("INFINILECT_COMIC_PREVIEW_DIRECTORY")?.let {path->val dir=java.nio.file.Path.of(path);java.nio.file.Files.createDirectories(dir);scene.render(test.testScheduler.currentTime*1_000_000).use {image->java.nio.file.Files.write(dir.resolve(name),assertNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use{it.bytes})}}}
        override fun close(){scene.close();reader.close();test.runCurrent();assertNull(appearance);assertEquals(1,document.closes);assertEquals(0,document.handles)}
    }
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
    @Test fun compactLandscapeKeepsCenterFreeAndSliderReachableThroughSettings()=runTest {Fixture(this,640,240).use {f->
        f.start();f.center();assertNotNull(f.text("1 / 7"));f.click("Settings");f.click("Go to page")
        val slider=assertNotNull(f.nodes().firstOrNull {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choose page")==true})
        assertTrue(assertNotNull(slider.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(5f));f.draw()
        assertEquals(0,f.reader.state.value.position.index)
        val confirm=f.nodes().last {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text=="Go to page"}==true}
        assertTrue(confirm.boundsInRoot.top<240&&confirm.boundsInRoot.bottom>0);f.tap(confirm.boundsInRoot.center);f.await(4)
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
