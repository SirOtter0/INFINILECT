// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*
import org.infinilect.core.*

internal const val MAX_METADATA_BYTES = 2 * 1024 * 1024
internal const val MAX_RESOURCE_BYTES = 64L * 1024 * 1024
internal val ARCHIVE_ID = SourceId("internet-archive")
internal class InvalidArchiveData : Exception("Internet Archive returned unsupported data.")

internal data class ArchiveFile(val resource: PublicationResource, val size: Long)
internal data class ArchiveItem(
    val publication: Publication,
    val files: List<ArchiveFile>,
    val rights: String?,
    val licenseUrl: String?,
    val locations: Set<ArchiveLocation>,
    val possibleCopyrightStatus: String?,
)

/** Bounded JSON metadata, not a universal Internet Archive schema. */
internal object ArchiveMetadata {
    private val json = Json { isLenient = false }
    private val cc0 = setOf("http://creativecommons.org/publicdomain/zero/1.0/", "https://creativecommons.org/publicdomain/zero/1.0/")

    private fun document(bytes: ByteArray, checkCancellation: () -> Unit): JsonObject {
        if (bytes.size > MAX_METADATA_BYTES) throw InvalidArchiveData()
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw InvalidArchiveData() }
        // Limit nesting before the recursive JSON parser sees untrusted input.
        var depth = 0
        var quoted = false
        var escaped = false
        for ((index, char) in text.withIndex()) {
            if (index % 8192 == 0) checkCancellation()
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '[', '{' -> if (++depth > 32) throw InvalidArchiveData()
                ']', '}' -> if (--depth < 0) throw InvalidArchiveData()
            }
        }
        checkCancellation()
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        checkCancellation()
        if (root == null || root["error"] != null) throw InvalidArchiveData()
        return root
    }

    private fun JsonObject.strings(key: String): List<String> {
        val value = this[key] ?: return emptyList()
        if (value is JsonNull) return emptyList()
        val values = if (value is JsonArray) value else listOf(value)
        if (values.size > 200) throw InvalidArchiveData()
        return values.map {
            val primitive = it as? JsonPrimitive ?: throw InvalidArchiveData()
            if (!primitive.isString || primitive.content.length > 16_384) throw InvalidArchiveData()
            primitive.content
        }.filter { it.isNotBlank() }
    }

    private fun JsonObject.string(key: String) = strings(key).singleOrNull()
    private fun JsonObject.flag(key: String): Boolean {
        val value = this[key] ?: return false
        // Present but ambiguous restriction flags are not proof of public access.
        return (value as? JsonPrimitive)?.content?.lowercase() !in setOf("false", "0")
    }

    private fun locations(root: JsonObject, identifier: String): Set<ArchiveLocation> {
        val result = mutableSetOf<ArchiveLocation>()
        val primary = root.strings("workable_servers").toMutableSet()
        root.string("server")?.let(primary::add)
        if (primary.size > 16) throw InvalidArchiveData()
        root.string("dir")?.let { directory ->
            for (host in primary) result += ArchiveUrls.storageLocation(host, directory, identifier)
        }
        // Observed in the official MDAPI response, separate from uploader-controlled metadata.
        val alternate = root["alternate_locations"]
        if (alternate != null) {
            val objectValue = alternate as? JsonObject ?: throw InvalidArchiveData()
            val workable = objectValue["workable"]
            if (workable != null) {
                val entries = workable as? JsonArray ?: throw InvalidArchiveData()
                if (entries.size > 16) throw InvalidArchiveData()
                for (entry in entries) {
                    val coordinate = entry as? JsonObject ?: throw InvalidArchiveData()
                    result += ArchiveUrls.storageLocation(coordinate.string("server") ?: throw InvalidArchiveData(),
                        coordinate.string("dir") ?: throw InvalidArchiveData(), identifier)
                }
            }
        }
        return result
    }

    fun search(bytes: ByteArray, query: String, page: Int, checkCancellation: () -> Unit = {}): SearchPage {
        val response = document(bytes, checkCancellation)["response"] as? JsonObject ?: throw InvalidArchiveData()
        val found = (response["numFound"] as? JsonPrimitive)?.longOrNull ?: throw InvalidArchiveData()
        val docs = response["docs"] as? JsonArray ?: throw InvalidArchiveData()
        if (found < 0 || docs.size > ArchiveUrls.PAGE_SIZE) throw InvalidArchiveData()
        val publications = docs.map { element ->
            val doc = element as? JsonObject ?: throw InvalidArchiveData()
            val identifier = doc.string("identifier") ?: throw InvalidArchiveData()
            try { ArchiveUrls.identifier(identifier) } catch (_: IllegalArgumentException) { throw InvalidArchiveData() }
            Publication(PublicationId(ARCHIVE_ID, identifier), doc.string("title") ?: identifier,
                PublicationType.DOCUMENT, sourceUrl = ArchiveUrls.canonical(identifier))
        }
        val next = if (docs.isNotEmpty() && page < 1000 && page.toLong() * ArchiveUrls.PAGE_SIZE < found)
            ArchiveUrls.token(query, page + 1) else null
        return SearchPage(publications, next)
    }

    fun item(bytes: ByteArray, expectedId: String, checkCancellation: () -> Unit = {}): ArchiveItem? {
        // The documented empty array denotes a nonexistent item.
        if (bytes.toString(Charsets.UTF_8).trim() == "[]") return null
        val root = document(bytes, checkCancellation)
        val metadata = root["metadata"] as? JsonObject ?: throw InvalidArchiveData()
        val identifier = metadata.string("identifier") ?: throw InvalidArchiveData()
        if (identifier != expectedId) throw InvalidArchiveData()
        ArchiveUrls.identifier(identifier)
        val id = PublicationId(ARCHIVE_ID, identifier)
        val rights = metadata.string("rights")
        val license = metadata.string("licenseurl")
        val restricted = root.flag("is_dark") || root.flag("is_restricted") || root.flag("nodownload") || root.flag("is_collection") || root["lendingInfo"] != null ||
            metadata.flag("access-restricted-item") || metadata.keys.any { it.startsWith("lending") } ||
            metadata.strings("collection").any { it in setOf("loggedin", "inlibrary", "printdisabled", "lendinglibrary") }
        val rawFiles = root["files"] as? JsonArray ?: throw InvalidArchiveData()
        if (rawFiles.size > 200) throw InvalidArchiveData()
        val files = if (restricted || license !in cc0 || metadata.string("mediatype") != "texts") emptyList() else
            rawFiles.mapNotNull { value ->
                val file = value as? JsonObject ?: throw InvalidArchiveData()
                if (file.flag("private") || file.flag("restricted")) return@mapNotNull null
                // Never override a file-specific declaration with the item-level CC0 field.
                if (file["rights"] != null || (file["licenseurl"] != null && file.string("licenseurl") !in cc0)) return@mapNotNull null
                val name = file.string("name") ?: return@mapNotNull null
                try { ArchiveUrls.filename(name) } catch (_: IllegalArgumentException) { return@mapNotNull null }
                val format = file.string("format")
                val representation = when {
                    format in setOf("DjVuTXT", "Text") && name.endsWith(".txt", true) -> PublicationFormat.TEXT to "text/plain"
                    format in setOf("Text PDF", "PDF") && name.endsWith(".pdf", true) -> PublicationFormat.PDF to "application/pdf"
                    else -> return@mapNotNull null
                }
                val size = (file["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
                if (size !in 1..MAX_RESOURCE_BYTES) return@mapNotNull null
                // Hashes are reported by IA but not yet verified against a complete streamed file.
                ArchiveFile(PublicationResource(id, name, representation.first, representation.second, revision = null), size)
            }
        if (files.map { it.resource.key }.distinct().size != files.size) throw InvalidArchiveData()
        return ArchiveItem(Publication(id, metadata.string("title") ?: identifier, PublicationType.DOCUMENT,
            metadata.strings("creator"), files.map { it.resource }, metadata.strings("language"),
            ArchiveUrls.canonical(identifier), rights ?: license), files, rights, license,
            locations(root, identifier), metadata.string("possible-copyright-status"))
    }
}
