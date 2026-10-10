// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import org.infinilect.app.search.SearchException

/** Display-only classification; never raw transport messages or publication metadata. */
internal enum class CatalogErrorKind {
    CONNECTION, TLS, TIMEOUT, HTTP_FORBIDDEN, HTTP_RATE_LIMITED, HTTP_ERROR,
    REDIRECT, RESPONSE_FORMAT, INVALID_OPDS, INVALID_JSON, SEARCH_LINK, CANCELLED, INTERNAL
}
internal open class CatalogSourceException(val kind: CatalogErrorKind, cause: Throwable? = null) :
    SearchException(catalogFailureMessage("Project Gutenberg", kind), cause)

internal fun catalogFailureMessage(source: String, kind: CatalogErrorKind?): String = when(kind) {
    CatalogErrorKind.CONNECTION -> "$source could not connect. Check your connection and retry."
    CatalogErrorKind.TLS -> "$source could not establish a secure connection. Check the device date and network, then retry."
    CatalogErrorKind.TIMEOUT -> "$source took too long to respond. Retry when the connection improves."
    CatalogErrorKind.HTTP_FORBIDDEN -> "$source blocked this request. Retry later."
    CatalogErrorKind.HTTP_RATE_LIMITED -> "$source is receiving too many requests. Wait before retrying."
    CatalogErrorKind.HTTP_ERROR -> "$source returned a server error. Retry later."
    CatalogErrorKind.REDIRECT -> "$source redirected to a route this catalog does not support."
    CatalogErrorKind.RESPONSE_FORMAT -> "$source returned an unsupported response format."
    CatalogErrorKind.INVALID_OPDS -> "$source returned an invalid OPDS 2.0 document."
    CatalogErrorKind.INVALID_JSON -> "$source returned invalid JSON."
    CatalogErrorKind.SEARCH_LINK -> "$source did not supply a supported OPDS 2.0 search link."
    CatalogErrorKind.CANCELLED -> "$source interrupted the request. Retry."
    CatalogErrorKind.INTERNAL -> "$source could not complete the request. Retry."
    null -> DiscoveryStrings.sourceUnavailable(source)
}
