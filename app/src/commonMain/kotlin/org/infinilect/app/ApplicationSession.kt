// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import org.infinilect.app.collections.CollectionsController
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.core.*

internal enum class Destination { HOME, SEARCH, LIBRARY, HISTORY, SETTINGS }

/** Small session navigation. Saved metadata resolves only IDs via an existing owning source.
 * A collection open uses a separate reader session so search/query/results remain intact.
 */
internal class ApplicationSession(
    private val sources: ApplicationSources,
    scope: CoroutineScope,
    private val decodingDispatcher: CoroutineDispatcher = org.infinilect.app.reader.textPreparationDispatcher,
    private val clock: () -> Long = sources.progress?.clock ?: { Clock.System.now().toEpochMilliseconds() },
) : ApplicationSessionLifetime {
    private val job=SupervisorJob(scope.coroutineContext[Job])
    private val scope=CoroutineScope(scope.coroutineContext+job)
    private val mutableSelected=MutableStateFlow(0)
    val selected=mutableSelected.asStateFlow()
    private val mutableDestination=MutableStateFlow(Destination.HOME)
    val destination=mutableDestination.asStateFlow()
    val covers get() = sources.covers
    val descriptions get() = sources.descriptions
    /** Details may query one existing progress summary after a restart; never save/normalize it. */
    suspend fun savedPublicationProgress(id: PublicationId) = sources.progress?.publicationSummaries(listOf(id))
        ?.filter { it.id.publicationId == id }?.maxByOrNull { it.updatedAtEpochMillis }
    val collections=CollectionsController(sources.collections,this.scope,clock) { sources.covers?.invalidate(it) }
    private fun session(option: SourceOption)=ReadingSession(option.source,this.scope,option.textReadingEnabled,
        sources.loaderFor(option.source),decodingDispatcher,sources.progress,sources.textPreparer,option.epubReadingEnabled,sources.epubPreparer,sources.epubSettings,option.pageReadingEnabled,sources.pagePreparer,sources.pageSettings,option.pdfReadingEnabled,sources.pdfPreparer) { publication ->
        collections.enteredReader(publication)
        sources.collections?.recordOpened(publication,clock())
    }
    private val mutableSearch=MutableStateFlow(session(sources.options.first()))
    val searchSession=mutableSearch.asStateFlow()
    private var savedReader: ReadingSession?=null
    private val mutableOpening=MutableStateFlow<OpenPublicationState>(OpenPublicationState.Idle)
    val opening=mutableOpening.asStateFlow()
    private var observer: Job?=null
    private val mutableHomeProgress = MutableStateFlow<List<ReadingProgress>>(emptyList())
    val homeProgress = mutableHomeProgress.asStateFlow()
    private var homeLookup: Job? = null
    private var homeGeneration = 0L
    private fun refreshHomeProgress() {
        if (closed || opening.value !is OpenPublicationState.Idle) return
        homeLookup?.cancel()
        val generation = ++homeGeneration
        val ids = collections.history.value.entries.take(8).map { it.publication.id }
        homeLookup = scope.launch {
            val summaries = sources.progress?.publicationSummaries(ids).orEmpty()
            currentCoroutineContext().ensureActive()
            if (!closed && generation == homeGeneration) mutableHomeProgress.value = summaries
        }
    }
    private var closed=false
    private val mutableImport = MutableStateFlow(org.infinilect.app.imports.LocalImportState())
    val importing = mutableImport.asStateFlow()
    private var importRequest: Job? = null
    private var importGeneration = 0L
    init {
        observe(searchSession.value); collections.refreshHistory()
        this.scope.launch { collections.history.collect { if (destination.value == Destination.HOME && !it.loading) refreshHomeProgress() } }
    }
    private fun observe(session: ReadingSession) {
        observer?.cancel(); mutableOpening.value=session.opening.state.value
        observer=scope.launch { session.opening.state.collect { mutableOpening.value=it } }
    }
    fun selectSource(index: Int) {
        if(closed || importing.value.busy || index !in sources.options.indices || index==selected.value || opening.value !is OpenPublicationState.Idle) return
        searchSession.value.close()
        mutableSelected.value=index; mutableSearch.value=session(sources.options[index]); observe(searchSession.value)
    }
    fun navigate(destination: Destination) {
        if(closed || importing.value.busy || opening.value !is OpenPublicationState.Idle) return
        if (destination != mutableDestination.value) collections.clearSelection()
        mutableDestination.value=destination
        if (destination == Destination.HOME) refreshHomeProgress() else { homeGeneration++; homeLookup?.cancel() }
        when(destination) { Destination.LIBRARY -> collections.refreshLibrary(); Destination.HISTORY -> collections.refreshHistory(); Destination.HOME -> { collections.refreshLibrary(); collections.refreshHistory() }; else -> Unit }
    }
    fun openSaved(snapshot: PublicationSnapshot, pdfRecreationIndex: Int? = null) {
        if(closed || importing.value.busy || opening.value !is OpenPublicationState.Idle) return
        collections.clearSelection()
        homeGeneration++; homeLookup?.cancel()
        observer?.cancel()
        val option=sources.options.firstOrNull { it.source.id==snapshot.id.sourceId }
        // Resources are deliberately absent. Even valid stored rights never authorize bytes.
        val placeholder=Publication(snapshot.id,snapshot.title,snapshot.type,snapshot.authors,languages=snapshot.languages)
        if(option==null || !(option.textReadingEnabled || option.epubReadingEnabled || option.pageReadingEnabled || option.pdfReadingEnabled)) {
            mutableOpening.value=OpenPublicationState.Error(placeholder,"This publication cannot be opened from its source.")
            return
        }
        savedReader?.close(); savedReader=session(option)
        observe(savedReader!!); savedReader!!.open(placeholder,pdfRecreationIndex)
        mutableOpening.value=savedReader!!.opening.state.value
    }
    fun restoreLocalPdf(localId: String, destination: String, pageIndex: Int) {
        if (!Regex("[0-9a-f]{64}").matches(localId)) return
        val local=sources.options.firstOrNull { it.pdfReadingEnabled && it.source.id.value == "local-imports" } ?: return
        val restoredDestination=Destination.entries.firstOrNull { it.name == destination } ?: Destination.SEARCH
        navigate(restoredDestination)
        openSaved(PublicationSnapshot(PublicationId(local.source.id,localId),"PDF",PublicationType.DOCUMENT),pageIndex.coerceIn(0,org.infinilect.core.PdfLimits.PAGES-1))
    }
    fun openSearch(publication: Publication) {
        if(closed || importing.value.busy || destination.value!=Destination.SEARCH) return
        searchSession.value.open(publication); mutableOpening.value=searchSession.value.opening.state.value
    }
    fun canRetry(publication: Publication)=sources.options.any { it.source.id==publication.id.sourceId && (it.textReadingEnabled || it.epubReadingEnabled || it.pageReadingEnabled || it.pdfReadingEnabled) }
    fun sourceName(id: SourceId)=sources.options.firstOrNull { it.source.id==id }?.name ?: id.value
    fun retry(publication: Publication) {
        if(closed || !canRetry(publication)) return
        val active=savedReader ?: searchSession.value
        observe(active); active.open(publication); mutableOpening.value=active.opening.state.value
    }
    fun back() {
        if(closed) return
        if(importing.value.busy) { cancelImport(); return }
        if (opening.value is OpenPublicationState.Idle && collections.selection.value.isNotEmpty()) { collections.clearSelection(); return }
        if(opening.value !is OpenPublicationState.Idle) {
            (savedReader ?: searchSession.value).back(); savedReader?.close(); savedReader=null
            collections.leftReader(); observe(searchSession.value)
            when(destination.value) { Destination.LIBRARY -> collections.refreshLibrary(); Destination.HISTORY -> collections.refreshHistory(); Destination.HOME -> { collections.refreshLibrary(); collections.refreshHistory() }; else -> Unit }
        } else if(destination.value!=Destination.HOME) {
            collections.dismissClearHistory(); navigate(Destination.HOME)
        }
    }
    fun handlesBack()=importing.value.busy || opening.value !is OpenPublicationState.Idle || destination.value!=Destination.HOME
    fun importLocal(picker: org.infinilect.app.imports.LocalFilePicker) {
        val importer = sources.localImports ?: return
        if(closed || importing.value.busy || opening.value !is OpenPublicationState.Idle) return
        val generation = ++importGeneration
        mutableImport.value = org.infinilect.app.imports.LocalImportState(busy=true)
        importRequest = scope.launch {
            try {
                val selection = picker.pick() ?: return@launch
                val publication = importer.import(selection)
                currentCoroutineContext().ensureActive()
                if(closed || generation != importGeneration) return@launch
                val result = sources.collections?.library?.put(PublicationSnapshot.from(publication),clock())
                currentCoroutineContext().ensureActive()
                if(closed || generation != importGeneration) return@launch
                if(result !is LocalStoreResult.Success) {
                    mutableImport.value = org.infinilect.app.imports.LocalImportState(message="The file was imported, but could not be added to Library. Find it under Imported files and try again.")
                    return@launch
                }
                sources.collections.changed()
                mutableImport.value = org.infinilect.app.imports.LocalImportState()
                openSaved(PublicationSnapshot.from(publication))
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(!closed && generation == importGeneration) mutableImport.value = org.infinilect.app.imports.LocalImportState(message=
                    (e as? org.infinilect.app.imports.LocalImportException)?.failure?.userMessage ?: "The selected file could not be imported. Please try again.")
            } finally {
                if(generation == importGeneration && mutableImport.value.busy) mutableImport.value = org.infinilect.app.imports.LocalImportState()
            }
        }.also { request -> request.invokeOnCompletion {
            if(generation == importGeneration && mutableImport.value.busy) mutableImport.value = org.infinilect.app.imports.LocalImportState()
        } }
    }
    private fun cancelImport() { importGeneration++; importRequest?.cancel(); importRequest=null; mutableImport.value=org.infinilect.app.imports.LocalImportState() }
    override fun flushProgress() { (savedReader ?: searchSession.value).flushProgress() }
    override fun close() {
        if(closed) return
        closed=true; homeGeneration++; homeLookup?.cancel(); cancelImport(); observer?.cancel(); savedReader?.close(); searchSession.value.close(); collections.close(); job.cancel()
    }
}
