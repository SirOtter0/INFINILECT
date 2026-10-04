// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import java.net.URI
import io.ktor.http.ContentType
import org.infinilect.core.*

internal const val GUTENBERG_TEXT_KEY = "text-utf8"
internal const val GUTENBERG_TEXT_MIME = "text/plain; charset=utf-8"
internal val GUTENBERG_ID = SourceId("gutenberg")

/** No URL/revision is persisted as authority. Only fresh RDF authorizes a location. */
internal data class GutenbergTextFile(val url: String, val size: Long)
internal data class GutenbergBook(val publication: Publication, val text: GutenbergTextFile?)

internal object GutenbergAcquisition {
    fun identifier(value: String): String {
        require(Regex("[1-9][0-9]{0,9}").matches(value) && value.toLong() <= Int.MAX_VALUE) { "Invalid Gutenberg identifier." }
        return value
    }
    fun metadata(id: String) = "https://www.gutenberg.org/cache/epub/${identifier(id)}/pg$id.rdf"
    fun canonical(id: String) = "https://www.gutenberg.org/ebooks/${identifier(id)}"
    fun resource(id: PublicationId) = PublicationResource(id, GUTENBERG_TEXT_KEY, PublicationFormat.TEXT, GUTENBERG_TEXT_MIME)

    fun utf8(type: String): Boolean = runCatching {
        val parsed = ContentType.parse(type)
        parsed.contentType.equals("text", true) && parsed.contentSubtype.equals("plain", true) &&
            parsed.parameters.size == 1 && parsed.parameters.single().name.equals("charset", true) &&
            parsed.parameters.single().value.equals("utf-8", true)
    }.getOrDefault(false)

    /** Deliberately ASCII, no escapes, queries or normalization before validating raw input. */
    fun location(value: String, id: String): String? = runCatching {
        identifier(id)
        if (value.length > 2048 || value.any { it.code !in 0x21..0x7e } || '%' in value || '\\' in value) return null
        val u = URI(value)
        if (u.scheme != "https" || u.host != "www.gutenberg.org" || u.rawUserInfo != null ||
            u.port != -1 || u.rawQuery != null || u.rawFragment != null) return null
        val path = u.rawPath
        if (path.split('/').any { it == "." || it == ".." }) return null
        val valid = path == "/ebooks/$id.txt.utf-8" || path == "/cache/epub/$id/pg$id.txt" ||
            Regex("/files/$id/[A-Za-z0-9][A-Za-z0-9_-]{0,127}\\.txt").matches(path)
        if (!valid || u.toASCIIString() != value) return null
        value
    }.getOrNull()

    /** At most the same fresh file or the item-specific generated variant's delivery route. No mirrors. */
    fun redirect(current: String, raw: String, id: String, selected: String): String {
        val target = if (raw.startsWith('/') && !raw.startsWith("//")) "https://www.gutenberg.org$raw" else raw
        val valid = location(target, id) ?: throw InvalidOpdsException()
        val generated = selected == "https://www.gutenberg.org/ebooks/$id.txt.utf-8" &&
            valid == "https://www.gutenberg.org/cache/epub/$id/pg$id.txt"
        if (valid != selected && !generated) throw InvalidOpdsException()
        // current was already validated; never permit redirect normalization to expand authority.
        if (location(current, id) == null) throw InvalidOpdsException()
        return valid
    }
}
