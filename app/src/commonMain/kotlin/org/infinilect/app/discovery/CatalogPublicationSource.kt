// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import org.infinilect.core.PublicationId
import org.infinilect.core.Publication

/** Describes the individual page, independently of a source's reader capability.
 * Namespace/title alone must not upgrade UNKNOWN to a complete WORK. */
internal enum class CatalogPageKind { WORK, CHAPTER, AUTHOR, INDEX, VERSIONS, DISAMBIGUATION, UNKNOWN }
internal enum class CatalogEpubState { POTENTIAL, VALIDATED, WEB_ONLY, UNAVAILABLE, INCOMPATIBLE, RIGHTS_UNVERIFIED }
internal data class CatalogPublicationInfo(
    val kind: CatalogPageKind,
    val epubState: CatalogEpubState,
    val description: String? = null,
    /** Adapter-validated canonical HTTPS page; never an acquisition/resource URL. */
    val officialUrl: String? = null,
    /** Separate positive rights evidence. Technical validity alone never grants this. */
    val authorized: Boolean = false,
    /** Optional freshly resolved bibliographic snapshot for this exact page ID. */
    val metadata: Publication? = null,
) {
    val mayRead get() = authorized && (epubState == CatalogEpubState.POTENTIAL || epubState == CatalogEpubState.VALIDATED)
    val mayDownload get() = mayRead
}

/** Bounded metadata lookup only. Calling this must not export or download an EPUB. */
internal interface CatalogPublicationSource {
    suspend fun catalogInfo(id: PublicationId): CatalogPublicationInfo
}
