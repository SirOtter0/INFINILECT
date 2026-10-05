// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import org.infinilect.core.*

/** Parallel preparation seam; the neutral opener hands ownership to an enabled EPUB reader. */
internal interface EpubPreparer {
    suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader): EpubDocument
    fun close()
    suspend fun awaitClosed()
}

internal enum class EpubFailure(val userMessage: String) {
    INVALID("This EPUB has unsupported or invalid structure."),
    LIMIT("This EPUB exceeds the supported preparation limits."),
    TRANSFER("The EPUB transfer was incomplete or changed."),
    STORAGE("Could not prepare this EPUB in private temporary storage."),
}
internal class EpubException(val failure: EpubFailure, cause: Throwable? = null) : Exception(failure.userMessage, cause)
