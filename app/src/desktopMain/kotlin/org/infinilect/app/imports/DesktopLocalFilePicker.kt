// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.infinilect.core.ResourceContent

/** Native AWT selector; dialog lifetime belongs to the suspended picker request. */
class DesktopLocalFilePicker : LocalFilePicker {
    override suspend fun pick(): LocalFileSelection? {
        val selected = suspendCancellableCoroutine<Path?> { continuation ->
            val dialog = AtomicReference<FileDialog?>()
            continuation.invokeOnCancellation { EventQueue.invokeLater { dialog.getAndSet(null)?.dispose() } }
            EventQueue.invokeLater {
                if(!continuation.isActive) return@invokeLater
                try {
                    val picker = FileDialog(null as Frame?, "Import TEXT, EPUB or CBZ", FileDialog.LOAD)
                    dialog.set(picker)
                    try {
                        picker.isVisible=true
                        val file=picker.file
                        val path=if(file == null) null else Paths.get(picker.directory,file).toAbsolutePath()
                        if(continuation.isActive) continuation.resume(path)
                    } finally { picker.dispose(); dialog.set(null) }
                } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            }
        } ?: return null
        return desktopLocalFileSelection(selected)
    }
}

internal fun desktopLocalFileSelection(path: Path): LocalFileSelection = LocalFileSelection(path.fileName.toString()) {
    var opened: ResourceContent? = null
    try { withContext(Dispatchers.IO) {
        require(path.isAbsolute && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
        val size=Files.size(path)
        val input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)
        object : ResourceContent {
            private val closed=AtomicBoolean()
            override val sizeBytes=size
            override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                require(offset in 0..buffer.size && length in 0..buffer.size-offset); check(!closed.get())
                if(length == 0) return 0
                return withContext(Dispatchers.IO) { currentCoroutineContext().ensureActive(); check(!closed.get()); input.read(buffer,offset,minOf(length,8192)) }
            }
            override fun close() { if(closed.compareAndSet(false,true)) input.close() }
        }.also { opened=it }
    } } catch(e: Throwable) { opened?.close(); throw e }
}
