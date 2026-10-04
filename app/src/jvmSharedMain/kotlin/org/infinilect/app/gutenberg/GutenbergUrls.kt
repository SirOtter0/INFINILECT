// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.infinilect.core.SourceId

internal val GUTENBERG_ID = SourceId("gutenberg")
internal const val GUTENBERG_ROOT = "https://opds-test.pglaf.org/opds/"
internal const val GUTENBERG_SEARCH_TEMPLATE = "https://opds-test.pglaf.org/opds/search{?query,title,author}"
internal const val MAX_FEED_BYTES = 1024 * 1024
internal const val GUTENBERG_UNSUPPORTED = "Project Gutenberg's experimental catalog does not support TEXT reading yet."
private const val TOKEN_PREFIX = "gutenberg-opds2-preview-1:"

/** Observed development-service routes, never a general URI-template/OPDS resolver. */
internal object GutenbergUrls {
    fun identifier(value: String): String {
        require(Regex("[1-9][0-9]{0,9}").matches(value) && value.toLong() <= Int.MAX_VALUE)
        return value
    }
    fun canonical(id: String) = "https://www.gutenberg.org/ebooks/${identifier(id)}"
    fun localId(value: String): String? = Regex("https://www\\.gutenberg\\.org/ebooks/([1-9][0-9]{0,9})")
        .matchEntire(value)?.groupValues?.get(1)?.takeIf { runCatching { identifier(it) }.isSuccess }
    fun publication(id: String) = "${GUTENBERG_ROOT}publications?id=${identifier(id)}"
    fun query(value: String) {
        require(value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() })
    }
    fun search(value: String): String {
        query(value)
        return URLBuilder("${GUTENBERG_ROOT}search").apply { parameters.append("query", value) }.buildString()
    }
    private fun strict(value: String): URI {
        require(value.length <= 8192 && value.none { it.code !in 0x21..0x7e } && '\\' !in value)
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host == "opds-test.pglaf.org" && uri.rawAuthority == uri.host &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawFragment == null)
        require(uri.rawPath in setOf("/opds/search", "/opds/publications", "/opds/"))
        return uri
    }
    fun page(value: String, query: String): Int {
        query(query)
        val uri = strict(value)
        require(uri.rawPath == "/opds/search")
        val url = Url(value)
        require(url.parameters.names() == setOf("limit", "query", "page"))
        require(url.parameters.getAll("limit") == listOf("25") && url.parameters.getAll("query") == listOf(query))
        val pages = url.parameters.getAll("page") ?: error("Missing page")
        require(pages.size == 1 && Regex("[1-9][0-9]{0,3}").matches(pages.single()))
        return pages.single().toInt().also { require(it <= 1000) }
    }
    fun nextToken(value: String, query: String, currentPage: Int): String {
        require(page(value, query) == currentPage + 1)
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    }
    fun pageUrl(token: String, query: String): String {
        require(token.startsWith(TOKEN_PREFIX) && token.length <= 4096)
        val encoded = token.removePrefix(TOKEN_PREFIX)
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
        val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(page(value, query) >= 2)
        return value
    }
}
