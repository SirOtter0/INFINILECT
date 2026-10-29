// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import org.infinilect.core.*

/** Optional display metadata, never acquisition authority or a second persisted record. */
internal data class PublicationBibliography(
    val publication: Publication,
    val description: String? = null,
    val subjects: List<String> = emptyList(),
    val publisher: String? = null,
    val date: String? = null,
    val identifiers: List<String> = emptyList(),
    val announcedFormats: List<String> = emptyList(),
)
internal interface PublicationBibliographySource {
    suspend fun bibliography(id: PublicationId): PublicationBibliography?
}

internal const val OAPEN_CATALOG_PAGE = "https://library.oapen.org/"
/** Only an OAPEN handle becomes a browser destination; never a metadata-supplied URL. */
internal fun oapenHandle(query: String): String? = query.trim()
    .removePrefix("https://library.oapen.org/handle/")
    .takeIf { Regex("20\\.500\\.12657/[1-9][0-9]{0,9}").matches(it) }
internal fun oapenPage(id: PublicationId): String? =
    if (id.sourceId.value == "oapen") oapenHandle(id.localId)?.let { "${OAPEN_CATALOG_PAGE}handle/$it" } else null
