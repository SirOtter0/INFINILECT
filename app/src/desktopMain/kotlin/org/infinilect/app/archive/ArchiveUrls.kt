// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import io.ktor.http.URLBuilder
import java.net.URI
import java.util.Base64

internal data class ArchiveLocation(val host: String, val directory: String)

/** Exact item-scoped locations from fresh MDAPI infrastructure fields, never a wildcard. */
internal object ArchiveUrls {
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

    private fun asciiHost(value: String): String {
        require(value.length in 1..253 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' })
        val host = value.lowercase(java.util.Locale.ROOT)
        val labels = host.split('.')
        require(labels.all { it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() && !it.startsWith("xn--") })
        return host
    }

    fun storageLocation(host: String, directory: String, identifier: String): ArchiveLocation {
        val normalized = asciiHost(host)
        val labels = normalized.split('.')
        // Domain labels establish the zone boundary; exact fresh item membership is still mandatory.
        require(labels.size >= 3 && labels.takeLast(2) == listOf("archive", "org"))
        require(Regex("/[0-9]{1,3}/items/${Regex.escape(identifier(identifier))}").matches(directory))
        return ArchiveLocation(normalized, directory)
    }

    fun redirect(current: String, location: String, identifier: String, filename: String, locations: Set<ArchiveLocation>): String {
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
        val host = asciiHost(requireNotNull(resolved.host))
        val expectedPath = if (host == "archive.org") "/download/$expectedId/$expectedFile" else {
            val coordinate = locations.singleOrNull { it.host == host && resolved.path == "${it.directory}/$expectedFile" }
                ?: throw IllegalArgumentException("Unannounced storage location.")
            val validated = storageLocation(coordinate.host, coordinate.directory, expectedId)
            "${validated.directory}/$expectedFile"
        }
        val canonical = URI("https", null, host, -1, expectedPath, null, null)
        val validPath = resolved.rawPath == canonical.rawPath
        require(validPath) { "Unverified resource redirect." }
        // Normalize host casing/default port so equivalent URLs cannot evade loop detection.
        return canonical.toASCIIString()
    }
}
