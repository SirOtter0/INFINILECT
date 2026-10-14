// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package org.infinilect.app.reader.page

import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import org.infinilect.app.Destination
import org.infinilect.app.ui.DestinationIcon
import kotlinx.coroutines.*
import kotlin.math.roundToInt

internal enum class ComicPanel(val label: String) { READING("Reading mode"), PRESENTATION("Page fitting"), GENERAL("Settings") }
private enum class Glyph { BACK, PREVIOUS, NEXT, CLOSE, READING, FIT, ZOOM_IN, ZOOM_OUT, RESET }

@Composable
private fun ComicGlyph(glyph: Glyph, reverse: Boolean = false) {
    val color = MaterialTheme.colors.onSurface.copy(alpha=LocalContentAlpha.current)
    Canvas(Modifier.size(24.dp).clearAndSetSemantics {}) {
        val scale = size.minDimension / 24
        val stroke = 1.7f * scale
        fun at(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(color, at(a,b), at(c,d), stroke)
        when (glyph) {
            Glyph.BACK, Glyph.PREVIOUS, Glyph.NEXT -> {
                val right = (glyph == Glyph.NEXT) xor reverse
                val edge = if (right) 16f else 8f; val other = if (right) 9f else 15f
                line(other,5f,edge,12f);line(edge,12f,other,19f)
                if (glyph == Glyph.BACK) line(8f,12f,21f,12f)
            }
            Glyph.CLOSE -> { line(5f,5f,19f,19f);line(19f,5f,5f,19f) }
            Glyph.READING -> {
                drawRect(color,at(3f,4f),Size(7*scale,16*scale),style=Stroke(stroke))
                drawRect(color,at(14f,4f),Size(7*scale,16*scale),style=Stroke(stroke))
            }
            Glyph.FIT -> { for ((x,y) in listOf(3f to 3f,21f to 3f,3f to 21f,21f to 21f)) {line(x,y,if(x==3f)9f else 15f,y);line(x,y,x,if(y==3f)9f else 15f)} }
            Glyph.ZOOM_IN, Glyph.ZOOM_OUT -> {
                drawCircle(color,7*scale,at(10f,10f),style=Stroke(stroke));line(15f,15f,21f,21f);line(6f,10f,14f,10f)
                if(glyph==Glyph.ZOOM_IN)line(10f,6f,10f,14f)
            }
            Glyph.RESET -> { drawArc(color,35f,285f,false,at(4f,4f),Size(16*scale,16*scale),style=Stroke(stroke));line(20f,5f,20f,11f);line(20f,11f,14f,11f) }
        }
    }
}

/** One shared tooltip implementation for touch long-press, mouse hover and accessible buttons. */
@Composable
internal fun ComicAction(label: String, enabled: Boolean = true, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val position = remember { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
            IntOffset((anchorBounds.center.x-popupContentSize.width/2).coerceIn(0,(windowSize.width-popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.top-popupContentSize.height-8).coerceIn(0,(windowSize.height-popupContentSize.height).coerceAtLeast(0)))
    } }
    BasicTooltipBox(positionProvider=position, state=rememberBasicTooltipState(), focusable=false,
        tooltip={Surface(shape=MaterialTheme.shapes.small){Text(label,Modifier.padding(8.dp))}}) {
        IconButton(onClick, enabled=enabled, modifier=Modifier.size(48.dp).semantics { contentDescription=label }) { icon() }
    }
}

@Composable
private fun PanelAction(panel: ComicPanel, open: (ComicPanel) -> Unit) = ComicAction(panel.label,onClick={open(panel)}) {
    if(panel==ComicPanel.GENERAL) DestinationIcon(Destination.SETTINGS,MaterialTheme.colors.onSurface)
    else ComicGlyph(if(panel==ComicPanel.READING)Glyph.READING else Glyph.FIT)
}

/** Material's single anchored state owns half/expanded/hidden, drag and scrim dismissal. */
@Composable
internal fun ComicSheetHost(reader: PageReaderController, state: PageReaderState, transform: PageTransform,
    backHandler: @Composable (Boolean,()->Unit)->Unit, publicationActions: (@Composable ()->Unit)?, notices: (@Composable ()->Unit)?,
    onDismissed:()->Unit = {}, content: @Composable (open:(ComicPanel)->Unit, sheetOpen:Boolean)->Unit) {
    val sheet = rememberModalBottomSheetState(ModalBottomSheetValue.Hidden, animationSpec=tween(180), skipHalfExpanded=false)
    val scope = rememberCoroutineScope()
    var motion by remember(reader) { mutableStateOf<Job?>(null) }
    var panel by remember(reader) { mutableStateOf(ComicPanel.GENERAL) }
    val open = sheet.currentValue!=ModalBottomSheetValue.Hidden || sheet.targetValue!=ModalBottomSheetValue.Hidden
    fun dismiss() { if(sheet.targetValue!=ModalBottomSheetValue.Hidden) { motion?.cancel(); motion=scope.launch(start=CoroutineStart.UNDISPATCHED){sheet.hide()} } }
    fun show(next:ComicPanel) {panel=next;if(sheet.targetValue==ModalBottomSheetValue.Hidden){motion?.cancel();motion=scope.launch(start=CoroutineStart.UNDISPATCHED){sheet.show()}}}
    backHandler(open || state.controlsVisible) { if(open)dismiss()else reader.hideControls() }
    val firstFocus = remember(reader) { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(open) { if(open) { snapshotFlow{sheet.currentValue}.first{it!=ModalBottomSheetValue.Hidden};withFrameNanos{};firstFocus.requestFocus() }
        else {withFrameNanos{};onDismissed()} }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height=maxHeight*.9f
        ModalBottomSheetLayout(sheetState=sheet, sheetShape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),
            sheetBackgroundColor=MaterialTheme.colors.surface, sheetContentColor=MaterialTheme.colors.onSurface,
            modifier=Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if(e.type==KeyEventType.KeyDown && open && e.key in listOf(Key.Escape,Key.F10)) {
                    dismiss();if(e.key==Key.F10)reader.hideControls();true
                } else false
            }, sheetContent={
                Column(Modifier.fillMaxWidth().height(height).focusProperties{canFocus=open}.then(if(!open)Modifier.clearAndSetSemantics{} else Modifier.semantics {
                    contentDescription="Reader settings sheet"
                    stateDescription=if(sheet.currentValue==ModalBottomSheetValue.Expanded)"Expanded"else"Half expanded"

                }), horizontalAlignment=Alignment.CenterHorizontally) {
                  if(open) {
                    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment=Alignment.Center) {
                        Box(Modifier.width(32.dp).height(4.dp).background(MaterialTheme.colors.onSurface.copy(alpha=.3f),RoundedCornerShape(2.dp)))
                    }
                    Row(Modifier.widthIn(max=720.dp).fillMaxWidth().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(panel.label,Modifier.weight(1f),style=MaterialTheme.typography.h6,maxLines=1,overflow=TextOverflow.Ellipsis)
                        IconButton(onClick={dismiss()},modifier=Modifier.size(48.dp).focusRequester(firstFocus).semantics { contentDescription="Close settings" }) {
                            ComicGlyph(Glyph.CLOSE)
                        }
                    }
                    Row(Modifier.widthIn(max=720.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                        ComicPanel.entries.forEach { PanelAction(it,::show) }
                    }
                    Divider()
                    Column(Modifier.weight(1f).widthIn(max=720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp)) {
                        when(panel) {
                            ComicPanel.READING -> {
                                PageReadingMode.entries.forEach {mode->Option(modeLabel(mode),state.settings.mode==mode) {reader.mode(mode);dismiss()} }
                                if(pagedMode(state.settings.mode)) {
                                    Divider(Modifier.padding(vertical=8.dp))
                                    Text("Page layout",style=MaterialTheme.typography.subtitle2)
                                    PageLayout.entries.forEach { layout->Option(if(layout==PageLayout.SINGLE)"Single page"else"Double page",state.settings.layout==layout){reader.layout(layout);dismiss()} }
                                }
                            }
                            ComicPanel.PRESENTATION -> if(pagedMode(state.settings.mode)) {
                                PageFit.entries.forEach {fit->Option(fitLabel(fit),state.settings.fit==fit){reader.fit(fit);dismiss()} }
                                Divider(Modifier.padding(vertical=8.dp))
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                                    ComicAction("Zoom out",onClick={reader.cancelTransition();transform.resetTap();transform.zoom=(transform.zoom-.5f).coerceAtLeast(1f);transform.pan=Offset.Zero;dismiss()}){ComicGlyph(Glyph.ZOOM_OUT)}
                                    ComicAction("Zoom in",onClick={reader.cancelTransition();transform.resetTap();transform.zoom=(transform.zoom+.5f).coerceAtMost(4f);dismiss()}){ComicGlyph(Glyph.ZOOM_IN)}
                                    ComicAction("Reset zoom",onClick={reader.cancelTransition();transform.reset();dismiss()}){ComicGlyph(Glyph.RESET)}
                                }
                            } else Text("Continuous modes use their existing width-fit presentation.")
                            ComicPanel.GENERAL -> {
                                publicationActions?.invoke();notices?.invoke()
                            }
                        }
                    }
                  }
                }
            }) { content(::show,open) }
    }
}

