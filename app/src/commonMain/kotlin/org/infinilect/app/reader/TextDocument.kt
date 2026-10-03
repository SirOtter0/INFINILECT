// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import kotlinx.coroutines.CoroutineDispatcher
import org.infinilect.core.*
import kotlin.math.roundToInt

// Reader preparation has its own finite disk/CPU budget; acquisition remains capped at 64 MiB.
internal const val MAX_TEXT_DOCUMENT_BYTES = 16 * 1024 * 1024
internal const val TEXT_READ_BUFFER_BYTES = 8192
internal const val TEXT_WINDOW_CODE_POINTS = 2048
internal const val TEXT_MIN_WINDOW_CODE_POINTS = 1024
internal const val TEXT_WINDOW_CACHE_ENTRIES = 8

internal data class TextWindow(val index: Int, val startCodePoint: Int, val text: String) {
    val locations = TextLocations(text)
    fun globalOffset(utf16Offset: Int): Int = startCodePoint + locations.locator(utf16Offset).codePointOffset.toInt()
}

/** No transport/platform types. Window IO is suspendable; close is nonblocking/idempotent. */
internal interface TextWindows {
    val codePoints: Int
    val count: Int
    fun start(index: Int): Int
    suspend fun read(index: Int): TextWindow
    fun close()
}

internal class TextDocument(
    val publicationId: PublicationId,
    val title: String,
    internal val windows: TextWindows,
    val progressId: ReadingProgressId? = null,
) {
    val codePoints: Int get() = windows.codePoints
    val windowCount: Int get() = windows.count
    fun windowStart(index: Int): Int = windows.start(index)
    suspend fun window(index: Int): TextWindow = windows.read(index)
    fun windowFor(offset: Int): Int {
        val target = offset.coerceIn(0, codePoints)
        var low = 0; var high = windowCount - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (windowStart(mid) <= target) low = mid else high = mid - 1
        }
        return low
    }
    fun restore(progress: ReadingProgress?): Int {
        if (progress == null || progress.id != progressId) return 0
        val locator = progress.locator as? ReadingLocator.Text ?: return 0
        return if (locator.documentCodePoints == codePoints.toLong()) locator.codePointOffset.coerceIn(0, codePoints.toLong()).toInt()
        else (progress.progression * codePoints).roundToInt().coerceIn(0, codePoints)
    }
    fun progression(offset: Int): Double = if (codePoints == 0) 0.0 else offset.coerceIn(0, codePoints).toDouble() / codePoints
    fun close() = windows.close()
}

internal enum class TextFailure(val userMessage: String) {
    UNKNOWN_SIZE("This text cannot be opened safely because its size is unknown."),
    TOO_LARGE("This text exceeds the 16 MiB supported reader limit."),
    EMPTY("This resource has no readable text."),
    INCONSISTENT_SIZE("The text transfer was incomplete or changed. Please try again."),
    INVALID_UTF8("This text is not valid UTF-8."),
    STORAGE("Could not prepare this text in private temporary storage. Please try again."),
}
internal class TextDocumentException(val failure: TextFailure, cause: Throwable? = null) :
    Exception(failure.userMessage, cause)

internal interface TextPreparer {
    suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader,
                        dispatcher: CoroutineDispatcher): TextDocument
    fun close() {}
    suspend fun awaitClosed() {}
}
internal expect val textPreparationDispatcher: CoroutineDispatcher
internal expect fun defaultTextPreparer(): TextPreparer

internal suspend fun loadTextDocument(
    publication: Publication, resource: PublicationResource, loader: ResourceLoader,
    decodingDispatcher: CoroutineDispatcher = textPreparationDispatcher,
    preparer: TextPreparer = defaultTextPreparer(),
): TextDocument {
    require(resource.format == PublicationFormat.TEXT && resource in publication.resources)
    return preparer.prepare(publication, resource, loader, decodingDispatcher)
}
