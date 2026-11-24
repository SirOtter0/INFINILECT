// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.discovery.*
import org.infinilect.app.downloads.*
import org.infinilect.app.network.*
import org.infinilect.core.*

private const val AUTOMATIC_EPUB_BYTES = 512L * 1024
private class WikiAutomaticLimit : Exception()
private class WikiHttp(val status:Int):Exception()
private class WikiFailure(kind:CatalogErrorKind,cause:Throwable?=null):CatalogSourceException(kind,cause) {
    override val message get()=catalogFailureMessage("Wikisource",kind)
}
/** Shared under-demand Wikisource adapter (one internally configured project). One metadata operation and one streamed export at most.
 * No remote publication data can configure hosts, paths, approval or redirects. */
internal class WikisourceSource(val edition:WikisourceEdition=WikisourceEdition.ES,private val engine:HttpClientEngine=platformHttpEngine(),private val now:()->Long=System::currentTimeMillis):
    PublicationSource,DiscoverySource,OfflineDownloadProvider,CatalogPublicationSource,ReadabilityCatalogSource,AutoCloseable {
    override val id=edition.id
    override val downloadSourceId get()=id
    private val lifetime=SupervisorJob()
    private val scope=CoroutineScope(lifetime+Dispatchers.IO)
    private val closed=AtomicBoolean();private val shutdown=CompletableDeferred<Unit>()
    private val metadataGate=Mutex();private val exportGate=Mutex();private val cooldown=AtomicLong()
    private val inspectionGate = Mutex()
    private val automaticRequests = AtomicLong()
    private val automaticPayloadBytes = AtomicLong()
    internal fun automaticTraffic() = automaticRequests.get() to automaticPayloadBytes.get()
    private val client=HttpClient(engine) {followRedirects=false;expectSuccess=false;install(HttpTimeout) {connectTimeoutMillis=5000;requestTimeoutMillis=20000;socketTimeoutMillis=15000}}
    private val rights=WikisourceRights(::bytes)
    private val catalog=WikisourceCatalog(edition,::bytes,now,rights::assess)
    private data class Observation(val state: CatalogEpubState, val at: Long, val policy: Int = 1)
    private val observations=linkedMapOf<Pair<PublicationId,String>,Observation>()
    private fun observation(resource: PublicationResource): CatalogEpubState? = synchronized(observations) {
        val key = resource.publicationId to resource.key
        observations.remove(key)?.takeIf {
            it.policy == 1 && now() - it.at in 0..(if (it.state == CatalogEpubState.TEMPORARY_ERROR) 30_000 else 600_000)
        }?.also { observations[key] = it }?.state
    }
    private val metadataRequests = AtomicLong()
    private val metadataPayloadBytes = AtomicLong()
    internal fun metadataTraffic() = metadataRequests.get() to metadataPayloadBytes.get()
    private var budget: WikiAssessmentBudget? = null // metadata gate only
    private val minuteBudget = WikiAssessmentBudget()
    private var windowStart: Long? = null
    private val assessedDetails = AtomicLong()
    internal fun assessedDetailCount() = assessedDetails.get()
    private val filter = WikisourceReadableCatalog(edition, now, catalog::discover, { id ->
        assessedDetails.incrementAndGet()
        resolvedInfo(id)
    }, ::knownReadability)
    private fun knownReadability(publication: Publication): CatalogReadability {
        val resource = publication.resources.singleOrNull() ?: return CatalogReadability.WEB_ONLY
        val record = catalog.cached(publication.id) ?: return CatalogReadability.UNVERIFIED
        if (resource.key != wikiResourceKey(record.page.revision)) return CatalogReadability.UNVERIFIED
        val state = if (record.publication.resources.isEmpty()) {
            if (record.page.kind in setOf(CatalogPageKind.AUTHOR, CatalogPageKind.INDEX, CatalogPageKind.VERSIONS, CatalogPageKind.DISAMBIGUATION))
                CatalogEpubState.WEB_ONLY else CatalogEpubState.UNAVAILABLE
        } else observation(resource) ?: CatalogEpubState.POTENTIAL
        return CatalogPublicationInfo(record.page.kind, state, authorized = record.grant.authorized, rightsState = record.grant.state).readability()
    }
    override suspend fun cachedReadability(publication: Publication): CatalogReadability =
        metadataGate.withLock { knownReadability(publication) }

    override suspend fun discoverReadable(request: DiscoveryRequest, token: String?): DiscoveryPage {
        if (request.genre != null || request.language != null && request.language != edition.language) return DiscoveryPage(emptyList())
        return operation {
            withAssessmentBudget(request.readabilityMode == ReadabilityMode.READABLE && request.assessCandidates) {
                filter.load(request, token)
            }
        }
    }
    private suspend fun <T> withAssessmentBudget(enabled: Boolean, block: suspend () -> T): T {
        if (!enabled) return block()
        if (windowStart?.let { now() - it in 0..59_999 } != true) {
            windowStart = now()
            minuteBudget.reset()
        }
        budget = minuteBudget
        try {
            return block()
        } finally {
            budget = null
        }
    }

    private var inspectExport: (suspend (Publication, PublicationResource, ResourceLoader) -> Unit)? = null
    fun epubInspector(block: suspend (Publication, PublicationResource, ResourceLoader) -> Unit) {
        check(inspectExport == null)
        inspectExport = block
    }
    override val canCheckEpub get()=inspectExport!=null
    private fun observed(resource:PublicationResource,state:CatalogEpubState) = synchronized(observations) {
        observations.remove(resource.publicationId to resource.key)
        observations[resource.publicationId to resource.key]=Observation(state, now())
        while(observations.size>32)observations.remove(observations.keys.first())
    }
    private fun throttle(seconds:Long=30) {cooldown.set(now()+seconds.coerceIn(30,300)*1000)}
    private suspend fun <T> operation(block:suspend()->T):T {
        check(!closed.get());currentCoroutineContext().ensureActive()
        val request=scope.async {metadataGate.withLock {
            check(!closed.get());if(now()<cooldown.get())throw WikiFailure(CatalogErrorKind.HTTP_RATE_LIMITED)
            try {block()} catch(e:CancellationException){throw e}
            catch(e:WikisourceApiBusy){throttle();throw WikiFailure(CatalogErrorKind.HTTP_RATE_LIMITED)}
            catch(e:WikisourceInvalid){throw WikiFailure(CatalogErrorKind.INVALID_JSON,e)}
            catch(e:WikiHttp){throw WikiFailure(when(e.status){429->CatalogErrorKind.HTTP_RATE_LIMITED;403->CatalogErrorKind.HTTP_FORBIDDEN;in 300..399->CatalogErrorKind.REDIRECT;else->CatalogErrorKind.HTTP_ERROR},e)}
            catch(e:WikisourceReviewRequired){throw e}
            catch(e:WikiBudgetReached){throw e}
            catch(e:CatalogSourceException){throw e}
            catch(e:Exception){throw WikiFailure(CatalogErrorKind.CONNECTION,e)}
        }}
        try {return request.await()} finally {if(!request.isCompleted)withContext(NonCancellable+Dispatchers.IO){request.cancelAndJoin()}}
    }
    private fun HttpResponse.size(maximum:Long):Long? {
        if(headers[HttpHeaders.ContentEncoding]?.let {!it.equals("identity",true)}==true)throw WikisourceInvalid()
        val raw=headers[HttpHeaders.ContentLength] ?: return null
        if(!Regex("[0-9]{1,10}").matches(raw))throw WikisourceInvalid()
        return raw.toLongOrNull()?.takeIf {it in 1..maximum} ?: throw WikisourceInvalid()
    }
    private fun HttpResponse.success() {
        if(status.value==429)throttle(headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 30)
        if(status.value!=200)throw WikiHttp(status.value)
    }
    private suspend fun bytes(url:String):ByteArray {
        check(url.startsWith("${edition.api}?") || url.startsWith("$WIKISOURCE_COMMONS_API?"))
        budget?.request()
        metadataRequests.incrementAndGet()
        return client.prepareGet(url) {header(HttpHeaders.UserAgent,PROJECT_USER_AGENT);header(HttpHeaders.Accept,"application/json");header(HttpHeaders.AcceptEncoding,"identity")}.execute {response ->
            response.success()
            if(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()!="application/json")throw WikisourceInvalid()
            val expected=response.size(1048576);val channel=response.bodyAsChannel();val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            try {
                while(true) {currentCoroutineContext().ensureActive();val n=channel.readAvailable(buffer,0,minOf(buffer.size,1048577-out.size()));if(n<0)break;metadataPayloadBytes.addAndGet(n.toLong());if(n>1048576-out.size())throw WikisourceInvalid();budget?.consume(n);out.write(buffer,0,n)}
                if(expected!=null && expected!=out.size().toLong())throw WikisourceInvalid()
                out.toByteArray()
            } finally {channel.cancel(null)}
        }
    }
    override suspend fun search(query:String,pageToken:String?)=operation {catalog.search(query,pageToken)}
    override suspend fun discover(request:DiscoveryRequest,token:String?):DiscoveryPage {
        if(request.genre!=null || request.language!=null && request.language!=edition.language)return DiscoveryPage(emptyList())
        return operation {catalog.discover(request.query,token)}
    }
    override suspend fun getPublication(publicationId:PublicationId):Publication?=operation {catalog.record(publicationId).publication}
    override suspend fun catalogInfo(id:PublicationId):CatalogPublicationInfo=operation { resolvedInfo(id) }
    private suspend fun resolvedInfo(id:PublicationId):CatalogPublicationInfo {
        val info=catalog.info(id)
        val resource=catalog.record(id).publication.resources.singleOrNull()
        val state=resource?.let(::observation)
        return if(state!=null)info.copy(epubState=state) else info
    }
    override suspend fun refreshCatalogInfo(id:PublicationId):CatalogPublicationInfo {
        operation {catalog.record(id,fresh=true)}
        return catalogInfo(id)
    }
    private suspend fun <T> downloading(block:suspend()->T):T=try {block()}
    catch(e:CancellationException){throw e}
    catch(e:DownloadException){throw e}
    catch(e:WikiAutomaticLimit){throw e}
    catch(e:WikiBudgetReached){throw e}
    catch(e:WikisourceReviewRequired){throw DownloadException(DownloadFailure.RIGHTS_UNVERIFIED)}
    catch(e:WikiHttp){throw DownloadException(DownloadFailure.HTTP,e.status)}
    catch(e:WikisourceInvalid){throw DownloadException(DownloadFailure.INVALID)}
    catch(e:CatalogSourceException){
        val status=(e.cause as? WikiHttp)?.status ?: if(e.kind==CatalogErrorKind.HTTP_RATE_LIMITED)429 else null
        throw DownloadException(if(status!=null)DownloadFailure.HTTP else DownloadFailure.CONNECTION,status)
    }
    catch(e:Exception){throw downloadError(e,DownloadFailure.CONNECTION)}
    override suspend fun downloadOffers(id:PublicationId):List<DownloadOffer> = downloading {
        val row=operation {catalog.record(id,fresh=true)}
        if(!row.grant.authorized)throw WikisourceReviewRequired()
        if(row.publication.resources.isEmpty())throw DownloadException(DownloadFailure.UNSUPPORTED_FORMAT)
        row.publication.resources.map {DownloadOffer(row.publication,it,null)} // dynamic ZIP: unknown byte revision/size
    }
    override suspend fun openDownload(offer:DownloadOffer):ResourceContent=downloading {loadResource(offer.resource)}
    /** Pure technical validation also works with a closed source and no network. */
    suspend fun validateDocument(document:EpubDocument,resource:PublicationResource,publication:Publication) {
        validateWikisourceExport(document,resource,publication,edition)
        currentCoroutineContext().ensureActive()
        observed(resource,CatalogEpubState.VALIDATED)
    }
    override suspend fun checkEpub(id: PublicationId): CatalogPublicationInfo {
        val joinedInspection = inspectionGate.isLocked
        return inspectionGate.withLock {
            val row = operation { catalog.record(id, fresh = true) }
            val resource = row.publication.resources.singleOrNull()
            if (resource != null && row.grant.authorized &&
                !(joinedInspection && observation(resource) == CatalogEpubState.VALIDATED)) {
                inspect(row.publication, resource, automatic = false)
            }
            catalogInfo(id)
        }
    }

    override suspend fun verifyReadable(publication: Publication): CatalogReadability = inspectionGate.withLock {
        val known = cachedReadability(publication)
        if (known != CatalogReadability.READABLE_CANDIDATE) return@withLock known
        val resource = publication.resources.single()
        inspect(publication, resource, automatic = true)
    }

    private suspend fun inspect(
        publication: Publication, resource: PublicationResource, automatic: Boolean,
    ): CatalogReadability {
        val inspector = inspectExport ?: return CatalogReadability.READABLE_CANDIDATE
        val loader = object : ResourceLoader {
            override suspend fun load(resource: PublicationResource): ResourceContent {
                require(resource == publication.resources.single())
                return loadExport(resource, automatic)
            }
        }
        val state = try {
            inspector(publication, resource, loader)
            CatalogEpubState.VALIDATED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Preparers wrap transport failures. A search-specific cap says
            // nothing about compatibility with the ordinary reader.
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            if (causes.any { it is WikisourceReviewRequired || it is DownloadException && it.failure == DownloadFailure.RIGHTS_UNVERIFIED }) return CatalogReadability.UNVERIFIED
            if (causes.any { it is WikiAutomaticLimit || it is WikiBudgetReached })
                return CatalogReadability.READABLE_CANDIDATE
            val status = causes.firstNotNullOfOrNull {
                when (it) {
                    is WikiHttp -> it.status
                    is DownloadException -> it.httpStatus
                    else -> null
                }
            }
            val epub = causes.filterIsInstance<org.infinilect.app.reader.EpubException>().firstOrNull()
            when {
                status == 404 -> CatalogEpubState.UNAVAILABLE
                status != null -> CatalogEpubState.TEMPORARY_ERROR
                epub?.failure in setOf(org.infinilect.app.reader.EpubFailure.INVALID,
                    org.infinilect.app.reader.EpubFailure.LIMIT,
                    org.infinilect.app.reader.EpubFailure.ENCRYPTED) -> CatalogEpubState.INCOMPATIBLE
                else -> CatalogEpubState.TEMPORARY_ERROR
            }
        }
        currentCoroutineContext().ensureActive()
        observed(resource, state)
        return when (state) {
            CatalogEpubState.VALIDATED -> CatalogReadability.READABLE_CONFIRMED
            CatalogEpubState.TEMPORARY_ERROR -> CatalogReadability.TEMPORARY_ERROR
            else -> CatalogReadability.UNAVAILABLE
        }
    }

    private suspend fun current(resource: PublicationResource, automatic: Boolean): WikiRecord = operation {
        withAssessmentBudget(automatic) {
            val record = catalog.record(resource.publicationId, fresh = true)
            if (!record.grant.authorized || record.publication.resources.singleOrNull() != resource)
                throw WikisourceReviewRequired()
            record
        }
    }

    override suspend fun loadResource(resource: PublicationResource): ResourceContent = loadExport(resource, automatic = false)

    private suspend fun loadExport(resource: PublicationResource, automatic: Boolean): ResourceContent {
        if(resource.publicationId.sourceId!=id || wikiRevision(resource.key)==null || resource.revision!=null || resource.format!=PublicationFormat.EPUB || resource.mediaType!="application/epub+zip")throw WikisourceReviewRequired()
        val ceiling = if (automatic) AUTOMATIC_EPUB_BYTES else WIKISOURCE_BYTES
        check(!closed.get());currentCoroutineContext().ensureActive()
        val ready=CompletableDeferred<ResourceContent>()
        val producer=scope.launch {
            try {exportGate.withLock {
                val selected=current(resource, automatic)
                val url="$WIKISOURCE_EXPORT?lang=${edition.language}&page=${wikiParameter(selected.page.title.replace(' ','_'))}&format=epub-3"
                if (automatic) automaticRequests.incrementAndGet()
                client.prepareGet(url) {
                    header(HttpHeaders.UserAgent,PROJECT_USER_AGENT);header(HttpHeaders.Accept,resource.mediaType);header(HttpHeaders.AcceptEncoding,"identity")
                    timeout {requestTimeoutMillis=60000}
                }.execute {response ->
                    response.success()
                    if(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()!=resource.mediaType)throw WikisourceInvalid()
                    val expected = response.size(WIKISOURCE_BYTES)
                    // Reserve one byte to detect an oversized/chunked response.
                    if (automatic && expected != null && expected >= ceiling) throw WikiAutomaticLimit()
                    val channel=response.bodyAsChannel();val released=CompletableDeferred<Unit>();val handleClosed=AtomicBoolean()
                    val content=object:ResourceContent {
                        override val sizeBytes=expected
                        var consumed=0L;var verified=false
                        override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int=downloading {withContext(Dispatchers.IO) {
                            require(offset in 0..buffer.size && length in 0..buffer.size-offset);check(!handleClosed.get());if(length==0)return@withContext 0
                            currentCoroutineContext().ensureActive()
                            val remaining = ceiling - consumed
                            if (automatic && remaining == 0L) throw WikiAutomaticLimit()
                            val n = channel.readAvailable(buffer, offset, minOf(length.toLong(),
                                if (automatic) remaining else remaining + 1).toInt())
                            if (automatic && n > 0) automaticPayloadBytes.addAndGet(n.toLong())
                            if(n<0) {
                                if(expected!=null && consumed!=expected)throw DownloadException(DownloadFailure.INCOMPLETE)
                                if(!verified) {current(resource, automatic);verified=true}
                            } else {
                                consumed += n
                                if (automatic && consumed >= ceiling) throw WikiAutomaticLimit()
                                if(consumed>ceiling || expected!=null && consumed>expected)throw DownloadException(DownloadFailure.LIMIT)
                            }
                            n
                        }}
                        override fun close() {if(handleClosed.compareAndSet(false,true))released.complete(Unit)}
                    }
                    try {if(!ready.complete(content))content.close();withTimeout(60000){released.await()}}
                    finally {content.close();channel.cancel(null)} // IO producer, never inline UI stream cleanup
                }
            }} catch(e:Exception){ready.completeExceptionally(e)}
        }
        producer.invokeOnCompletion {error->if(!ready.isCompleted)ready.completeExceptionally(error ?: WikisourceInvalid())}
        var delivered=false
        try {val content=ready.await();currentCoroutineContext().ensureActive();delivered=true;return content}
        finally {if(!delivered)withContext(NonCancellable+Dispatchers.IO){ready.cancel();producer.cancelAndJoin()}}
    }
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        CoroutineScope(Dispatchers.IO).launch {
            try {lifetime.cancelAndJoin()} finally {try {client.close()} finally {engine.close()}}
        }.invokeOnCompletion {error->if(error==null)shutdown.complete(Unit)else shutdown.completeExceptionally(error)}
    }
    suspend fun awaitClosed(){shutdown.await()}
}
