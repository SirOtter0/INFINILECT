// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import java.net.URI
import java.util.Base64

internal const val GUTENBERG_SEARCH_URL = "https://www.gutenberg.org/ebooks/search.opds/"
internal const val MAX_FEED_BYTES = 1024 * 1024
private const val TOKEN_PREFIX = "gutenberg-opds-1:"

internal object GutenbergUrls {
    fun search(query: String): String {
        require(query.isNotBlank() && query.length <= 256) { "Search must contain 1–256 characters." }
        return URLBuilder(GUTENBERG_SEARCH_URL).apply { parameters.append("query", query) }.buildString()
    }

    /** Only the verified Gutenberg host; legacy HTTP links are upgraded to HTTPS. */
    fun resolve(href: String, base: String): String? = runCatching {
        if (href.length > 8192) return null
        val uri = URI(base).resolve(href)
        if (uri.scheme !in setOf("http", "https") || uri.host != "www.gutenberg.org" ||
            uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.port !in setOf(-1, if (uri.scheme == "https") 443 else 80)) return null
        // Preserve encoded path/query octets: decoding %2F can change the resource.
        URI("https://www.gutenberg.org${uri.rawPath}" + (uri.rawQuery?.let { "?$it" } ?: "")).toASCIIString()
    }.getOrNull()

    fun localId(entryId: String): String? {
        val resolved = resolve(entryId, GUTENBERG_SEARCH_URL) ?: return null
        val url = Url(resolved)
        if (url.parameters.names().isNotEmpty()) return null
        return Regex("/ebooks/([1-9][0-9]{0,9})(?:\\.opds)?").matchEntire(url.encodedPath)
            ?.groupValues?.get(1)?.takeIf { runCatching { GutenbergAcquisition.identifier(it) }.isSuccess }
    }

    fun nextToken(href: String, currentUrl: String, query: String): String {
        val url = resolve(href, currentUrl) ?: throw InvalidOpdsException()
        validatePage(url, query)
        val currentIndex = Url(currentUrl).parameters["start_index"]?.toIntOrNull() ?: 1
        val nextIndex = Url(url).parameters["start_index"]!!.toInt()
        if (nextIndex <= currentIndex) throw InvalidOpdsException()
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))
    }

    fun pageUrl(token: String, query: String): String {
        require(token.startsWith(TOKEN_PREFIX) && token.length <= 4096) { "Invalid Gutenberg page token." }
        val url = try {
            String(Base64.getUrlDecoder().decode(token.removePrefix(TOKEN_PREFIX)), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Gutenberg page token.", error)
        }
        validatePage(url, query)
        return url
    }

    private fun validatePage(value: String, query: String) {
        require(resolve(value, GUTENBERG_SEARCH_URL) == value) { "Invalid Gutenberg page URL." }
        val url = Url(value)
        require(url.encodedPath == "/ebooks/search.opds/" &&
            url.parameters.names() == setOf("query", "start_index") &&
            url.parameters.getAll("query") == listOf(query) &&
            url.parameters.getAll("start_index")?.size == 1 &&
            (url.parameters["start_index"]?.toIntOrNull() ?: 0) > 1) { "Invalid Gutenberg page URL." }
    }
}
