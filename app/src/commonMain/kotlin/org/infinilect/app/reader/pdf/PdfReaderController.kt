// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.core.*

internal sealed interface PdfFrame {
    data object Loading : PdfFrame
    data class Ready(val raster: PdfRaster) : PdfFrame
    data class Failed(val failure: PdfFailure) : PdfFrame
}
internal data class PdfReaderState(val index: Int = 0, val ticket: Long = 0, val frame: PdfFrame = PdfFrame.Loading,
    val presentedIndex: Int? = null)

/** UI-thread owner of one logical page and one raster. Rendering is delegated entirely
 * through the owned contract. No scroll pixels, engine objects, filesystem or parser. */
internal class PdfReaderController(
    val document: PdfDocument,
    private val scope: CoroutineScope,
    private val persistence: ProgressPersistence? = null,
) {
    val progressId = document.progressId
    private val mutableState = MutableStateFlow(PdfReaderState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var closed = false
    private var timestamp = 0L
    init {
        PdfLimits.pageCount(document.pageCount)
        require(progressId.format == PublicationFormat.PDF)
    }
    suspend fun initialize(restored: ReadingProgress?, recreationIndex: Int? = null) {
        val saved = restored?.takeIf { it.id == progressId }
        currentCoroutineContext().ensureActive();check(!closed)
        val persistedIndex = (saved?.locator as? ReadingLocator.Page)?.pageIndex ?: 0
        val index = (recreationIndex ?: persistedIndex).coerceIn(document.pages.indices)
        timestamp = saved?.updatedAtEpochMillis ?: 0
        mutableState.value = PdfReaderState(index,1)
        // First page must render successfully before opening/History is published.
        render(1,index,recreationIndex != null && index != persistedIndex)
    }
    fun next() = navigate(state.value.index+1)
    fun previous() = navigate(state.value.index-1)
    fun navigate(index: Int) {
        if (closed || index !in document.pages.indices || index == state.value.index) return
        request?.cancel()
        retireFrame()
        val ticket = state.value.ticket+1
        mutableState.value = state.value.copy(index=index,ticket=ticket,frame=PdfFrame.Loading)
        request = scope.launch {
            try { render(ticket,index,true) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!closed && state.value.ticket == ticket)
                    mutableState.value = state.value.copy(frame=PdfFrame.Failed((error as? PdfException)?.failure ?: PdfFailure.RENDER))
            }
        }
    }
    private suspend fun render(ticket: Long, index: Int, persist: Boolean) {
        var acquired: PdfRaster? = null
        try {
            acquired = document.renderPage(index,document.pages[index].fit())
            currentCoroutineContext().ensureActive()
            if (!closed && state.value.ticket == ticket) {
                mutableState.value = state.value.copy(frame=PdfFrame.Ready(acquired),presentedIndex=index)
                acquired = null
                // No suspension between current-generation delivery and progress capture.
                if (persist) savePosition(index)
            }
        } finally { acquired?.close() }
    }
    private fun savePosition(index: Int) {
        val writer = persistence ?: return
        timestamp = maxOf(writer.clock().coerceAtLeast(0),if (timestamp == Long.MAX_VALUE) timestamp else timestamp+1)
        writer.submit(ReadingProgress(progressId,ReadingLocator.Page("pdf-page-$index",index,0.0),
            if (document.pageCount == 1) 0.0 else index.toDouble()/(document.pageCount-1),timestamp))
    }
    fun flush() { /* Successful current renders already submitted their semantic position. */ }
    private fun retireFrame() { (state.value.frame as? PdfFrame.Ready)?.raster?.close() }
    fun close() {
        if (closed) return
        closed = true; request?.cancel(); retireFrame()
        mutableState.value = state.value.copy(ticket=state.value.ticket+1,frame=PdfFrame.Loading)
        document.close()
    }
}
