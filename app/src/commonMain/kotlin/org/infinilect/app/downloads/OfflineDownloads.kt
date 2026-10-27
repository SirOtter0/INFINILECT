// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.downloads

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.infinilect.core.*

internal enum class DownloadStatus { NOT_DOWNLOADED, PENDING, DOWNLOADING, DOWNLOADED, ERROR, CANCELLED }
internal enum class DownloadFailure { NOT_ALLOWED, CONNECTION, INCOMPLETE, INVALID, LIMIT, SPACE, STORAGE, MISSING, INTERRUPTED, BUSY }
internal class DownloadException(val failure: DownloadFailure) : Exception(failure.name)
internal enum class DownloadCapability { AUTHORIZED_SOURCE, IMPORTED, UNSUPPORTED }
internal data class DownloadOffer(val publication: Publication,val resource: PublicationResource,val size: Long?)
/** Explicit opt-in per adapter; discovery metadata alone is never an acquisition grant. */
internal interface OfflineDownloadProvider {
    val downloadSourceId: SourceId
    suspend fun downloadOffers(id: PublicationId): List<DownloadOffer>
    suspend fun openDownload(offer: DownloadOffer): ResourceContent
}
internal data class DownloadEntry(val publication: Publication,val format: PublicationFormat,val status: DownloadStatus,
    val resource: PublicationResource? = null,val size: Long? = null,val sha256: String? = null,val completedAt: Long? = null,val failure: DownloadFailure? = null,val revision: Long = 0) {
    val id get()=publication.id
}
internal data class DownloadProgress(val bytes: Long=0,val total: Long?=null)
internal data class DownloadSnapshot(val entries: List<DownloadEntry> = emptyList(),val usedBytes: Long=0,val quotaBytes: Long=512L*1024*1024,val damagedRecords: Boolean=false,val revision: Long=0)
internal interface OfflineReadingCopy {
    val publication: Publication
    val loader: ResourceLoader
    fun close()
}
internal interface DownloadBackend {
    fun capability(id: PublicationId): DownloadCapability
    suspend fun load(): DownloadSnapshot
    suspend fun offers(id: PublicationId): List<DownloadOffer>
    suspend fun pending(offer: DownloadOffer): DownloadEntry
    suspend fun download(entry: DownloadEntry, started: suspend (DownloadEntry)->Unit, progress: suspend (DownloadProgress)->Unit): DownloadEntry
    suspend fun settle(id: PublicationId,status: DownloadStatus,failure: DownloadFailure?): DownloadEntry?
    suspend fun remove(id: PublicationId)
    suspend fun acquire(id: PublicationId): OfflineReadingCopy?
    suspend fun quota(bytes: Long)
    suspend fun snapshot(): DownloadSnapshot
    suspend fun close()
}
internal data class DownloadCatalogue(val loaded: Boolean=false,val entries: List<DownloadEntry> = emptyList(),val usedBytes: Long=0,val quotaBytes: Long=512L*1024*1024,val failure: DownloadFailure?=null,val busy: Set<PublicationId> = emptySet(),val revision: Long=0)

/** Application-owned, one active transfer and at most eight accepted pending operations.
 * Navigation never owns these jobs. Android backgrounding cancels rather than impersonating
 * a persistent OS download service. Retry is explicit and starts from fresh authorization. */
