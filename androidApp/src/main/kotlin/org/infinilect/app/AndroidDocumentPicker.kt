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
    private val requests = AndroidDocumentPickerState()
    private var pending: CancellableContinuation<Uri?>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val continuation = synchronized(requests) {
            if (requests.result() == null) null else pending.also { pending = null }
        }
        continuation?.resume(uri)
    }
    override suspend fun pick(): LocalFileSelection? {
        val uri = suspendCancellableCoroutine<Uri?> { continuation ->
            val request = synchronized(requests) { requests.begin().also { pending = continuation } }
            continuation.invokeOnCancellation {
                synchronized(requests) {
                    requests.cancel(request)
                    if (pending === continuation) pending = null
                }
            }
            val failure = synchronized(requests) {
                if (!requests.launch(request)) null // Cancelled/closed before the actual launch.
                else try { launcher.launch(arrayOf("*/*")); null }
                catch (error: Exception) {
                    if (requests.launchFailed(request)) {
                        if (pending === continuation) pending = null
                        error
                    } else null
                }
            }
            failure?.let { continuation.resumeWithException(it) }
        } ?: return null
        return androidLocalFileSelection(resolver,uri)
    }
    fun close() {
        val continuation = synchronized(requests) {
            if (requests.isClosed()) return
            requests.close()
            pending.also { pending = null }
        }
        continuation?.cancel()
        launcher.unregister()
    }
}
