// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.infinilect.core.*
import org.infinilect.app.reader.epub.*
import org.infinilect.app.reader.page.*
import org.infinilect.app.reader.pdf.*
import org.infinilect.core.PublicationFormat
import org.infinilect.core.PublicationSource
import org.infinilect.core.ResourceLoader
import org.infinilect.app.progress.ProgressPersistence
import org.infinilect.app.acquisition.selectResource

internal sealed interface OpenPublicationState {
    data object Idle : OpenPublicationState
    data class Loading(val publication: Publication) : OpenPublicationState
    data class Ready(val document: TextDocument, val reading: TextReadingProgress? = null, val publication: Publication? = null) : OpenPublicationState
    data class EpubReady(val reader: EpubReaderController, val publication: Publication) : OpenPublicationState
    data class PageReady(val reader: PageReaderController, val publication: Publication) : OpenPublicationState
    data class PdfReady(val reader: PdfReaderController, val publication: Publication) : OpenPublicationState
    data class Error(val publication: Publication, val userMessage: String) : OpenPublicationState
}

/** UI-thread actions; the supplied session scope owns work, never Compose or a network client. */
internal class OpenPublicationController(
    private val source: PublicationSource,
    private val loader: ResourceLoader,
    private val scope: CoroutineScope,
    private val decodingDispatcher: CoroutineDispatcher = org.infinilect.app.reader.textPreparationDispatcher,
    private val progress: ProgressPersistence? = null,
    private val preparer: TextPreparer = defaultTextPreparer(),
    private val epubPreparer: EpubPreparer? = null,
    private val epubParser: EpubParser = defaultEpubParser(),
    private val epubSettings: EpubSettingsPersistence? = null,
    private val pagePreparer: PagePreparer? = null,
    private val pageSettings: PageSettingsPersistence? = null,
    private val pdfPreparer: PdfPreparer? = null,
    private val onOpened: (Publication) -> Unit = {},
) {
    private val mutableState = MutableStateFlow<OpenPublicationState>(OpenPublicationState.Idle)
    val state: StateFlow<OpenPublicationState> = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var closed = false

    fun open(publication: Publication) {
        if (state.value is OpenPublicationState.Loading) return
        closeReady()
        if (closed || publication.id.sourceId != source.id) {
            mutableState.value = OpenPublicationState.Error(publication,
                "This publication is unavailable from the selected source.")
            return
        }
        val ticket = ++generation
        mutableState.value = OpenPublicationState.Loading(publication)
        request = scope.launch {
            var prepared: TextDocument? = null
            var epubReader: EpubReaderController? = null
            var pageReader: PageReaderController? = null
            var pdfReader: PdfReaderController? = null
            var openingPages = false
            var openingEpub = false
            var handedOff = false
            try {
                val details = withTimeout(60_000) { source.getPublication(publication.id) }
                suspend fun prepareReader(): OpenPublicationState {
                    currentCoroutineContext().ensureActive()
                    if (details == null) {
                        throw OpeningException("This publication is no longer available.")
                    }
                    check(details.id == publication.id)
                    val textResource = selectResource(details, PublicationFormat.TEXT)
                    if (textResource == null && epubPreparer != null && selectResource(details, PublicationFormat.EPUB) != null) {
                        val epubResource = selectResource(details, PublicationFormat.EPUB)
                            ?: throw OpeningException("No supported reading format is available for this publication.")
                        openingEpub = true
                        val epub = epubPreparer.prepare(details, epubResource, loader)
                        // Assign ownership before the next suspension; every unhanded document closes.
                        val reader = try {
                            check(epub.publicationId == details.id)
                            EpubReaderController(epub, ReadingProgressId(details.id, epubResource.key, PublicationFormat.EPUB), scope, epubParser, progress, epubSettings)
                        } catch (error: Throwable) { epub.close(); throw error }
                        epubReader = reader
                        reader.initialize(progress?.get(reader.progressId))
                        currentCoroutineContext().ensureActive()
                        return OpenPublicationState.EpubReady(reader, details)
                    }
                    if (textResource == null && pagePreparer != null && details.resources.any { it.format == PublicationFormat.PAGES || it.format == PublicationFormat.CBZ }) {
                        openingPages = true
                        val document = pagePreparer.prepare(details, loader)
                        val reader = try {
                            check(document.publicationId == details.id)
                            PageReaderController(document, scope, progress, pageSettings)
                        } catch (error: Throwable) { document.close(); throw error }
                        pageReader = reader
                        reader.initialize(progress?.get(reader.progressId))
                        currentCoroutineContext().ensureActive()
                        return OpenPublicationState.PageReady(reader, details)
                    }
                    if (textResource == null && pdfPreparer != null && selectResource(details,PublicationFormat.PDF) != null) {
                        val resource = checkNotNull(selectResource(details,PublicationFormat.PDF))
                        val document = pdfPreparer.prepare(details,resource,loader)
                        val reader = try {
                            check(document.progressId == ReadingProgressId(details.id,resource.key,PublicationFormat.PDF))
                            PdfReaderController(document,scope,progress)
                        } catch (error: Throwable) { document.close(); throw error }
                        pdfReader = reader
                        reader.initialize(progress?.get(reader.progressId))
                        currentCoroutineContext().ensureActive()
                        return OpenPublicationState.PdfReady(reader,details)
                    }
                    val resource = textResource
                        ?: throw OpeningException("No readable text format is available for this publication.")
                    val loaded = loadTextDocument(details, resource, loader, decodingDispatcher, preparer).also { prepared = it }
                    val stored = loaded.progressId?.let { progress?.get(it) }
                    return OpenPublicationState.Ready(loaded,
                        progress?.let { TextReadingProgress(loaded, stored, it, scope) }, details)
                }
                // Synchronous PDF parsing/rendering has no reliable interruption deadline.
                // Cancellation is result-safe through adapter ownership and generation checks.
                val isPdf = details?.resources?.any { it.format == PublicationFormat.PDF } == true
                val loadedState = if (isPdf) prepareReader() else withTimeout(60_000) { prepareReader() }
                // Publish only after exiting the deadline successfully. A timeout/cancel at
                // the withTimeout return boundary must not record History or leak the EPUB.
                currentCoroutineContext().ensureActive()
                if (generation == ticket) {
                    mutableState.value = loadedState
                    val details = when (loadedState) {
                        is OpenPublicationState.Ready -> checkNotNull(loadedState.publication)
                        is OpenPublicationState.EpubReady -> loadedState.publication
                        is OpenPublicationState.PageReady -> loadedState.publication
                        is OpenPublicationState.PdfReady -> loadedState.publication
                        else -> error("Unexpected prepared state")
                    }
                    onOpened(details)
                    handedOff = true
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (generation == ticket) mutableState.value = OpenPublicationState.Error(publication,
                    if (openingPages) "Opening these pages timed out. Please try again." else if (openingEpub) "Opening this EPUB timed out. Please try again." else "Opening this text timed out. Please try again.")
            } catch (error: CancellationException) {
                if (generation == ticket) mutableState.value = OpenPublicationState.Idle
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val message = when (error) {
                    is PdfException -> error.failure.userMessage
                    is EpubException -> error.failure.userMessage
                    is TextDocumentException -> error.failure.userMessage
                    is OpeningException -> error.message!!
                    else -> if (openingPages) "Could not open these pages. The page sequence or its storage may be unavailable."
                        else if (openingEpub) "Could not acquire this EPUB. The source may be unavailable. Please try again."
                        else "Could not acquire this text. The source may be unavailable. Please try again."
                }
                if (generation == ticket) mutableState.value = OpenPublicationState.Error(publication, message)
            } finally {
                if (!handedOff) { prepared?.close(); epubReader?.close(); pageReader?.close(); pdfReader?.close() }
            }
        }.also { job ->
            job.invokeOnCompletion {
                // A cancelled scope may prevent the launch body from starting at all.
                if (generation == ticket && state.value is OpenPublicationState.Loading)
                    mutableState.value = OpenPublicationState.Idle
            }
        }
    }

    /** Back cancels work and invalidates even a noncooperative late result. */
    fun flushProgress() { (state.value as? OpenPublicationState.Ready)?.reading?.flush(); (state.value as? OpenPublicationState.EpubReady)?.reader?.flush(); (state.value as? OpenPublicationState.PageReady)?.reader?.flush(); (state.value as? OpenPublicationState.PdfReady)?.reader?.flush() }

    fun cancel() {
        closeReady()
        generation++
        request?.cancel()
        request = null
        mutableState.value = OpenPublicationState.Idle
    }

    private fun closeReady() {
        (state.value as? OpenPublicationState.PdfReady)?.reader?.close()
        (state.value as? OpenPublicationState.PageReady)?.reader?.close()
        (state.value as? OpenPublicationState.EpubReady)?.reader?.close()
        (state.value as? OpenPublicationState.Ready)?.let { it.reading?.close(); it.document.close() }
    }

    fun close() {
        closed = true
        cancel()
    }

    private class OpeningException(message: String) : Exception(message)
}

/** Platform Back is handled only away from the root search screen. */
internal fun OpenPublicationState.handlesBack(): Boolean = this !is OpenPublicationState.Idle
