// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material.ExperimentalMaterialApi::class)
package org.infinilect.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider

internal enum class LibraryGlyph { FILTER, SORT, TAG, HEART, BOOKMARK, MORE, CLOSE, CHECK, EDIT, DELETE, BOOK, UP, DOWN, DOWNLOAD, ERROR }

/** Original lightweight vectors. No image request, persistent color or extra icon dependency. */
@Composable
internal fun LibraryIcon(glyph: LibraryGlyph, active: Boolean = false, modifier: Modifier = Modifier, colorOverride: Color? = null, fill: Float = if(active) 1f else 0f) {
    val color = colorOverride ?: if (active) MaterialTheme.colors.primary else LocalContentColor.current.copy(alpha = LocalContentAlpha.current)
    Canvas(modifier.size(22.dp).clearAndSetSemantics {}) {
        val s = size.minDimension / 24f
        fun p(x: Float,y: Float) = Offset(x*s,y*s)
        fun line(x: Float,y: Float,ex: Float,ey: Float) = drawLine(color,p(x,y),p(ex,ey),1.8f*s,StrokeCap.Round)
        when(glyph) {
            LibraryGlyph.FILTER -> { line(3f,5f,21f,5f);line(6f,12f,18f,12f);line(10f,19f,14f,19f) }
            LibraryGlyph.DOWNLOAD -> {line(12f,3f,12f,16f);line(7f,11f,12f,16f);line(17f,11f,12f,16f);line(4f,17f,4f,21f);line(4f,21f,20f,21f);line(20f,17f,20f,21f)}
            LibraryGlyph.ERROR -> {drawCircle(color,10*s,p(12f,12f),style=Stroke(1.8f*s));line(12f,6f,12f,13f);drawCircle(color,1.1f*s,p(12f,17f))}
            LibraryGlyph.SORT -> { line(4f,5f,16f,5f);line(4f,12f,13f,12f);line(4f,19f,10f,19f);line(20f,5f,20f,20f);line(17f,17f,20f,20f);line(23f,17f,20f,20f) }
            LibraryGlyph.MORE -> for(y in listOf(5f,12f,19f)) drawCircle(color,1.7f*s,p(12f,y))
            LibraryGlyph.CLOSE -> { line(6f,6f,18f,18f);line(6f,18f,18f,6f) }
            LibraryGlyph.CHECK -> { line(4f,12f,10f,18f);line(10f,18f,21f,5f) }
            LibraryGlyph.HEART -> {
                val path=Path().apply { moveTo(12*s,21*s);cubicTo(9*s,18*s,2*s,13*s,2*s,7*s);cubicTo(2*s,1*s,10*s,1*s,12*s,7*s);cubicTo(14*s,1*s,22*s,1*s,22*s,7*s);cubicTo(22*s,13*s,15*s,18*s,12*s,21*s);close() }
                drawPath(path,color.copy(alpha=color.alpha*fill.coerceIn(0f,1f)));drawPath(path,color,style=Stroke(1.8f*s))
            }
            LibraryGlyph.BOOKMARK -> {
                val path=Path().apply {moveTo(5*s,3*s);lineTo(19*s,3*s);lineTo(19*s,21*s);lineTo(12*s,17*s);lineTo(5*s,21*s);close()}
                drawPath(path,color.copy(alpha=color.alpha*fill.coerceIn(0f,1f)));drawPath(path,color,style=Stroke(1.8f*s))
            }
            LibraryGlyph.TAG -> { val path=Path().apply {moveTo(3*s,3*s);lineTo(13*s,3*s);lineTo(22*s,12*s);lineTo(12*s,22*s);lineTo(3*s,13*s);close()};drawPath(path,color,style=Stroke(1.8f*s));drawCircle(color,1.5f*s,p(8f,8f)) }
            LibraryGlyph.EDIT -> { line(5f,19f,19f,5f);line(16f,3f,21f,8f);line(5f,19f,4f,22f);line(4f,22f,8f,21f) }
            LibraryGlyph.DELETE -> { line(4f,6f,20f,6f);line(9f,3f,15f,3f);line(6f,6f,7f,21f);line(7f,21f,17f,21f);line(17f,21f,18f,6f);line(10f,10f,10f,17f);line(14f,10f,14f,17f) }
            LibraryGlyph.BOOK -> { line(12f,5f,12f,21f);for(x in listOf(3f,13f)) drawRect(color,p(x,4f),Size(8*s,16*s),style=Stroke(1.8f*s)) }
            LibraryGlyph.UP,LibraryGlyph.DOWN -> { val down=glyph==LibraryGlyph.DOWN;val y=if(down) 19f else 5f;val from=if(down) 14f else 10f;line(12f,5f,12f,19f);line(7f,from,12f,y);line(17f,from,12f,y) }
        }
    }
}

