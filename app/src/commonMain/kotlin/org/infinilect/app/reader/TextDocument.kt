// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.infinilect.core.Publication
import org.infinilect.core.PublicationFormat
import org.infinilect.core.PublicationId
import org.infinilect.core.PublicationResource
import org.infinilect.core.ResourceContent
import org.infinilect.core.ResourceLoader

/** Small documents/short books only: bound full decoding and the first plain-text UI. */
internal const val MAX_TEXT_DOCUMENT_BYTES = 512 * 1024

/** Reader presentation data, with no source, transport, live handle or Compose objects. */
internal data class TextDocument(val publicationId: PublicationId, val title: String, val text: String)

internal enum class TextFailure(val userMessage: String) {
    UNKNOWN_SIZE("The text size is unknown, so it cannot be opened safely yet."),
    TOO_LARGE("This text exceeds the 512 KiB reader limit."),
    EMPTY("This resource has no readable text."),
    INCONSISTENT_SIZE("The text transfer was incomplete or changed. Please try again."),
    INVALID_UTF8("This text is not valid UTF-8."),
}

internal class TextDocumentException(val failure: TextFailure, cause: Throwable? = null) :
    Exception(failure.userMessage, cause)

/** Owns one fresh handle, closes before decoding, and allocates one bounded payload array. */
internal suspend fun loadTextDocument(
    publication: Publication,
    resource: PublicationResource,
    loader: ResourceLoader,
    decodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
): TextDocument {
    require(resource.format == PublicationFormat.TEXT && resource in publication.resources)
    currentCoroutineContext().ensureActive()
    val bytes = consumeText(loader.load(resource))
    return withContext(decodingDispatcher) {
        currentCoroutineContext().ensureActive()
        // Remove exactly one leading UTF-8 BOM; preserve all other text, including interior BOMs.
        val documentBytes = bytes.size - 1 // Exclude the overflow probe.
        val start = if (documentBytes >= 3 && bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) 3 else 0
        // The last array position is the overflow probe, not document data.
        val text = try { bytes.decodeToString(start, documentBytes, throwOnInvalidSequence = true) }
        catch (error: CharacterCodingException) { throw TextDocumentException(TextFailure.INVALID_UTF8, error) }
        currentCoroutineContext().ensureActive()
        if (text.isBlank() || '\u0000' in text) throw TextDocumentException(TextFailure.EMPTY)
        TextDocument(publication.id, publication.title, text)
    }
}

private suspend fun consumeText(content: ResourceContent): ByteArray {
    var failure: Throwable? = null
    try {
        currentCoroutineContext().ensureActive()
        val declared = content.sizeBytes ?: throw TextDocumentException(TextFailure.UNKNOWN_SIZE)
        if (declared > MAX_TEXT_DOCUMENT_BYTES) throw TextDocumentException(TextFailure.TOO_LARGE)
        if (declared < 0) throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
        if (declared == 0L) throw TextDocumentException(TextFailure.EMPTY)
        // Check known size before allocation; one extra byte detects an overlong stream.
        val bytes = ByteArray(declared.toInt() + 1)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (content.sizeBytes != declared) throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
            val requested = minOf(8192, bytes.size - total)
            val count = content.read(bytes, total, requested)
            currentCoroutineContext().ensureActive()
            if (content.sizeBytes != declared) throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
            if (count == -1) {
                if (total.toLong() != declared) throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
                return bytes
            }
            if (count !in 1..requested || count > declared - total)
                throw TextDocumentException(TextFailure.INCONSISTENT_SIZE)
            total += count
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try { content.close() }
        catch (closeError: Throwable) {
            val primary = failure
            if (primary == null) throw closeError
            if (primary !== closeError) primary.addSuppressed(closeError)
        }
    }
}
