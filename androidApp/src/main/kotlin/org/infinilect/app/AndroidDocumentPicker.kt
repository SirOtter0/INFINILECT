// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.infinilect.app.imports.*

/** Activity-owned registration only. Selected content uses an application resolver. */
internal class AndroidDocumentPicker(activity: ComponentActivity) : LocalFilePicker {
    private val resolver = activity.applicationContext.contentResolver
    private val lock = Any()
    private var pending: CancellableContinuation<Uri?>? = null
    private var inFlight = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val continuation = synchronized(lock) { inFlight=false; pending.also { pending=null } }
        continuation?.resume(uri)
    }
    override suspend fun pick(): LocalFileSelection? {
        val uri = suspendCancellableCoroutine<Uri?> { continuation ->
            // A cancelled SAF request can still return. Do not let its URI satisfy a new request.
            synchronized(lock) { check(!inFlight); inFlight=true; pending=continuation }
            continuation.invokeOnCancellation { synchronized(lock) { if(pending === continuation) pending=null } }
            try { launcher.launch(arrayOf("*/*")) }
            catch(e: Exception) { synchronized(lock) { if(pending === continuation) pending=null; inFlight=false }; continuation.resumeWithException(e) }
        } ?: return null
        return androidLocalFileSelection(resolver,uri)
    }
    fun close() { synchronized(lock) { pending?.cancel(); pending=null }; launcher.unregister() }
}