@Composable
internal fun LibraryIconButton(label: String, glyph: LibraryGlyph, enabled: Boolean = true, active: Boolean = false,
    modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null, click: () -> Unit) {
    val position=remember { object: PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize) =
            IntOffset((anchorBounds.center.x-popupContentSize.width/2).coerceIn(0,(windowSize.width-popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.bottom+4).coerceIn(0,(windowSize.height-popupContentSize.height).coerceAtLeast(0)))
    } }
    BasicTooltipBox(positionProvider=position,state=rememberBasicTooltipState(),focusable=false,
        tooltip={Surface(shape=MaterialTheme.shapes.small){Text(label,Modifier.padding(8.dp))}}) {
        IconButton(click,modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics {contentDescription=label},enabled=enabled) { if(icon!=null) icon() else LibraryIcon(glyph,active) }
    }
}

/** 48dp interaction area, wrapping text, and a single selection semantic. */
@Composable
internal fun LibraryChip(label: String, selected: Boolean, enabled: Boolean = true, choose: () -> Unit) {
    Box(Modifier.heightIn(min=48.dp).padding(vertical=4.dp)) {
        Surface(onClick=choose,enabled=enabled,shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color=if(selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha=.06f),
            contentColor=if(selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
            modifier=Modifier.heightIn(min=40.dp).semantics {this.selected=selected}) {
            Box(Modifier.padding(horizontal=12.dp,vertical=10.dp)) {Text(label,style=MaterialTheme.typography.caption,color=(if(selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface).copy(alpha=if(enabled) 1f else .6f))}
        }
    }
}

/** Primary reading action stays separate from compact metadata actions. */
@Composable
internal fun PublicationReadAction(progress: org.infinilect.core.ReadingProgress?, enabled: Boolean = true, read: () -> Unit) {
    val strings=LocalAppStrings.current
    Button(read,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=enabled && LocalCatalogActions.current.mayRead,contentPadding=PaddingValues(12.dp)) {
        LibraryIcon(LibraryGlyph.BOOK);Spacer(Modifier.width(10.dp));Text(strings.status(readingAction(progress)))
    }
}

@Composable
internal fun LibrarySelectionBar(count: Int,total: Int,enabled: Boolean,allFavorite: Boolean,
    clear: () -> Unit,selectAll: () -> Unit,organize: () -> Unit,favorite: () -> Unit,remove: () -> Unit,menu: MutableState<Boolean>) {
    val strings=LocalAppStrings.current
    var more by menu
    val menuFocus=remember {FocusRequester()}
    val triggerFocus=remember {FocusRequester()}
    var restoreFocus by remember {mutableStateOf(false)}
    LaunchedEffect(more) {
        if(more) {withFrameNanos {};menuFocus.requestFocus()}
        else if(restoreFocus) {restoreFocus=false;withFrameNanos {};triggerFocus.requestFocus()}
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        LibraryIconButton(strings[UiText.UI_CLEAR_SELECTION],LibraryGlyph.CLOSE,click=clear)
        Text(strings.selectedCount(count),Modifier.weight(1f).semantics {heading();liveRegion=LiveRegionMode.Polite},
            style=MaterialTheme.typography.subtitle2,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        LibraryIconButton(strings[UiText.LIB_ORGANIZE],LibraryGlyph.TAG,enabled=enabled,click=organize)
        LibraryIconButton(strings[UiText.LIB_FAVORITE],LibraryGlyph.HEART,enabled=enabled,active=allFavorite,click=favorite)
        Box {
            LibraryIconButton(strings[UiText.LIB_MORE],LibraryGlyph.MORE,modifier=Modifier.focusRequester(triggerFocus)) {more=true}
            DropdownMenu(more,{more=false},modifier=Modifier.onPreviewKeyEvent {
                if(it.type==KeyEventType.KeyDown && it.key==Key.Escape) {restoreFocus=true;more=false;true} else false
            }) {
                DropdownMenuItem({more=false;selectAll()},modifier=Modifier.focusRequester(menuFocus),enabled=enabled) {Text(if(total>1000) strings.format(UiText.LIB_SELECT_LIMIT,strings.number(1000)) else strings[UiText.SELECT_ALL])}
                DropdownMenuItem({more=false;remove()},enabled=enabled) {Text(strings[UiText.UI_REMOVE_FROM_LIBRARY])}
            }
        }
    }
}
