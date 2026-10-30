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
/** Spanish reviewed subset. One metadata operation and one streamed export at most.
 * No remote publication data can configure hosts, paths, approval or redirects. */
internal class WikisourceSource(private val engine:HttpClientEngine=platformHttpEngine(),private val now:()->Long=System::currentTimeMillis,reviewed:List<WikisourceApproval> = wikisourceApprovals):
    PublicationSource,DiscoverySource,OfflineDownloadProvider,AutoCloseable {
    override val id=WIKISOURCE_ID
    override val downloadSourceId get()=id
    private val lifetime=SupervisorJob()
    private val scope=CoroutineScope(lifetime+Dispatchers.IO)
    private val closed=AtomicBoolean();private val shutdown=CompletableDeferred<Unit>()
    private val metadataGate=Mutex();private val exportGate=Mutex();private val cooldown=AtomicLong()
    private val client=HttpClient(engine) {followRedirects=false;expectSuccess=false;install(HttpTimeout) {connectTimeoutMillis=5000;requestTimeoutMillis=20000;socketTimeoutMillis=15000}}
    private val catalog=WikisourceCatalog(::bytes,reviewed)
    private fun throttle(seconds:Long=30) {cooldown.set(now()+seconds.coerceIn(30,300)*1000)}
    private suspend fun <T> operation(block:suspend()->T):T {
        check(!closed.get());currentCoroutineContext().ensureActive()
        val request=scope.async {metadataGate.withLock {
            check(!closed.get());if(now()<cooldown.get())throw WikiFailure(CatalogErrorKind.HTTP_RATE_LIMITED)
            try {block()} catch(e:CancellationException){throw e}
            catch(e:WikisourceApiBusy){throttle();throw WikiFailure(CatalogErrorKind.HTTP_RATE_LIMITED)}
            catch(e:WikisourceInvalid){throw WikiFailure(CatalogErrorKind.INVALID_JSON)}
            catch(e:WikiHttp){throw WikiFailure(when(e.status){429->CatalogErrorKind.HTTP_RATE_LIMITED;403->CatalogErrorKind.HTTP_FORBIDDEN;in 300..399->CatalogErrorKind.REDIRECT;else->CatalogErrorKind.HTTP_ERROR})}
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
        check(url.startsWith("$WIKISOURCE_API?"))
        return client.prepareGet(url) {header(HttpHeaders.UserAgent,PROJECT_USER_AGENT);header(HttpHeaders.Accept,"application/json");header(HttpHeaders.AcceptEncoding,"identity")}.execute {response ->
            response.success()
            if(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()!="application/json")throw WikisourceInvalid()
            val expected=response.size(131072);val channel=response.bodyAsChannel();val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            try {
                while(true) {currentCoroutineContext().ensureActive();val n=channel.readAvailable(buffer,0,minOf(buffer.size,131073-out.size()));if(n<0)break;if(n>131072-out.size())throw WikisourceInvalid();out.write(buffer,0,n)}
                if(expected!=null && expected!=out.size().toLong())throw WikisourceInvalid()
                out.toByteArray()
            } finally {channel.cancel(null)}
        }
    }
    override suspend fun search(query:String,pageToken:String?)=operation {catalog.search(query,pageToken)}
    override suspend fun discover(request:DiscoveryRequest,token:String?):DiscoveryPage {
        if(request.genre!=null || request.language!=null && request.language!="es")return DiscoveryPage(emptyList())
        return operation {
            val page=if(request.query in setOf("*","classics")) {if(token!=null)throw WikisourceInvalid();SearchPage(catalog.featured())}else catalog.search(request.query,token)
            DiscoveryPage(page.publications.map {DiscoveryEntry(it)},page.nextPageToken)
        }
    }
    override suspend fun getPublication(publicationId:PublicationId):Publication?=operation {catalog.publication(publicationId)}
    private suspend fun <T> downloading(block:suspend()->T):T=try {block()}
    catch(e:CancellationException){throw e}
    catch(e:DownloadException){throw e}
    catch(e:WikisourceReviewRequired){throw DownloadException(DownloadFailure.RIGHTS_UNVERIFIED)}
    catch(e:WikiHttp){throw DownloadException(DownloadFailure.HTTP,e.status)}
    catch(e:WikisourceInvalid){throw DownloadException(DownloadFailure.INVALID)}
    catch(e:CatalogSourceException){throw DownloadException(if(e.kind==CatalogErrorKind.HTTP_RATE_LIMITED)DownloadFailure.HTTP else DownloadFailure.CONNECTION,if(e.kind==CatalogErrorKind.HTTP_RATE_LIMITED)429 else null)}
    catch(e:Exception){throw downloadError(e,DownloadFailure.CONNECTION)}
    override suspend fun downloadOffers(id:PublicationId):List<DownloadOffer> = downloading {
        val publication=getPublication(id) ?: throw DownloadException(DownloadFailure.MISSING)
        publication.resources.map {DownloadOffer(publication,it,null)} // dynamically generated: no invented size/revision
    }
    override suspend fun openDownload(offer:DownloadOffer):ResourceContent=downloading {loadResource(offer.resource)}
    /** Invoked by the real preparer before publication for both online and offline opens. */
    suspend fun validateDocument(document:EpubDocument,resource:PublicationResource) {
        try {catalog.verify(document,resource)} catch(e:WikisourceReviewRequired) {
            throw org.infinilect.app.reader.EpubException(org.infinilect.app.reader.EpubFailure.INVALID,e)
        }
    }
    override suspend fun loadResource(resource:PublicationResource):ResourceContent {
        val approval=catalog.approval(resource.publicationId)
        if(resource.key!=approval.resourceKey || resource.revision!=null || resource.format!=PublicationFormat.EPUB || resource.mediaType!="application/epub+zip")throw WikisourceReviewRequired()
        check(!closed.get());currentCoroutineContext().ensureActive()
        val ready=CompletableDeferred<ResourceContent>()
        val producer=scope.launch {
            try {exportGate.withLock {
                operation {catalog.publication(resource.publicationId)}
                val url="$WIKISOURCE_EXPORT?lang=es&page=${wikiParameter(approval.exportTitle)}&format=epub-3"
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
                                if(!verified) {operation {catalog.publication(resource.publicationId)};verified=true}
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
