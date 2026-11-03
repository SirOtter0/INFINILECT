// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import org.infinilect.core.PublicationId
import org.infinilect.core.Publication

/** Describes the individual page, independently of a source's reader capability.
 * Namespace/title alone must not upgrade UNKNOWN to a complete WORK. */
internal enum class CatalogPageKind { WORK, TEXT, CHAPTER, AUTHOR, INDEX, VERSIONS, DISAMBIGUATION, UNKNOWN }
internal enum class CatalogEpubState { POTENTIAL, EXPORTABLE, VALIDATED, WEB_ONLY, UNAVAILABLE, INCOMPATIBLE, TEMPORARY_ERROR }
internal enum class CatalogRightsState { VERIFIED, INSUFFICIENT, UNKNOWN, RESTRICTED, PENDING }
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
    val rightsState: CatalogRightsState = if(authorized) CatalogRightsState.VERIFIED else CatalogRightsState.UNKNOWN,
    val rightsRule: String? = null,
) {
    val mayRead get() = authorized && rightsState==CatalogRightsState.VERIFIED && (epubState == CatalogEpubState.POTENTIAL || epubState == CatalogEpubState.EXPORTABLE || epubState == CatalogEpubState.VALIDATED)
    val mayDownload get() = mayRead
}

/** Metadata lookup never exports. The separate explicit user check may acquire
 * an authorized file temporarily, with the existing preparer and safety limits. */
internal interface CatalogPublicationSource {
    suspend fun catalogInfo(id: PublicationId): CatalogPublicationInfo
    /** Explicit metadata refresh only: it must never acquire an EPUB. */
    suspend fun refreshCatalogInfo(id: PublicationId): CatalogPublicationInfo = catalogInfo(id)
    val canCheckEpub: Boolean get() = false
    /** Explicit user action; may temporarily acquire an authorized export. */
    suspend fun checkEpub(id: PublicationId): CatalogPublicationInfo = catalogInfo(id)
}
