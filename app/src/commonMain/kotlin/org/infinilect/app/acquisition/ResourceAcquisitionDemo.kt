// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.acquisition

import org.infinilect.core.*

/** One explicitly supplied source; no registry, caching, or source-specific consumer policy. */
internal class DirectResourceLoader(private val source: PublicationSource) : ResourceLoader {
    override suspend fun load(resource: PublicationResource): ResourceContent {
        require(resource.publicationId.sourceId == source.id)
        return source.loadResource(resource)
    }
}

/** Callers specify the supported demonstration format; this is not a product preference order. */
internal fun selectDemoResource(publication: Publication, format: PublicationFormat): PublicationResource? =
    publication.resources.firstOrNull { it.format == format }

internal data class AcquisitionEvidence(val format: PublicationFormat, val sizeBytes: Long?, val sampledBytes: Int)

/** Consumes one fresh handle only. A future reader must open its own fresh handle. */
internal suspend fun demonstrateAcquisition(loader: ResourceLoader, resource: PublicationResource): AcquisitionEvidence {
    require(resource.format == PublicationFormat.TEXT) { "This demo currently verifies a text prefix only." }
    val content = loader.load(resource)
    try {
        val prefix = ByteArray(512)
        var total = 0
        while (total < prefix.size) {
            val count = content.read(prefix, total, prefix.size - total)
            if (count == -1) break
            check(count in 1..(prefix.size - total))
            total += count
        }
        check(total > 0) { "Resource is empty." }
        // A bounded prefix may end inside a UTF-8 sequence. Only drop that incomplete suffix.
        var end = total
        var continuation = end - 1
        while (continuation >= 0 && (prefix[continuation].toInt() and 0xc0) == 0x80) continuation--
        if (continuation >= 0) {
            val lead = prefix[continuation].toInt() and 0xff
            val width = when { lead < 0x80 -> 1; lead in 0xc2..0xdf -> 2; lead in 0xe0..0xef -> 3; lead in 0xf0..0xf4 -> 4; else -> 1 }
            if (total - continuation < width) end = continuation
        }
        val decoded = prefix.decodeToString(0, end, throwOnInvalidSequence = true)
        check(decoded.isNotBlank() && '\u0000' !in decoded) { "Resource is not a supported UTF-8 text." }
        return AcquisitionEvidence(resource.format, content.sizeBytes, total)
    } finally { content.close() }
}
