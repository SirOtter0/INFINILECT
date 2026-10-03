// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import org.infinilect.app.collections.CollectionsController
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.core.*

internal enum class Destination { SEARCH, LIBRARY, HISTORY }

/** Small session navigation. Saved metadata resolves only IDs via an existing owning source.
 * A collection open uses a separate reader session so search/query/results remain intact.
 */
internal class ApplicationSession(
    private val sources: ApplicationSources,
    scope: CoroutineScope,
    private val decodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = sources.progress?.clock ?: { Clock.System.now().toEpochMilliseconds() },
) : ApplicationSessionLifetime {
    private val job=SupervisorJob(scope.coroutineContext[Job])
    private val scope=CoroutineScope(scope.coroutineContext+job)
    private val mutableSelected=MutableStateFlow(0)
    val selected=mutableSelected.asStateFlow()
    private val mutableDestination=MutableStateFlow(Destination.SEARCH)
    val destination=mutableDestination.asStateFlow()
    val collections=CollectionsController(sources.collections,this.scope,clock)
    private fun session(option: SourceOption)=ReadingSession(option.source,this.scope,option.textReadingEnabled,
        sources.loaderFor(option.source),decodingDispatcher,sources.progress) { publication ->
        collections.enteredReader(publication)
        sources.collections?.recordOpened(publication,clock())
    }
    private val mutableSearch=MutableStateFlow(session(sources.options.first()))
    val searchSession=mutableSearch.asStateFlow()
    private var savedReader: ReadingSession?=null
    private val mutableOpening=MutableStateFlow<OpenPublicationState>(OpenPublicationState.Idle)
    val opening=mutableOpening.asStateFlow()
    private var observer: Job?=null
    private var closed=false
    init { observe(searchSession.value) }
    private fun observe(session: ReadingSession) {
        observer?.cancel(); mutableOpening.value=session.opening.state.value
        observer=scope.launch { session.opening.state.collect { mutableOpening.value=it } }
    }
    fun selectSource(index: Int) {
        if(closed || index !in sources.options.indices || index==selected.value || opening.value !is OpenPublicationState.Idle) return
        searchSession.value.close()
        mutableSelected.value=index; mutableSearch.value=session(sources.options[index]); observe(searchSession.value)
    }
    fun navigate(destination: Destination) {
        if(closed || opening.value !is OpenPublicationState.Idle) return
        mutableDestination.value=destination
        when(destination) { Destination.LIBRARY -> collections.refreshLibrary(); Destination.HISTORY -> collections.refreshHistory(); else -> Unit }
    }
    fun openSaved(snapshot: PublicationSnapshot) {
        if(closed || opening.value !is OpenPublicationState.Idle) return
        observer?.cancel()
        val option=sources.options.firstOrNull { it.source.id==snapshot.id.sourceId }
        // Resources are deliberately absent. Even valid stored rights never authorize bytes.
        val placeholder=Publication(snapshot.id,snapshot.title,snapshot.type,snapshot.authors,languages=snapshot.languages)
        if(option==null || !option.textReadingEnabled) {
            mutableOpening.value=OpenPublicationState.Error(placeholder,"This publication cannot be opened from its source.")
            return
        }
        savedReader?.close(); savedReader=session(option)
        observe(savedReader!!); savedReader!!.open(placeholder)
        mutableOpening.value=savedReader!!.opening.state.value
    }
    fun openSearch(publication: Publication) {
        if(closed || destination.value!=Destination.SEARCH) return
        searchSession.value.open(publication); mutableOpening.value=searchSession.value.opening.state.value
    }
    fun canRetry(publication: Publication)=sources.options.any { it.source.id==publication.id.sourceId && it.textReadingEnabled }
    fun sourceName(id: SourceId)=sources.options.firstOrNull { it.source.id==id }?.name ?: id.value
    fun retry(publication: Publication) {
        if(closed || !canRetry(publication)) return
        val active=savedReader ?: searchSession.value
        observe(active); active.open(publication); mutableOpening.value=active.opening.state.value
    }
    fun back() {
        if(closed) return
        if(opening.value !is OpenPublicationState.Idle) {
            (savedReader ?: searchSession.value).back(); savedReader?.close(); savedReader=null
            collections.leftReader(); observe(searchSession.value)
            when(destination.value) { Destination.LIBRARY -> collections.refreshLibrary(); Destination.HISTORY -> collections.refreshHistory(); else -> Unit }
        } else if(destination.value!=Destination.SEARCH) {
            collections.dismissClearHistory(); navigate(Destination.SEARCH)
        }
    }
    fun handlesBack()=opening.value !is OpenPublicationState.Idle || destination.value!=Destination.SEARCH
    override fun flushProgress() { (savedReader ?: searchSession.value).flushProgress() }
    override fun close() {
        if(closed) return
        closed=true; observer?.cancel(); savedReader?.close(); searchSession.value.close(); collections.close(); job.cancel()
    }
}
