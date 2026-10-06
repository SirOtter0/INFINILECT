// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.infinilect.core.ResourceContent

/** Resolver must come from applicationContext. No persisted URI grant or external identity. */
suspend fun androidLocalFileSelection(resolver: ContentResolver, uri: Uri): LocalFileSelection = withContext(Dispatchers.IO) {
    require(uri.scheme == "content")
    var name: String? = null; var size: Long? = null
    providerOperation { signal -> resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null,signal) }?.use { cursor ->
        if(cursor.moveToFirst()) {
            val display = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if(display >= 0 && !cursor.isNull(display)) name = cursor.getString(display)?.take(4096)
            val length = cursor.getColumnIndex(OpenableColumns.SIZE)
            if(length >= 0 && !cursor.isNull(length)) size = cursor.getLong(length)
        }
    }
    LocalFileSelection(name) {
        var opened: ResourceContent? = null
        try { withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val descriptor = providerOperation { signal -> resolver.openAssetFileDescriptor(uri,"r",signal) }
                ?: throw java.io.IOException()
            val input = try { descriptor.createInputStream() } catch(e: Throwable) { descriptor.close(); throw e }
            object : ResourceContent {
                private val closed = AtomicBoolean()
                override val sizeBytes = size
                override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                    require(offset in 0..buffer.size && length in 0..buffer.size-offset); check(!closed.get())
                    if(length == 0) return 0
                    return withContext(Dispatchers.IO) { currentCoroutineContext().ensureActive(); check(!closed.get()); input.read(buffer,offset,minOf(length,8192)) }
                }
                override fun close() { if(closed.compareAndSet(false,true)) input.close() }
            }.also { opened = it }
        } } catch(e: Throwable) { opened?.close(); throw e }
    }
}

/** Cancellation reaches cooperative SAF providers even while their query/open call blocks IO. */
private suspend fun <T> providerOperation(block: (CancellationSignal) -> T): T {
    var opened: T? = null
    try { return coroutineScope {
        val signal = CancellationSignal()
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try { currentCoroutineContext().ensureActive(); block(signal).also { opened = it } }
        finally { cancellation.cancel() }
    } } catch(e: Throwable) {
        // A cancelled scope can discard a cursor/descriptor produced just before cancellation.
        try { (opened as? java.io.Closeable)?.close() } catch (_: Exception) { }
        throw e
    }
}

internal fun androidImportDirectory(filesDir: java.io.File): java.nio.file.Path =
    filesDir.toPath().resolve(IMPORT_DIRECTORY_NAME)