@Composable
private fun Option(label:String,chosen:Boolean,action:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.RadioButton,onClick=action).semantics(mergeDescendants=true){selected=chosen},verticalAlignment=Alignment.CenterVertically) {
        RadioButton(chosen,onClick=null);Text(label,Modifier.weight(1f).padding(start=8.dp,top=8.dp,bottom=8.dp))
    }
}

@Composable
internal fun BoxScope.ComicChrome(reader:PageReaderController,state:PageReaderState,transform:PageTransform,onBack:()->Unit,backLabel:String,
    saveFailed:Boolean,settingsFailed:Boolean,open:(ComicPanel)->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact=maxHeight<240.dp
        val tiny=compact && LocalDensity.current.fontScale>1.5f
        val headerLimit=maxHeight/3
        val nav=state.transition?.takeIf{it.phase!=PageTransitionPhase.RETURNING}?.target?:state.position.index
        val counter=state.scrub?.let{"Preview ${it.requested+1} / ${reader.document.pages.size}"}?:reader.spread().indicator(reader.document.pages.size)
        Column(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxWidth().heightIn(max=headerLimit).pointerInput(Unit){detectTapGestures{}},color=MaterialTheme.colors.surface.copy(alpha=.97f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    ComicAction(backLabel,onClick=onBack){ComicGlyph(Glyph.BACK)}
                    Column(Modifier.weight(1f).padding(end=12.dp).semantics { if(tiny)contentDescription=reader.document.title }) {
                        if(!tiny)Text(reader.document.title,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(compact)Text(counter,style=MaterialTheme.typography.caption)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(Modifier.pointerInput(Unit){detectTapGestures{}},color=MaterialTheme.colors.surface.copy(alpha=.97f)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        ComicAction("Previous",enabled=nav>0,onClick={transform.reset();reader.stepTap(-1)}){ComicGlyph(Glyph.PREVIOUS,state.settings.mode==PageReadingMode.PAGED_RTL)}
                        if(reader.document.pages.size>1)Slider((state.scrub?.requested?:state.position.index).plus(1).toFloat(),
                            onValueChange={ value->if(state.scrub==null)transform.reset();reader.previewPage(value.roundToInt()-1) },
                            modifier=Modifier.weight(1f).height(48.dp).semantics {
                                contentDescription="Go to page";stateDescription=counter
                                setProgress {value->if(!value.isFinite())false else{transform.reset();reader.seek((value.roundToInt()-1).coerceIn(reader.document.pages.indices));true}}
                            }, valueRange=1f..reader.document.pages.size.toFloat(),onValueChangeFinished=reader::releasePreview)
                        else Spacer(Modifier.weight(1f))
                        ComicAction("Next",enabled=reader.spread(nav).indices.last()<reader.document.pages.lastIndex,onClick={transform.reset();reader.stepTap(1)}){ComicGlyph(Glyph.NEXT,state.settings.mode==PageReadingMode.PAGED_RTL)}
                        if(compact)PanelAction(ComicPanel.GENERAL,open)
                    }
                    if(!compact)Row(Modifier.fillMaxWidth().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(counter,Modifier.weight(1f),style=MaterialTheme.typography.caption,maxLines=2)
                        ComicPanel.entries.forEach{PanelAction(it,open)}
                    }
                    if(saveFailed)Text("Reading position could not be saved on this device.")
                    if(settingsFailed)Text("Page reader preferences could not be saved on this device.")
                    if(state.navigationFailed)Text("Requested page unavailable or unsupported")
                }
            }
        }
    }
}
