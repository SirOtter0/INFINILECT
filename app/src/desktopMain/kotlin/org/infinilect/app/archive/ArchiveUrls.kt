// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import io.ktor.http.URLBuilder
import java.net.URI
import java.util.Base64

/** Narrow, evidence-based subset. Unobserved storage hosts deliberately fail closed. */
internal object ArchiveUrls {
    const val DELIVERY_HOST = "dn760105.eu.archive.org"
    const val PAGE_SIZE = 10
    private val identifierPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
    private val filenamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,199}")

    fun identifier(value: String): String {
        require(identifierPattern.matches(value) && ".." !in value) { "Unsupported item identifier." }
        return value
    }

    fun filename(value: String): String {
        require(filenamePattern.matches(value) && ".." !in value) { "Unsupported file name." }
        return value
    }

    fun metadata(identifier: String) = "https://archive.org/metadata/${identifier(identifier)}"
    fun canonical(identifier: String) = "https://archive.org/details/${identifier(identifier)}"
    fun download(identifier: String, filename: String): String = URI(
        "https", null, "archive.org", -1, "/download/${identifier(identifier)}/${filename(filename)}", null, null,
    ).toASCIIString()

    fun query(value: String): String {
        val query = value.trim()
        require(query.length in 1..256 && query.none { it.isISOControl() }) { "Use 1–256 query characters." }
        return query
    }

    fun search(query: String, page: Int): String = URLBuilder("https://archive.org/advancedsearch.php").apply {
        require(page in 1..1000)
        // This first adapter supports public CC0 text items only, not the whole Archive catalog.
        parameters.append("q", "($query) AND mediatype:texts AND licenseurl:\"http://creativecommons.org/publicdomain/zero/1.0/\" AND NOT access-restricted-item:true")
        parameters.append("fl[]", "identifier")
        parameters.append("fl[]", "title")
        parameters.append("rows", PAGE_SIZE.toString())
        parameters.append("page", page.toString())
        parameters.append("output", "json")
    }.buildString()

    fun token(query: String, page: Int): String = "ia1." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$page\n$query".toByteArray(Charsets.UTF_8))

    fun page(token: String, query: String): Int {
        require(token.length <= 1600 && token.startsWith("ia1.")) { "Invalid page token." }
        val decoded = try { Base64.getUrlDecoder().decode(token.removePrefix("ia1.")).toString(Charsets.UTF_8) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid page token.") }
        val page = decoded.substringBefore('\n').toIntOrNull()
        require(page != null && page in 2..1000 && decoded.substringAfter('\n', "") == query && token(query, page) == token) {
            "Invalid page token."
        }
        return page
    }

    fun redirect(current: String, location: String, identifier: String, filename: String): String {
        require(location.length <= 2048 && !location.contains('\\') && !location.any { it.isISOControl() })
        // Reject ambiguous encodings/traversal before URI resolution normalizes them away.
        require(!Regex("(?i)%2e|%2f|%5c|%25").containsMatchIn(location))
        val target = URI(location)
        require(target.rawPath?.split('/')?.none { it == ".." || it == "." } != false)
        val resolved = URI(current).resolve(target)
        require(resolved.scheme == "https" && resolved.userInfo == null && resolved.fragment == null && resolved.query == null)
        require(resolved.port == -1 || resolved.port == 443)
        val expectedFile = filename(filename)
        val expectedId = identifier(identifier)
        val validPath = when (resolved.host) {
            "archive.org" -> resolved.path == "/download/$expectedId/$expectedFile"
            DELIVERY_HOST -> Regex("/[0-9]{1,3}/items/${Regex.escape(expectedId)}/${Regex.escape(expectedFile)}").matches(resolved.path)
            else -> false
        }
        require(validPath) { "Unverified resource redirect." }
        return resolved.toASCIIString()
    }
}
