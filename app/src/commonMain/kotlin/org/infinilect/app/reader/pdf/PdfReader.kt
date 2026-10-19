// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import org.infinilect.app.ui.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.infinilect.app.media.Raster
import org.infinilect.app.media.rasterImageBitmap

/** Fit-page presentation. Recomposition/resizing never changes semantic page position. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PdfReader(reader: PdfReaderController, saveFailed: Boolean, onBack: () -> Unit, backLabel: String, onPosition: (Int) -> Unit = {}) {
    val strings = LocalAppStrings.current
    val state by reader.state.collectAsState()
    SideEffect { state.presentedIndex?.let(onPosition) }
    val ticket = state.ticket // Capture this generation before a suspending UI conversion.
    var presentationFailed by remember(reader,ticket) { mutableStateOf(false) }
    val frame = state.frame as? PdfFrame.Ready
    val presented by produceState<Pair<Long,ImageBitmap>?>(null,reader,ticket,frame) {
        value = null
        if (frame != null) {
            val bitmap = try { rasterImageBitmap(Raster(frame.raster.size.width,frame.raster.size.height,frame.raster.argb)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { presentationFailed=true;return@produceState }
            currentCoroutineContext().ensureActive()
            value = ticket to bitmap
        }
    }
    Column(Modifier.fillMaxSize()) {
        // Localized labels and accessibility fonts can exceed one row on a phone.
        FlowRow(Modifier.fillMaxWidth().padding(8.dp),verticalArrangement=Arrangement.Center) {
            TextButton(onClick=onBack,modifier=Modifier.align(Alignment.CenterVertically)) { Text(backLabel) }
            Text("${state.index+1} / ${reader.document.pageCount}",Modifier.padding(horizontal=8.dp).align(Alignment.CenterVertically))
            TextButton(enabled=state.index>0,onClick=reader::previous,modifier=Modifier.align(Alignment.CenterVertically)) { Text(strings[UiText.PREVIOUS]) }
            TextButton(enabled=state.index+1<reader.document.pageCount,onClick=reader::next,modifier=Modifier.align(Alignment.CenterVertically)) { Text(strings[UiText.NEXT]) }
        }
        if (saveFailed) Text(strings[UiText.UI_READING_PROGRESS_COULD_NOT_BE_SAVED],Modifier.padding(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.White),contentAlignment=Alignment.Center) {
            val bitmap=presented?.takeIf { it.first == state.ticket && frame != null }?.second
            if (bitmap != null) Image(bitmap,strings.format(UiText.UI_PDF_PAGE_1_S, state.index+1),Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            else if (presentationFailed) Text(strings[UiText.UI_THIS_PDF_PAGE_COULD_NOT_BE_DISPLAYED],color=Color.Black)
            else when(val current=state.frame) {
                is PdfFrame.Failed -> Text(strings.status(current.failure.userMessage),Modifier.padding(16.dp),color=Color.Black)
                else -> CircularProgressIndicator()
            }
        }
    }
}
