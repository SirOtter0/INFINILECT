// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import org.infinilect.core.Publication

internal enum class CatalogReadability { READABLE_CONFIRMED, READABLE_CANDIDATE, UNVERIFIED, UNAVAILABLE, WEB_ONLY, TEMPORARY_ERROR }
internal enum class ReadabilityMode { READABLE, ALL }

internal fun CatalogPublicationInfo.readability() = when {
    epubState == CatalogEpubState.WEB_ONLY -> CatalogReadability.WEB_ONLY
    epubState == CatalogEpubState.TEMPORARY_ERROR -> CatalogReadability.TEMPORARY_ERROR
    epubState in setOf(CatalogEpubState.UNAVAILABLE, CatalogEpubState.INCOMPATIBLE) -> CatalogReadability.UNAVAILABLE
    !authorized || rightsState != CatalogRightsState.VERIFIED -> CatalogReadability.UNVERIFIED
    epubState == CatalogEpubState.VALIDATED -> CatalogReadability.READABLE_CONFIRMED
    else -> CatalogReadability.READABLE_CANDIDATE
}

internal interface ReadabilityCatalogSource : DiscoverySource {
    suspend fun discoverReadable(request: DiscoveryRequest, token: String?): DiscoveryPage
    suspend fun cachedReadability(publication: Publication): CatalogReadability
    suspend fun verifyReadable(publication: Publication): CatalogReadability = cachedReadability(publication)
}

internal fun CatalogReadability.resultLabel(): org.infinilect.app.ui.UiText = when (this) {
    CatalogReadability.READABLE_CONFIRMED -> org.infinilect.app.ui.UiText.WIKI_FILTER_CONFIRMED
    CatalogReadability.READABLE_CANDIDATE -> org.infinilect.app.ui.UiText.WIKI_FILTER_PENDING
    CatalogReadability.UNVERIFIED -> org.infinilect.app.ui.UiText.WIKI_FILTER_UNVERIFIED
    CatalogReadability.UNAVAILABLE -> org.infinilect.app.ui.UiText.WIKI_FILTER_UNAVAILABLE
    CatalogReadability.WEB_ONLY -> org.infinilect.app.ui.UiText.WIKI_FILTER_WEB
    CatalogReadability.TEMPORARY_ERROR -> org.infinilect.app.ui.UiText.WIKI_EPUB_TEMPORARY
}
