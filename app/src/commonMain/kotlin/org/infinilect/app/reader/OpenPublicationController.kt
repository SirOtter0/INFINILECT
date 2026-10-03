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
import org.infinilect.core.Publication
import org.infinilect.core.PublicationFormat
import org.infinilect.core.PublicationSource
import org.infinilect.core.ResourceLoader
import org.infinilect.app.acquisition.selectResource

internal sealed interface OpenPublicationState {
    data object Idle : OpenPublicationState
    data class Loading(val publication: Publication) : OpenPublicationState
    data class Ready(val document: TextDocument) : OpenPublicationState
    data class Error(val publication: Publication, val userMessage: String) : OpenPublicationState
}

/** UI-thread actions; the supplied session scope owns work, never Compose or a network client. */
internal class OpenPublicationController(
    private val source: PublicationSource,
    private val loader: ResourceLoader,
    private val scope: CoroutineScope,
    private val decodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableState = MutableStateFlow<OpenPublicationState>(OpenPublicationState.Idle)
    val state: StateFlow<OpenPublicationState> = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var closed = false

    fun open(publication: Publication) {
        if (state.value is OpenPublicationState.Loading) return
        if (closed || publication.id.sourceId != source.id) {
            mutableState.value = OpenPublicationState.Error(publication,
                "This publication is unavailable from the selected source.")
            return
        }
        val ticket = ++generation
        mutableState.value = OpenPublicationState.Loading(publication)
        request = scope.launch {
            try {
                val document = withTimeout(60_000) {
                    val details = source.getPublication(publication.id)
                    currentCoroutineContext().ensureActive()
                    if (details == null) {
                        throw OpeningException("This publication is no longer available.")
                    }
                    check(details.id == publication.id)
                    val resource = selectResource(details, PublicationFormat.TEXT)
                        ?: throw OpeningException("No readable text format is available for this publication.")
                    loadTextDocument(details, resource, loader, decodingDispatcher)
                }
                currentCoroutineContext().ensureActive()
                if (generation == ticket) mutableState.value = OpenPublicationState.Ready(document)
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (generation == ticket) mutableState.value = OpenPublicationState.Error(publication,
                    "Opening this text timed out. Please try again.")
            } catch (error: CancellationException) {
                if (generation == ticket) mutableState.value = OpenPublicationState.Idle
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val message = when (error) {
                    is TextDocumentException -> error.failure.userMessage
                    is OpeningException -> error.message!!
                    else -> "Could not acquire this text. The source may be unavailable. Please try again."
                }
                if (generation == ticket) mutableState.value = OpenPublicationState.Error(publication, message)
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
    fun cancel() {
        generation++
        request?.cancel()
        request = null
        mutableState.value = OpenPublicationState.Idle
    }

    fun close() {
        closed = true
        cancel()
    }

    private class OpeningException(message: String) : Exception(message)
}
