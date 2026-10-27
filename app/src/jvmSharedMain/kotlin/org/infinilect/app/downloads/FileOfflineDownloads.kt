// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.downloads

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.core.*

internal const val OFFLINE_DIRECTORY="offline-downloads-v1"
internal const val DOWNLOAD_FILE_LIMIT=64L*1024*1024
internal val OFFLINE_QUOTAS=setOf(128L,512L,1024L,2048L).map {it*1024*1024}.toSet()
private val digestName=Regex("[0-9a-f]{64}")
private fun ByteArray.hex()=joinToString("") {"%02x".format(it)}
internal fun offlineKey(id: PublicationId): String {
    val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use {it.writeUTF(id.sourceId.value);it.writeUTF(id.localId)}
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).hex()
}
private fun fail(f: DownloadFailure): Nothing=throw DownloadException(f)

/** A new independent private store, not a second Library or an import source. One durable
 * metadata record per original publication identity; original resource key/revision survive.
 * Completion publishes payload first, then an atomic checksummed record. Recovery never
 * promotes an interrupted transfer merely because a partial/complete-looking file exists. */
internal class FileOfflineDownloads(private val directory: Path?,providers: List<OfflineDownloadProvider>,
    private val validate: suspend (DownloadOffer,ResourceLoader)->Unit,
    private val io: CoroutineDispatcher=Dispatchers.IO,private val clock: ()->Long=System::currentTimeMillis,
    private val freeSpace: (Path)->Long={Files.getFileStore(it).usableSpace}) : DownloadBackend {
    private val providers=providers.associateBy {it.downloadSourceId}
    private val mutex=Mutex()
    private val rows=linkedMapOf<PublicationId,DownloadEntry>()
    private val leases=ConcurrentHashMap<PublicationId,AtomicInteger>()
    private val jobs=ConcurrentHashMap.newKeySet<Job>()
    private val contents=ConcurrentHashMap.newKeySet<ResourceContent>()
    private val closed=AtomicBoolean()
    private var root: Path?=null
    private var owner: FileChannel?=null
    private var ownerLock: FileLock?=null
    private var initialized=false
    private var damaged=false
    private var revision=0L
    private var quota=512L*1024*1024
    private val active=mutableSetOf<PublicationId>()
    init {require(providers.size==this.providers.size)}
    override fun capability(id: PublicationId)=when {
        id.sourceId.value=="local-imports" -> DownloadCapability.IMPORTED
        id.sourceId in providers -> DownloadCapability.AUTHORIZED_SOURCE
        else -> DownloadCapability.UNSUPPORTED
    }
    private fun checkOpen() {if(closed.get()) throw CancellationException()}
    private fun base(): Path {
        checkOpen();root?.let {return it}
        val p=directory?.takeIf {it.isAbsolute} ?: fail(DownloadFailure.STORAGE)
        if(Files.isSymbolicLink(p)) fail(DownloadFailure.STORAGE)
        Files.createDirectories(p)
        if(!Files.isDirectory(p,NOFOLLOW_LINKS)) fail(DownloadFailure.STORAGE)
        Files.getFileAttributeView(p,PosixFileAttributeView::class.java,NOFOLLOW_LINKS)?.setPermissions(PosixFilePermissions.fromString("rwx------"))
        val channel=FileChannel.open(p.resolve("owner.lock"),CREATE,WRITE,NOFOLLOW_LINKS)
        val lock=try {channel.tryLock() ?: fail(DownloadFailure.BUSY)} catch(e: Exception) {channel.close();if(e is OverlappingFileLockException) fail(DownloadFailure.BUSY);throw e}
        owner=channel;ownerLock=lock;root=p;return p
    }
    private suspend fun <T> locked(block: suspend ()->T): T = withContext(io) {
        try {mutex.withLock {checkOpen();block()}}
        catch(e: CancellationException) {throw e} catch(e: DownloadException) {throw e}
        catch(_: Exception) {fail(DownloadFailure.STORAGE)}
    }
    private fun path(id: PublicationId,suffix: String)=base().resolve(offlineKey(id)+suffix)
    private fun children(): List<Path> {
        val result=mutableListOf<Path>()
        Files.newDirectoryStream(base()).use {stream -> for(p in stream) {if(result.size>=1030) fail(DownloadFailure.LIMIT);result.add(p)}}
        return result
    }
    private fun used()=children().filter {it.fileName.toString().endsWith(".payload") && digestName.matches(it.fileName.toString().removeSuffix(".payload"))}
        .sumOf {if(Files.isRegularFile(it,NOFOLLOW_LINKS)) Files.size(it) else 0L}
    private fun atomic(file: Path,bytes: ByteArray) {
        val part=file.resolveSibling(file.fileName.toString()+".tmp")
        try {
            Files.deleteIfExists(part)
            FileChannel.open(part,CREATE_NEW,WRITE,NOFOLLOW_LINKS).use {out -> val b=ByteBuffer.wrap(bytes);while(b.hasRemaining()) out.write(b);out.force(true)}
            Files.move(part,file,ATOMIC_MOVE,REPLACE_EXISTING)
        } finally {Files.deleteIfExists(part)}
    }
    private fun save(row: DownloadEntry): DownloadEntry {
        val updated=row.copy(revision=++revision)
        atomic(path(row.id,".record"),encodeDownload(updated));rows[row.id]=updated;return updated
    }
    private suspend fun initialize() {
        if(initialized) return
        base()
        val all=children()
        val quotaFile=base().resolve("quota")
        if(Files.exists(quotaFile,NOFOLLOW_LINKS)) {
            val raw=bounded(quotaFile,8)
            if(raw.size!=8) fail(DownloadFailure.STORAGE)
            quota=ByteBuffer.wrap(raw).long.takeIf {it in OFFLINE_QUOTAS} ?: fail(DownloadFailure.STORAGE)
        }
        for(p in all) {
            val name=p.fileName.toString()
            if(name.endsWith(".record") && digestName.matches(name.removeSuffix(".record"))) {
                if(rows.size>=256) fail(DownloadFailure.LIMIT)
                val row=try {decodeDownload(bounded(p,131072))} catch(_: Exception) {damaged=true;null} ?: continue
                if(offlineKey(row.id)!=name.removeSuffix(".record")) {damaged=true;continue}
                rows[row.id]=row;revision=maxOf(revision,row.revision)
            }
        }
        for(row in rows.values.toList()) {
            val payload=path(row.id,".payload")
            when {
                row.status in setOf(DownloadStatus.PENDING,DownloadStatus.DOWNLOADING) -> {
                    Files.deleteIfExists(payload);save(row.copy(status=DownloadStatus.CANCELLED,failure=DownloadFailure.INTERRUPTED,completedAt=null,sha256=null))
                }
                row.status==DownloadStatus.DOWNLOADED && (!Files.isRegularFile(payload,NOFOLLOW_LINKS) || Files.size(payload)!=row.size) -> save(row.copy(status=DownloadStatus.ERROR,failure=DownloadFailure.MISSING))
            }
        }
        for(p in all) {
            val name=p.fileName.toString()
            if((name.endsWith(".part") && digestName.matches(name.removeSuffix(".part"))) || (name.endsWith(".record.tmp") && digestName.matches(name.removeSuffix(".record.tmp"))) || name=="quota.tmp") Files.deleteIfExists(p)
        }
        initialized=true
    }
    override suspend fun load(): DownloadSnapshot=locked {initialize();snapshotLocked()}
    private fun snapshotLocked(): DownloadSnapshot {
        for(row in rows.values.toList()) if(row.status==DownloadStatus.DOWNLOADED) {
            val p=path(row.id,".payload")
            if(!Files.isRegularFile(p,NOFOLLOW_LINKS) || Files.size(p)!=row.size) save(row.copy(status=DownloadStatus.ERROR,failure=DownloadFailure.MISSING))
        }
        return DownloadSnapshot(rows.values.sortedWith(compareBy({it.publication.title},{it.id.sourceId.value},{it.id.localId})),used(),quota,damaged,revision)
    }
    override suspend fun snapshot(): DownloadSnapshot=locked {initialize();snapshotLocked()}
    override suspend fun offers(id: PublicationId): List<DownloadOffer> {
        if(capability(id)!=DownloadCapability.AUTHORIZED_SOURCE) fail(DownloadFailure.NOT_ALLOWED)
        val offers=try {providers.getValue(id.sourceId).downloadOffers(id)} catch(e: CancellationException) {throw e} catch(_: Exception) {fail(DownloadFailure.CONNECTION)}
        if(offers.size>32) fail(DownloadFailure.LIMIT)
        return offers.filter {it.publication.id==id && compatible(it)}
    }
    private fun compatible(offer: DownloadOffer): Boolean {
        val r=offer.resource
        val type=when(r.format) {PublicationFormat.TEXT->"text/plain";PublicationFormat.PDF->"application/pdf";PublicationFormat.EPUB->"application/epub+zip";PublicationFormat.CBZ->"application/vnd.comicbook+zip";else->return false}
        return r.publicationId==offer.publication.id && r in offer.publication.resources && r.mediaType==type && offer.size?.let {it !in 1..limit(r.format)}!=true
    }
    private fun limit(format: PublicationFormat)=when(format) {PublicationFormat.TEXT->16L*1024*1024;PublicationFormat.PDF->PdfLimits.SOURCE_BYTES;else->DOWNLOAD_FILE_LIMIT}
    override suspend fun pending(offer: DownloadOffer): DownloadEntry=locked {
        initialize()
        if(!compatible(offer)) fail(DownloadFailure.NOT_ALLOWED)
        if(capability(offer.publication.id)!=DownloadCapability.AUTHORIZED_SOURCE || offer.resource.publicationId!=offer.publication.id) fail(DownloadFailure.NOT_ALLOWED)
        if(rows.size>=256 && offer.publication.id !in rows) fail(DownloadFailure.LIMIT)
        if(rows[offer.publication.id]?.status in setOf(DownloadStatus.PENDING,DownloadStatus.DOWNLOADING,DownloadStatus.DOWNLOADED)) fail(DownloadFailure.BUSY)
        if((leases[offer.publication.id]?.get() ?: 0)>0) fail(DownloadFailure.BUSY)
        Files.deleteIfExists(path(offer.publication.id,".payload")) // Explicit retry only, never automatic eviction.
        save(DownloadEntry(offer.publication.copy(resources=listOf(offer.resource)),offer.resource.format,DownloadStatus.PENDING,offer.resource,offer.size))
    }
    override suspend fun download(entry: DownloadEntry,started: suspend (DownloadEntry)->Unit,progress: suspend (DownloadProgress)->Unit): DownloadEntry {
        val job=currentCoroutineContext().job;jobs+=job
        var content: ResourceContent?=null
        try {return withContext(io) {
            val offer=offers(entry.id).firstOrNull {it.resource==entry.resource} ?: fail(DownloadFailure.NOT_ALLOWED)
            val cap=locked {
                initialize();if(active.isNotEmpty() || rows[entry.id]?.status!=DownloadStatus.PENDING) fail(DownloadFailure.BUSY)
                val remaining=quota-used()
                val maximum=minOf(limit(entry.format),remaining)
                if(maximum<=0 || offer.size?.let {it>maximum}==true) fail(DownloadFailure.LIMIT)
                if(freeSpace(base())<3*(offer.size ?: maximum)+8*1024*1024) fail(DownloadFailure.SPACE)
                active+=entry.id
                started(save(entry.copy(publication=offer.publication.copy(resources=listOf(offer.resource)),resource=offer.resource,size=offer.size,status=DownloadStatus.DOWNLOADING)))
                maximum
            }
            val part=path(entry.id,".part");val hash=MessageDigest.getInstance("SHA-256");var total=0L;var last=0L
            val opened=try {providers.getValue(entry.id.sourceId).openDownload(offer)} catch(e: CancellationException) {throw e} catch(_: Exception) {fail(DownloadFailure.CONNECTION)}
            content=opened;contents+=opened
            val declared=opened.sizeBytes
            if(declared?.let {it !in 1..cap || offer.size!=null && it!=offer.size}==true) fail(DownloadFailure.INCOMPLETE)
            Files.deleteIfExists(part)
            FileChannel.open(part,CREATE_NEW,WRITE,NOFOLLOW_LINKS).use {out ->
                val b=ByteArray(32768)
                while(true) {
                    checkOpen();ensureActive()
                    val requested=minOf(b.size.toLong(),cap-total+1).toInt()
                    val n=try {opened.read(b,0,requested)} catch(e: CancellationException) {throw e} catch(_: Exception) {fail(DownloadFailure.CONNECTION)}
                    if(n==-1) break
                    if(n !in 1..requested) fail(DownloadFailure.INCOMPLETE)
                    total+=n;if(total>cap) fail(DownloadFailure.LIMIT)
                    if(declared!=null && total>declared || offer.size!=null && total>offer.size) fail(DownloadFailure.INCOMPLETE)
                    hash.update(b,0,n);val bytes=ByteBuffer.wrap(b,0,n);while(bytes.hasRemaining()) out.write(bytes)
                    val now=clock();if(now-last>=100) {progress(DownloadProgress(total,offer.size ?: declared));last=now}
                };out.force(true)
            }
            if(total==0L || declared!=null && declared!=total || offer.size!=null && offer.size!=total) fail(DownloadFailure.INCOMPLETE)
            opened.close();contents-=opened;content=null
            val digest=hash.digest().hex()
            val local=localLoader(part,total,offer.resource)
            try {validate(offer,local)} catch(e: CancellationException) {throw e} catch(e: DownloadException) {throw e} catch(_: Exception) {fail(DownloadFailure.INVALID)}
            ensureActive();checkOpen()
            locked {
                if(rows[entry.id]?.status!=DownloadStatus.DOWNLOADING) fail(DownloadFailure.BUSY)
                Files.move(part,path(entry.id,".payload"),ATOMIC_MOVE,REPLACE_EXISTING)
                val completed=save(rows.getValue(entry.id).copy(status=DownloadStatus.DOWNLOADED,size=total,sha256=digest,completedAt=clock(),failure=null))
                progress(DownloadProgress(total,total));completed
            }
        }} catch(e: CancellationException) {throw e} catch(e: DownloadException) {throw e} catch(_: Exception) {fail(DownloadFailure.STORAGE)}
        finally {
            content?.let {contents-=it;try {it.close()} catch(_: Exception) {}}
            withContext(NonCancellable+io) {mutex.withLock {active-=entry.id;root?.resolve(offlineKey(entry.id)+".part")?.let {try {Files.deleteIfExists(it)} catch(_: IOException) {}}}}
            jobs-=job
        }
    }
    override suspend fun settle(id: PublicationId,status: DownloadStatus,failure: DownloadFailure?): DownloadEntry?=locked {
        initialize();val row=rows[id] ?: return@locked null
        if(row.status==DownloadStatus.DOWNLOADED) return@locked row // Durable completion won the cancellation race.
        Files.deleteIfExists(path(id,".part"));Files.deleteIfExists(path(id,".payload"))
        save(row.copy(status=status,failure=failure,sha256=null,completedAt=null))
    }
    override suspend fun remove(id: PublicationId)=locked {
        initialize();if(id in active || (leases[id]?.get() ?: 0)>0) fail(DownloadFailure.BUSY)
        // Remove authorization marker first; interruption can leave an orphan, never a false ready file.
        Files.deleteIfExists(path(id,".record"));rows.remove(id);revision++
        Files.deleteIfExists(path(id,".payload"));Files.deleteIfExists(path(id,".part"));Unit
    }
    override suspend fun quota(bytes: Long)=locked {
        initialize();if(bytes !in OFFLINE_QUOTAS || bytes<used() || active.isNotEmpty()) fail(DownloadFailure.LIMIT)
        atomic(base().resolve("quota"),ByteBuffer.allocate(8).putLong(bytes).array());quota=bytes;revision++;Unit
    }
    private fun localLoader(file: Path,size: Long,expected: PublicationResource)=object: ResourceLoader {
        override suspend fun load(resource: PublicationResource): ResourceContent {
            if(resource!=expected) fail(DownloadFailure.INVALID)
            var opened: ResourceContent?=null
            try {return withContext(io) {fileContent(file,size).also {opened=it}}}
            catch(e: Throwable) {opened?.close();throw e}
        }
    }
    override suspend fun acquire(id: PublicationId): OfflineReadingCopy? {
        if(directory==null || capability(id)!=DownloadCapability.AUTHORIZED_SOURCE) return null
        var acquired: OfflineReadingCopy?=null
        try {return locked {
            initialize();val row=rows[id]?.takeIf {it.status==DownloadStatus.DOWNLOADED} ?: return@locked null
            val payload=path(id,".payload")
            if(!Files.isRegularFile(payload,NOFOLLOW_LINKS) || Files.size(payload)!=row.size || hashFile(payload)!=row.sha256) {
                save(row.copy(status=DownloadStatus.ERROR,failure=DownloadFailure.MISSING));fail(DownloadFailure.MISSING)
            }
            val count=leases.getOrPut(id) {AtomicInteger()};count.incrementAndGet()
            val done=AtomicBoolean();val handles=ConcurrentHashMap.newKeySet<ResourceContent>()
            object: OfflineReadingCopy {
                override val publication=row.publication
                override val loader=object: ResourceLoader {
                    override suspend fun load(resource: PublicationResource): ResourceContent=withContext(io) {
                        if(done.get() || resource!=row.resource) fail(DownloadFailure.MISSING)
                        val handle=fileContent(payload,checkNotNull(row.size));handles+=handle
                        if(done.get()) {handle.close();fail(DownloadFailure.MISSING)}
                        handle
                    }
                }
                override fun close() {if(done.compareAndSet(false,true)) {try {handles.forEach {it.close()}} finally {count.decrementAndGet();releaseOwnerIfUnused()}}}
            }.also {acquired=it}
        }} catch(e: Throwable) {acquired?.close();throw e}
    }
    private suspend fun hashFile(file: Path): String {
        val digest=MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file,NOFOLLOW_LINKS).use {input -> val b=ByteArray(32768);while(true) {currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0) break;digest.update(b,0,n)}}
        return digest.digest().hex()
    }
    override suspend fun close() {
        if(!closed.compareAndSet(false,true)) return
        contents.forEach {try {it.close()} catch(_: Exception) {}}
        buildList {for(job in jobs) add(job)}.forEach {it.cancelAndJoin()}
        withContext(io) {mutex.withLock {releaseOwnerIfUnused()}}
    }
    @Synchronized private fun releaseOwnerIfUnused() {
        if(!closed.get() || leases.values.any {it.get()>0}) return
        try {ownerLock?.release()} catch(_: IOException) {} finally {ownerLock=null;try {owner?.close()} catch(_: IOException) {};owner=null}
    }
    private fun fileContent(file: Path,size: Long): ResourceContent {
        if(!Files.isRegularFile(file,NOFOLLOW_LINKS) || Files.size(file)!=size) fail(DownloadFailure.MISSING)
        val channel=FileChannel.open(file,READ,NOFOLLOW_LINKS);val done=AtomicBoolean()
        return object: ResourceContent {
            override val sizeBytes=size
            override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int=withContext(io) {
                require(offset in 0..buffer.size && length in 0..(buffer.size-offset));check(!done.get());if(length==0) 0 else channel.read(ByteBuffer.wrap(buffer,offset,length))
            }
            override fun close() {if(done.compareAndSet(false,true)) try {channel.close()} catch(_: IOException) {}}
        }
    }
    private fun bounded(file: Path,maximum: Int): ByteArray {
        if(!Files.isRegularFile(file,NOFOLLOW_LINKS) || Files.size(file)>maximum) fail(DownloadFailure.INVALID)
        FileChannel.open(file,READ,NOFOLLOW_LINKS).use {ch -> val b=ByteBuffer.allocate(ch.size().toInt());while(b.hasRemaining()) {if(ch.read(b)<0) fail(DownloadFailure.INVALID)};return b.array()}
    }
}

