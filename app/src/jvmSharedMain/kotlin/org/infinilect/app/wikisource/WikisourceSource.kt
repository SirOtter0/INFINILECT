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

private class WikiHttp(val status:Int):Exception()
private class WikiFailure(kind:CatalogErrorKind,cause:Throwable?=null):CatalogSourceException(kind,cause) {
    override val message get()=catalogFailureMessage("Wikisource",kind)
}
/** Shared under-demand Wikisource adapter (one internally configured project). One metadata operation and one streamed export at most.
 * No remote publication data can configure hosts, paths, approval or redirects. */
internal class WikisourceSource(val edition:WikisourceEdition=WikisourceEdition.ES,private val engine:HttpClientEngine=platformHttpEngine(),private val now:()->Long=System::currentTimeMillis):
    PublicationSource,DiscoverySource,OfflineDownloadProvider,CatalogPublicationSource,AutoCloseable {
    override val id=edition.id
    override val downloadSourceId get()=id
    private val lifetime=SupervisorJob()
    private val scope=CoroutineScope(lifetime+Dispatchers.IO)
    private val closed=AtomicBoolean();private val shutdown=CompletableDeferred<Unit>()
    private val metadataGate=Mutex();private val exportGate=Mutex();private val cooldown=AtomicLong()
    private val client=HttpClient(engine) {followRedirects=false;expectSuccess=false;install(HttpTimeout) {connectTimeoutMillis=5000;requestTimeoutMillis=20000;socketTimeoutMillis=15000}}
    private val rights=WikisourceRights(::bytes)
    private val catalog=WikisourceCatalog(edition,::bytes,now,rights::assess)
    private val validated=linkedMapOf<Pair<PublicationId,String>,Unit>()
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
        return client.prepareGet(url) {header(HttpHeaders.UserAgent,PROJECT_USER_AGENT);header(HttpHeaders.Accept,"application/json");header(HttpHeaders.AcceptEncoding,"identity")}.execute {response ->
            response.success()
            if(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()!="application/json")throw WikisourceInvalid()
            val expected=response.size(1048576);val channel=response.bodyAsChannel();val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            try {
                while(true) {currentCoroutineContext().ensureActive();val n=channel.readAvailable(buffer,0,minOf(buffer.size,1048577-out.size()));if(n<0)break;if(n>1048576-out.size())throw WikisourceInvalid();out.write(buffer,0,n)}
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
    override suspend fun catalogInfo(id:PublicationId):CatalogPublicationInfo=operation {
        val info=catalog.info(id)
        val resource=catalog.record(id).publication.resources.singleOrNull()
        if(resource!=null && synchronized(validated){validated.containsKey(id to resource.key)})info.copy(epubState=CatalogEpubState.VALIDATED) else info
    }
    private suspend fun <T> downloading(block:suspend()->T):T=try {block()}
    catch(e:CancellationException){throw e}
    catch(e:DownloadException){throw e}
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
        synchronized(validated) {
            validated[publication.id to resource.key]=Unit
            while(validated.size>32)validated.remove(validated.keys.first())
        }
    }
    private suspend fun current(resource:PublicationResource):WikiRecord=operation {
        val record=catalog.record(resource.publicationId,fresh=true)
        if(!record.grant.authorized || record.publication.resources.singleOrNull()!=resource)throw WikisourceReviewRequired()
        record
    }
    override suspend fun loadResource(resource:PublicationResource):ResourceContent {
        if(resource.publicationId.sourceId!=id || wikiRevision(resource.key)==null || resource.revision!=null || resource.format!=PublicationFormat.EPUB || resource.mediaType!="application/epub+zip")throw WikisourceReviewRequired()
        check(!closed.get());currentCoroutineContext().ensureActive()
        val ready=CompletableDeferred<ResourceContent>()
        val producer=scope.launch {
            try {exportGate.withLock {
                val selected=current(resource)
                val url="$WIKISOURCE_EXPORT?lang=${edition.language}&page=${wikiParameter(selected.page.title.replace(' ','_'))}&format=epub-3"
                client.prepareGet(url) {
                    header(HttpHeaders.UserAgent,PROJECT_USER_AGENT);header(HttpHeaders.Accept,resource.mediaType);header(HttpHeaders.AcceptEncoding,"identity")
                    timeout {requestTimeoutMillis=60000}
                }.execute {response ->
                    response.success()
                    if(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()!=resource.mediaType)throw WikisourceInvalid()
                    val expected=response.size(WIKISOURCE_BYTES);val channel=response.bodyAsChannel();val released=CompletableDeferred<Unit>();val handleClosed=AtomicBoolean()
                    val content=object:ResourceContent {
                        override val sizeBytes=expected
                        var consumed=0L;var verified=false
                        override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int=downloading {withContext(Dispatchers.IO) {
                            require(offset in 0..buffer.size && length in 0..buffer.size-offset);check(!handleClosed.get());if(length==0)return@withContext 0
                            currentCoroutineContext().ensureActive()
                            val n=channel.readAvailable(buffer,offset,minOf(length.toLong(),WIKISOURCE_BYTES-consumed+1).toInt())
                            if(n<0) {
                                if(expected!=null && consumed!=expected)throw DownloadException(DownloadFailure.INCOMPLETE)
                                if(!verified) {current(resource);verified=true}
                            } else {consumed+=n;if(consumed>WIKISOURCE_BYTES || expected!=null && consumed>expected)throw DownloadException(DownloadFailure.LIMIT)}
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