internal class OfflineDownloads(val backend: DownloadBackend,dispatcher: CoroutineDispatcher=Dispatchers.Main.immediate) {
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val mutable=MutableStateFlow(DownloadCatalogue())
    val catalogue=mutable.asStateFlow()
    val available=MutableStateFlow<Set<PublicationId>>(emptySet())
    private val progress=mutableMapOf<PublicationId,MutableStateFlow<DownloadProgress>>()
    fun progress(id: PublicationId): StateFlow<DownloadProgress> = progress.getOrPut(id) {MutableStateFlow(DownloadProgress())}
    private val commands=Channel<PublicationId>(8)
    private val closed=MutableStateFlow(false)
    private val mutations=mutableSetOf<Job>()
    private var active: Pair<PublicationId,Deferred<DownloadEntry>>?=null
    private var foreground=true
    private var shutdown: Job?=null
    private fun entry(id: PublicationId)=catalogue.value.entries.firstOrNull {it.id==id}
    private fun publish(row: DownloadEntry) {
        if(closed.value) return
        mutable.update {old -> if((old.entries.firstOrNull {it.id==row.id}?.revision ?: old.revision)>row.revision) old else old.copy(revision=maxOf(old.revision,row.revision),entries=(old.entries.filterNot {e->e.id==row.id}+row).sortedWith(compareBy({it.publication.title},{it.id.sourceId.value},{it.id.localId})))}
        available.value=catalogue.value.entries.filter {it.status==DownloadStatus.DOWNLOADED}.map {it.id}.toSet()
    }
    private suspend fun refresh() {
        val s=backend.snapshot()
        if(!closed.value) {
            mutable.update {if(it.revision>s.revision) it else it.copy(loaded=true,entries=s.entries,usedBytes=s.usedBytes,quotaBytes=s.quotaBytes,revision=s.revision,failure=if(s.damagedRecords) DownloadFailure.INVALID else null)}
            available.value=catalogue.value.entries.filter {it.status==DownloadStatus.DOWNLOADED}.map {it.id}.toSet()
        }
    }
    private fun fail(reason: DownloadFailure) {if(!closed.value) mutable.update {it.copy(failure=reason)}}
    private fun mutate(id: PublicationId? = null,block: suspend ()->Unit) {
        if(closed.value || id!=null && id in catalogue.value.busy) return
        if(id!=null) mutable.update {it.copy(busy=it.busy+id)}
        lateinit var job: Job
        job=scope.launch(start=CoroutineStart.LAZY) {
            try {block()}
            catch(e: CancellationException) {throw e}
            catch(e: DownloadException) {fail(e.failure)}
            catch(_: Exception) {fail(DownloadFailure.STORAGE)}
            finally {if(id!=null) mutable.update {it.copy(busy=it.busy-id)}}
        }
        mutations+=job;job.invokeOnCompletion {mutations-=job};job.start()
    }
    private val worker=scope.launch {
        try {
            val loaded=backend.load()
            mutable.value=DownloadCatalogue(true,loaded.entries,loaded.usedBytes,loaded.quotaBytes,if(loaded.damagedRecords) DownloadFailure.INVALID else null,revision=loaded.revision)
            available.value=loaded.entries.filter {it.status==DownloadStatus.DOWNLOADED}.map {it.id}.toSet()
        } catch(e: CancellationException) {throw e} catch(e: DownloadException) {fail(e.failure)} catch(_: Exception) {fail(DownloadFailure.STORAGE)}
        for(id in commands) {
            val row=entry(id)?.takeIf {it.status==DownloadStatus.PENDING} ?: continue
            if(!foreground || closed.value) {
                try {backend.settle(id,DownloadStatus.CANCELLED,DownloadFailure.INTERRUPTED)?.let(::publish)} catch(_: Exception) {fail(DownloadFailure.STORAGE)}
                continue
            }
            val currentProgress=progress.getOrPut(id) {MutableStateFlow(DownloadProgress())}
            val job=scope.async {backend.download(row,{withContext(dispatcher) {publish(it)}},{withContext(dispatcher) {if(!closed.value) currentProgress.value=it}})}
            active=id to job
            try {publish(job.await());refresh()}
            catch(e: CancellationException) {
                withContext(NonCancellable) {try {backend.settle(id,DownloadStatus.CANCELLED,DownloadFailure.INTERRUPTED)?.let(::publish)} catch(_: Exception) {fail(DownloadFailure.STORAGE)}}
                currentCoroutineContext().ensureActive()
            } catch(e: Exception) {
                val failure=(e as? DownloadException)?.failure ?: DownloadFailure.CONNECTION
                try {backend.settle(id,DownloadStatus.ERROR,failure)?.let(::publish);refresh()} catch(_: Exception) {fail(DownloadFailure.STORAGE)}
            } finally {active=null}
        }
    }
    fun enqueue(offer: DownloadOffer) {
        val id=offer.publication.id
        if(closed.value || !foreground || !catalogue.value.loaded || entry(id)?.status in setOf(DownloadStatus.PENDING,DownloadStatus.DOWNLOADING,DownloadStatus.DOWNLOADED)) return
        if(catalogue.value.entries.count {it.status in setOf(DownloadStatus.PENDING,DownloadStatus.DOWNLOADING)}+catalogue.value.busy.size>=8) {fail(DownloadFailure.LIMIT);return}
        progress.getOrPut(id) {MutableStateFlow(DownloadProgress())}.value=DownloadProgress(total=offer.size)
        mutate(id) {
            val row=backend.pending(offer)
            if(!foreground || closed.value) {backend.settle(id,DownloadStatus.CANCELLED,DownloadFailure.INTERRUPTED)?.let(::publish);return@mutate}
            publish(row)
            if(!commands.trySend(id).isSuccess) backend.settle(id,DownloadStatus.CANCELLED,DownloadFailure.LIMIT)?.let(::publish)
        }
    }
    suspend fun offers(id: PublicationId)=backend.offers(id)
    fun retry(id: PublicationId) {
        if(!foreground || !catalogue.value.loaded || entry(id)?.status !in setOf(DownloadStatus.ERROR,DownloadStatus.CANCELLED)) return
        if(catalogue.value.entries.count {it.status in setOf(DownloadStatus.PENDING,DownloadStatus.DOWNLOADING)}+catalogue.value.busy.size>=8) {fail(DownloadFailure.LIMIT);return}
        mutate(id) {
            val previous=entry(id) ?: return@mutate
            val offer=backend.offers(id).firstOrNull {it.resource==previous.resource} ?: throw DownloadException(DownloadFailure.NOT_ALLOWED)
            val row=backend.pending(offer);progress.getOrPut(id) {MutableStateFlow(DownloadProgress())}.value=DownloadProgress(total=offer.size)
            if(!foreground || closed.value) {backend.settle(id,DownloadStatus.CANCELLED,DownloadFailure.INTERRUPTED)?.let(::publish);return@mutate}
            publish(row);if(!commands.trySend(id).isSuccess) backend.settle(id,DownloadStatus.CANCELLED,DownloadFailure.LIMIT)?.let(::publish)
        }
    }
    fun cancel(id: PublicationId) {
        if(active?.first==id) {active?.second?.cancel();return}
        if(entry(id)?.status in setOf(DownloadStatus.PENDING,DownloadStatus.DOWNLOADING)) mutate(id) {backend.settle(id,DownloadStatus.CANCELLED,DownloadFailure.INTERRUPTED)?.let(::publish)}
    }
    fun remove(id: PublicationId) {mutate(id) {try {backend.remove(id)} finally {refresh()};progress.remove(id)}}
    fun setQuota(bytes: Long) {mutate {backend.quota(bytes);refresh()}}
    fun reload() {mutate {backend.load();refresh()}}
    suspend fun acquire(id: PublicationId): OfflineReadingCopy? {
        try {return backend.acquire(id)} catch(e: DownloadException) {
            // A damaged/unavailable optional retained store must not disable the original
            // online reader. Invalid bytes never escape; the source will reauthorize anew.
            try {refresh()} catch(cancelled: CancellationException) {throw cancelled} catch(_: DownloadException) { }
            fail(e.failure)
            return null
        }
    }
    fun pause() {foreground=false;active?.second?.cancel();catalogue.value.entries.filter {it.status==DownloadStatus.PENDING}.forEach {cancel(it.id)}}
    fun resume() {if(!closed.value) foreground=true}
    fun close() {
        if(closed.value) return
        closed.value=true;foreground=false;active?.second?.cancel();commands.close()
        shutdown=scope.launch {
            active?.second?.join();worker.cancelAndJoin();mutations.toList().joinAll()
            withContext(NonCancellable) {
                try {backend.snapshot().entries.filter {it.status in setOf(DownloadStatus.PENDING,DownloadStatus.DOWNLOADING)}.forEach {backend.settle(it.id,DownloadStatus.CANCELLED,DownloadFailure.INTERRUPTED)}}
                catch(_: Exception) { /* Previous atomic records remain recoverable on next load. */ }
                try {backend.close()} catch(_: Exception) { /* Closed owner; persistent records remain authoritative. */ }
            }
            scope.cancel()
        }
    }
    suspend fun awaitClosed() {shutdown?.join()}
}
