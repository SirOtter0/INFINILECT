// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
import java.net.*
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import org.infinilect.app.discovery.*

internal enum class GutenbergStage { ROOT, SEARCH, PAGINATION, DETAIL, THUMBNAIL }
internal enum class GutenbergParsingStage { HTTP, HEADERS, BODY, JSON, DOCUMENT, SEARCH_LINK, COMPLETE }
/** No URL/query/body/message is stored. Endpoint labels are fixed, never remote input. */
internal data class GutenbergDiagnostic(
    val stage: GutenbergStage,
    val parsing: GutenbergParsingStage,
    val status: Int?,
    val durationMillis: Long,
    val failure: CatalogErrorKind?,
    val exceptionClass: String?,
    val causeClass: String?,
    val cancelled: Boolean,
) {
    val host get() = if(stage == GutenbergStage.THUMBNAIL) "www.gutenberg.org" else "opds-test.pglaf.org"
    val path get() = when(stage) {
        GutenbergStage.ROOT -> "/opds/"
        GutenbergStage.SEARCH, GutenbergStage.PAGINATION -> "/opds/search"
        GutenbergStage.DETAIL -> "/opds/publications"
        GutenbergStage.THUMBNAIL -> "/cache/epub/{id}/cover.jpg"
    }
}

internal fun gutenbergErrorKind(error: Exception): CatalogErrorKind {
    if(error is CancellationException) return CatalogErrorKind.CANCELLED
    if(error is CatalogSourceException) return error.kind
    val chain=generateSequence(error as Throwable?) { it.cause }.take(8).toList()
    return when {
        chain.any { it is SSLException || it is CertificateException } -> CatalogErrorKind.TLS
        chain.any { it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException || it is io.ktor.client.network.sockets.SocketTimeoutException } -> CatalogErrorKind.TIMEOUT
        chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is UnresolvedAddressException || it is IOException } -> CatalogErrorKind.CONNECTION
        else -> CatalogErrorKind.INTERNAL
    }
}
internal fun gutenbergHttpKind(status: Int): CatalogErrorKind = when(status) {
    in 300..399 -> CatalogErrorKind.REDIRECT
    403 -> CatalogErrorKind.HTTP_FORBIDDEN
    429 -> CatalogErrorKind.HTTP_RATE_LIMITED
    else -> CatalogErrorKind.HTTP_ERROR
}
internal fun gutenbergDiagnostic(stage: GutenbergStage, parsing: GutenbergParsingStage, status: Int?, started: Long, error: Exception? = null): GutenbergDiagnostic =
    GutenbergDiagnostic(stage, parsing, status, ((System.nanoTime()-started)/1_000_000).coerceAtLeast(0),
        error?.let(::gutenbergErrorKind), error?.javaClass?.name?.take(160), error?.cause?.javaClass?.name?.take(160), error is CancellationException)
internal fun reportGutenberg(diagnostics: (GutenbergDiagnostic)->Unit, diagnostic: GutenbergDiagnostic) {
    runCatching { diagnostics(diagnostic) }
}