private fun encodeDownload(row: DownloadEntry): ByteArray {
    val bytes=ByteArrayOutputStream()
    DataOutputStream(bytes).use {out ->
        val p=row.publication;val r=row.resource ?: fail(DownloadFailure.INVALID)
        if(p.id.sourceId.value.length>128 || p.id.localId.length>512 || p.title.length>512 || p.authors.size>64 || p.languages.size>32 || r.key.length>512 || (p.rights?.length ?: 0)>16384 || (p.sourceUrl?.length ?: 0)>2048) fail(DownloadFailure.LIMIT)
        out.writeInt(0x494e4431);out.writeUTF(p.id.sourceId.value);out.writeUTF(p.id.localId);out.writeUTF(p.title);out.writeUTF(p.type.name)
        out.writeInt(p.authors.size);p.authors.forEach {if(it.length>512) fail(DownloadFailure.LIMIT);out.writeUTF(it)}
        out.writeInt(p.languages.size);p.languages.forEach {if(it.length>128) fail(DownloadFailure.LIMIT);out.writeUTF(it)}
        out.writeUTF(p.sourceUrl.orEmpty());out.writeUTF(p.rights.orEmpty());out.writeUTF(row.format.name);out.writeUTF(row.status.name)
        out.writeUTF(r.key);out.writeUTF(r.mediaType);out.writeUTF(r.revision.orEmpty());out.writeLong(row.size ?: -1);out.writeUTF(row.sha256.orEmpty());out.writeLong(row.completedAt ?: -1);out.writeUTF(row.failure?.name.orEmpty());out.writeLong(row.revision)
    }
    val data=bytes.toByteArray();if(data.size>131040) fail(DownloadFailure.LIMIT)
    return data+MessageDigest.getInstance("SHA-256").digest(data)
}
private fun decodeDownload(bytes: ByteArray): DownloadEntry {
    if(bytes.size<36) fail(DownloadFailure.INVALID)
    val payload=bytes.copyOf(bytes.size-32)
    if(!MessageDigest.isEqual(bytes.takeLast(32).toByteArray(),MessageDigest.getInstance("SHA-256").digest(payload))) fail(DownloadFailure.INVALID)
    return DataInputStream(ByteArrayInputStream(payload)).use {input ->
        if(input.readInt()!=0x494e4431) fail(DownloadFailure.INVALID)
        val id=PublicationId(SourceId(input.readUTF()),input.readUTF());val title=input.readUTF();val type=PublicationType.valueOf(input.readUTF())
        fun list(max: Int): List<String> {val n=input.readInt();if(n !in 0..max) fail(DownloadFailure.INVALID);return List(n) {input.readUTF()}}
        val authors=list(64);val languages=list(32);val url=input.readUTF().ifEmpty {null};val rights=input.readUTF().ifEmpty {null}
        val format=PublicationFormat.valueOf(input.readUTF());val status=DownloadStatus.valueOf(input.readUTF())
        if(format !in setOf(PublicationFormat.TEXT,PublicationFormat.PDF,PublicationFormat.EPUB,PublicationFormat.CBZ)) fail(DownloadFailure.INVALID)
        val resource=PublicationResource(id,input.readUTF(),format,input.readUTF(),input.readUTF().ifEmpty {null})
        val size=input.readLong().takeIf {it!=-1L};val hash=input.readUTF().ifEmpty {null};val time=input.readLong().takeIf {it!=-1L};val error=input.readUTF().ifEmpty {null}?.let(DownloadFailure::valueOf);val rev=input.readLong()
        if(input.available()!=0 || rev<0 || size?.let {it !in 1..DOWNLOAD_FILE_LIMIT}==true || status==DownloadStatus.DOWNLOADED && (hash==null || !digestName.matches(hash) || time==null || time<0 || size==null)) fail(DownloadFailure.INVALID)
        DownloadEntry(Publication(id,title,type,authors,listOf(resource),languages,url,rights),format,status,resource,size,hash,time,error,rev).also {encodeDownload(it)}
    }
}
