// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.app.progress.privateProgressPermissions
import org.infinilect.core.*

internal const val PDF_PREPARATION_DIRECTORY = "pdf-preparation-v1"

/** Concurrent owner sets have no atomic size/iterator pair. Traverse with hasNext,
 * never Collection.toList's size=1 shortcut, while keeping callbacks outside traversal. */
internal fun <T> pdfOwnerSnapshot(owners: Collection<T>): List<T> = buildList {
    for (owner in owners) add(owner)
}

/** Infrastructure-only synchronous seam, also used for ownership/cancellation host tests. */
internal fun interface PdfEngine { fun open(path: Path): PdfEngineDocument }
internal interface PdfEngineDocument {
    val pages: List<PdfPageGeometry>
    fun render(index: Int, size: PdfRenderSize): PdfRaster
    fun close()
}
internal expect fun platformPdfEngine(): PdfEngine

// Also bounds overlapping abandoned operations across reader replacement. Never a timeout.
private val pdfEngineLock = Mutex()

/** Fully reads the loader (including EOF integrity checks) before any parser sees a file.
 * Private seekable spool is disposable; durable imports remain in their existing store.
 * Two live documents at most (import inspection + active reader); one engine operation.
 */
internal class FilePdfPreparer(
    private val directory: Path?,
    private val engine: PdfEngine = platformPdfEngine(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PdfPreparer {
    private val closed = AtomicBoolean()
    private val documents = ConcurrentHashMap.newKeySet<OwnedDocument>()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    private val cleanup = CoroutineScope(SupervisorJob() + io)
    private var root: Path? = null
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var shutdown: Job? = null

    override suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader): PdfDocument {
        require(resource.publicationId == publication.id && resource.format == PublicationFormat.PDF)
        var acquired: OwnedDocument? = null
        try {
            return withContext(io) {
                val job = currentCoroutineContext().job
                jobs.add(job)
                try { pdfEngineLock.withLock {
                    ensureOpen()
                    if (documents.size >= 2) throw PdfException(PdfFailure.LIMIT)
                    val base = initialize()
                    var temporary: Path? = Files.createTempFile(base,"pdf-",".part")
                    var native: PdfEngineDocument? = null
                    try {
                        privateProgressPermissions(temporary!!,false)
                        val content = loader.load(resource)
                        try {
                            val expected = content.sizeBytes
                            if (expected != null && expected !in 1..PdfLimits.SOURCE_BYTES) throw PdfException(PdfFailure.LIMIT)
                            var total = 0L
                            Files.newOutputStream(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    ensureOpen()
                                    val requested = minOf(buffer.size.toLong(),PdfLimits.SOURCE_BYTES-total+1).toInt()
                                    val n = try { content.read(buffer,0,requested) }
                                    catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { throw PdfException(PdfFailure.TRANSFER) }
                                    if (n == -1) break
                                    if (n !in 1..requested) throw PdfException(PdfFailure.TRANSFER)
                                    total += n
                                    if (total > PdfLimits.SOURCE_BYTES) throw PdfException(PdfFailure.LIMIT)
                                    if (expected != null && total > expected) throw PdfException(PdfFailure.TRANSFER)
                                    output.write(buffer,0,n)
                                }
                            }
                            if (total == 0L || expected != null && total != expected) throw PdfException(PdfFailure.TRANSFER)
                        } finally { content.close() }
                        ensureOpen()
                        native = engine.open(temporary!!)
                        PdfLimits.pageCount(native.pages.size)
                        native.pages.forEach { PdfLimits.geometry(it.widthPoints,it.heightPoints) }
                        ensureOpen()
                        val document = OwnedDocument(native,temporary!!,ReadingProgressId(publication.id,resource.key,PublicationFormat.PDF))
                        acquired = document
                        documents.add(document)
                        native = null; temporary = null
                        ensureOpen()
                        document
                    } finally {
                        try { native?.close() } finally { temporary?.let { Files.deleteIfExists(it) } }
                    }
                } } finally { jobs.remove(job) }
            }
        } catch (error: Throwable) {
            // Prompt cancellation at withContext return must not discard an open document.
            acquired?.close()
            when (error) {
                is CancellationException -> throw error
                is PdfException -> throw error
                is Error -> throw error
                else -> throw PdfException(PdfFailure.STORAGE)
            }
        }
    }

    private suspend fun ensureOpen() {
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw PdfException(PdfFailure.CLOSED)
    }
    private fun initialize(): Path {
        root?.let { return it }
        val base = directory?.takeIf { it.isAbsolute } ?: throw PdfException(PdfFailure.STORAGE)
        if (Files.isSymbolicLink(base)) throw PdfException(PdfFailure.STORAGE)
        Files.createDirectories(base); privateProgressPermissions(base,true)
        if (!Files.isDirectory(base,LinkOption.NOFOLLOW_LINKS)) throw PdfException(PdfFailure.STORAGE)
        val owner = base.resolve(".owner.lock")
        if (Files.isSymbolicLink(owner)) throw PdfException(PdfFailure.STORAGE)
        val opened = FileChannel.open(owner,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)
        val lease = try { opened.tryLock() ?: throw PdfException(PdfFailure.STORAGE) }
        catch (error: Throwable) { opened.close(); throw error }
        channel = opened; lock = lease
        try {
            privateProgressPermissions(owner,false)
            // Only exact generated names; no durable content is stored in this namespace.
            Files.newDirectoryStream(base).use { entries ->
                var count = 0
                for (path in entries) {
                    if (++count > 256) throw PdfException(PdfFailure.STORAGE)
                    if (Regex("pdf-[0-9]+\\.part").matches(path.fileName.toString())) Files.deleteIfExists(path)
                }
            }
        } catch (error: Throwable) {
            try { lease.close() } finally { opened.close();channel=null;lock=null }
            throw error
        }
        root = base
        return base
    }

    private inner class OwnedDocument(
        private val native: PdfEngineDocument,
        private val path: Path,
        override val progressId: ReadingProgressId,
    ) : PdfDocument {
        override val pages = native.pages.toList()
        private val retired = AtomicBoolean()
        override suspend fun renderPage(pageIndex: Int, size: PdfRenderSize): PdfRaster {
            if (pageIndex !in pages.indices) throw PdfException(PdfFailure.PAGE_INDEX)
            PdfLimits.output(size.width,size.height)
            var acquired: PdfRaster? = null
            try {
                return withContext(io) { pdfEngineLock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (closed.get() || retired.get()) throw PdfException(PdfFailure.CLOSED)
                    val raster = native.render(pageIndex,size)
                    acquired = raster
                    currentCoroutineContext().ensureActive()
                    if (closed.get() || retired.get()) throw PdfException(PdfFailure.CLOSED)
                    if (raster.size.width > size.width || raster.size.height > size.height) throw PdfException(PdfFailure.RENDER)
                    raster
                } }
            } catch (error: Throwable) {
                acquired?.close()
                when (error) {
                    is CancellationException -> throw error
                    is PdfException -> throw error
                    is Error -> throw error
                    else -> throw PdfException(PdfFailure.RENDER)
                }
            }
        }
        override fun close() {
            if (!retired.compareAndSet(false,true)) return
            cleanup.launch { pdfEngineLock.withLock {
                try { native.close() } catch (_: Exception) { /* Retired; never deliver more work. */ }
                finally {
                    try { Files.deleteIfExists(path) } catch (_: Exception) { /* Clean stale spools on restart. */ }
                    finally { documents.remove(this@OwnedDocument) }
                }
            } }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        pdfOwnerSnapshot(jobs).forEach { it.cancel() }
        pdfOwnerSnapshot(documents).forEach { it.close() }
        shutdown = cleanup.launch {
            pdfOwnerSnapshot(jobs).joinAll()
            // A parser may have produced a document between the snapshots above.
            pdfOwnerSnapshot(documents).forEach { it.close() }
            val self = currentCoroutineContext().job
            cleanup.coroutineContext.job.children.filter { it !== self }.toList().joinAll()
            pdfEngineLock.withLock {
                try { lock?.close() } catch (_: Exception) { }
                finally { try { channel?.close() } catch (_: Exception) { }; lock=null; channel=null }
            }
        }
    }
    override suspend fun awaitClosed() {
        shutdown?.join()
        cleanup.coroutineContext.job.children.toList().joinAll()
        if (closed.get()) cleanup.cancel()
    }
}
