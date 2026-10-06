// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import org.infinilect.core.Publication
import org.infinilect.core.ResourceContent

/** A one-shot platform acquisition offer. The open callback is consumed off the UI thread;
 * paths/URIs stay inside its platform implementation and are never persisted. */
class LocalFileSelection(val displayName: String?, val open: suspend () -> ResourceContent)

interface LocalFilePicker {
    /** Null is normal picker cancellation. */
    suspend fun pick(): LocalFileSelection?
}

/** Durable acquisition ownership, independent of readers, cache, Library and History. */
internal interface LocalPublicationImporter {
    suspend fun import(selection: LocalFileSelection): Publication
    fun close()
    suspend fun awaitClosed()
}

internal enum class ImportFailure(val userMessage: String) {
    UNSUPPORTED("This file is not a supported UTF-8 TEXT, EPUB or CBZ publication."),
    LIMIT("This file exceeds the supported import limits or local import storage is full."),
    TRANSFER("The selected file could not be read completely. Please try again."),
    STORAGE("The file could not be saved in private application storage. Please try again."),
}
internal class LocalImportException(val failure: ImportFailure) : Exception(failure.userMessage)
internal data class LocalImportState(val busy: Boolean = false, val message: String? = null)
