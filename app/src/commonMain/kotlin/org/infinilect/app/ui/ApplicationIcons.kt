// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import org.infinilect.app.Destination
import kotlin.math.*

/** Small original outline UI glyphs. These are controls, not a substitute project logo. */
@Composable
internal fun DestinationIcon(destination: Destination, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp).clearAndSetSemantics {}) {
        val scale = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x*scale,y*scale)
        fun line(a: Float,b: Float,c: Float,d: Float) = drawLine(color,point(a,b),point(c,d),1.7f*scale)
        when (destination) {
            Destination.HOME -> {
                val path=Path().apply { moveTo(3*scale,11*scale);lineTo(12*scale,3*scale);lineTo(21*scale,11*scale);lineTo(21*scale,21*scale);lineTo(15*scale,21*scale);lineTo(15*scale,14*scale);lineTo(9*scale,14*scale);lineTo(9*scale,21*scale);lineTo(3*scale,21*scale);close() }
                drawPath(path,color,style=Stroke(1.7f*scale))
            }
            Destination.LIBRARY -> {
                drawRect(color,point(3f,4f),Size(7*scale,16*scale),style=Stroke(1.7f*scale))
                drawRect(color,point(14f,4f),Size(7*scale,16*scale),style=Stroke(1.7f*scale));line(6f,7f,7f,7f);line(17f,7f,18f,7f)
            }
            Destination.HISTORY -> { drawCircle(color,9*scale,point(12f,12f),style=Stroke(1.7f*scale));line(12f,6f,12f,12f);line(12f,12f,16f,14f) }
            Destination.SEARCH -> { drawCircle(color,7*scale,point(10f,10f),style=Stroke(1.7f*scale));line(15f,15f,21f,21f) }
            Destination.SETTINGS -> {
                val path=Path()
                for(i in 0 until 32) {
                    val angle=i*PI/16;val radius=if(i%4 in 1..2) 10f else 8f
                    val x=(12+cos(angle)*radius).toFloat()*scale;val y=(12+sin(angle)*radius).toFloat()*scale
                    if(i==0)path.moveTo(x,y)else path.lineTo(x,y)
                }
                path.close();drawPath(path,color,style=Stroke(1.7f*scale));drawCircle(color,3*scale,point(12f,12f),style=Stroke(1.7f*scale))
            }
        }
    }
}

@Composable
internal fun ImportIcon() {
    val color=MaterialTheme.colors.onSurface
    Canvas(Modifier.size(24.dp).clearAndSetSemantics {}) {
        val stroke=1.7.dp.toPx()
        drawLine(color,Offset(size.width/2,2.dp.toPx()),Offset(size.width/2,size.height*.68f),stroke)
        drawLine(color,Offset(size.width*.25f,size.height*.43f),Offset(size.width/2,size.height*.68f),stroke)
        drawLine(color,Offset(size.width*.75f,size.height*.43f),Offset(size.width/2,size.height*.68f),stroke)
        drawLine(color,Offset(size.width*.15f,size.height*.88f),Offset(size.width*.85f,size.height*.88f),stroke)
    }
}
