// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*
import org.infinilect.app.search.SearchException
import org.infinilect.core.*

internal class InvalidOpdsException(cause: Throwable? = null) :
    SearchException("Project Gutenberg's experimental catalog returned unsupported data.", cause)

/** Actual development OPDS2 JSON subset. No XML, generic templates, or acquisition fallback. */
internal object GutenbergOpds2Parser {
    private val json = Json { isLenient = false }
    private fun invalid(): Nothing = throw InvalidOpdsException()
    private fun document(bytes: ByteArray, cancelled: () -> Unit): JsonObject {
        if (bytes.size > MAX_FEED_BYTES) invalid()
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { invalid() }
        // Preflight recursive-parser depth and duplicate keys, including escaped aliases.
        val objects = mutableListOf<MutableSet<String>?>()
        var i = 0
        while (i < text.length) {
            if (i % 1024 == 0) cancelled()
            when (text[i]) {
                '{', '[' -> { objects += if (text[i] == '{') mutableSetOf<String>() else null; if (objects.size > 32) invalid() }
                '}', ']' -> { if (objects.isEmpty()) invalid(); objects.removeAt(objects.lastIndex) }
                '"' -> {
                    val begin = i++; var escaped = false
                    while (i < text.length) {
                        if (i % 1024 == 0) cancelled()
                        if (i - begin > 65_536) invalid()
                        val char = text[i]
                        if (!escaped && char == '"') break
                        escaped = !escaped && char == '\\'
                        i++
                    }
                    if (i == text.length) invalid()
                    var next = i + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        val key = try { (json.parseToJsonElement(text.substring(begin, i + 1)) as JsonPrimitive).content }
                        catch (_: Exception) { invalid() }
                        val keys = objects.lastOrNull() ?: invalid()
                        if (!keys.add(key) || keys.size > 64) invalid()
                    }
                }
            }
            i++
        }
        if (objects.isNotEmpty()) invalid()
        cancelled()
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: invalid()
        var nodes = 0
        fun bound(element: JsonElement) {
            if (++nodes > 20_000) invalid()
            if (nodes % 128 == 0) cancelled()
            when (element) {
                is JsonObject -> { if (element.size > 64) invalid(); element.values.forEach(::bound) }
                is JsonArray -> { if (element.size > 256) invalid(); element.forEach(::bound) }
                is JsonPrimitive -> if (element.content.length > 16_384) invalid()
            }
        }
        bound(root); cancelled(); return root
    }
    private fun JsonObject.string(key: String, required: Boolean = false, max: Int = 16_384): String? {
        val element = this[key] ?: return if (required) invalid() else null
        val value = element as? JsonPrimitive ?: invalid()
        if (!value.isString || value.content.length > max || (required && value.content.isBlank())) invalid()
        return value.content
    }
    private fun JsonObject.array(key: String, required: Boolean = false): JsonArray =
        (this[key]?.let { it as? JsonArray ?: invalid() } ?: if (required) invalid() else JsonArray(emptyList()))
    private fun JsonObject.number(key: String): Long {
        val primitive = this[key] as? JsonPrimitive ?: invalid()
        if (primitive.isString || !Regex("0|[1-9][0-9]{0,18}").matches(primitive.content)) invalid()
        return primitive.content.toLongOrNull() ?: invalid()
    }
    private fun objectValue(value: JsonElement) = value as? JsonObject ?: invalid()
    private fun links(root: JsonObject) = root.array("links", required = true).map(::objectValue).also { if (it.size > 32) invalid() }
    private fun relations(link: JsonObject): List<String> {
        val raw = link["rel"] ?: invalid()
        val list = if (raw is JsonArray) raw else listOf(raw)
        if (list.size > 8) invalid()
        return list.map { val p = it as? JsonPrimitive ?: invalid(); if (!p.isString) invalid(); p.content }
    }
    fun root(bytes: ByteArray, cancelled: () -> Unit = {}): String {
        val root = document(bytes, cancelled)
        objectValue(root["metadata"] ?: invalid()).string("title", required = true)
        val links = links(root)
        val self = links.singleOrNull { "self" in relations(it) } ?: invalid()
        if (self.string("href") != GUTENBERG_ROOT || self.string("type") != "application/opds+json") invalid()
        val search = links.singleOrNull { "search" in relations(it) } ?: invalid()
        if (search.string("href") != GUTENBERG_SEARCH_TEMPLATE || search.string("type") != "application/opds+json" ||
            (search["templated"] as? JsonPrimitive)?.booleanOrNull != true || (search["templated"] as JsonPrimitive).isString) invalid()
        // Navigation/groups are bounded but intentionally neither fetched nor rendered.
        return GUTENBERG_SEARCH_TEMPLATE
    }
    fun search(bytes: ByteArray, query: String, currentPage: Int, cancelled: () -> Unit = {}): SearchPage {
        val root = document(bytes, cancelled)
        val metadata = objectValue(root["metadata"] ?: invalid())
        val count = metadata.number("numberOfItems")
        if (metadata.number("itemsPerPage") != 25L || metadata.number("currentPage") != currentPage.toLong()) invalid()
        val raw = root.array("publications", required = true)
        if (raw.size > 25 || raw.size.toLong() > count) invalid()
        val publications = raw.map { cancelled(); publication(objectValue(it)) }
        if (publications.map { it.id }.distinct().size != publications.size) invalid()
        val links = links(root)
        val self = links.singleOrNull { "self" in relations(it) } ?: invalid()
        if (self.string("type") != "application/opds+json" ||
            runCatching { GutenbergUrls.page(self.string("href", true)!!, query) }.getOrNull() != currentPage) invalid()
        val nextLinks = links.filter { "next" in relations(it) }
        if (nextLinks.size > 1) invalid()
        val next = nextLinks.singleOrNull()?.let {
            if (publications.isEmpty() || currentPage.toLong() * 25 >= count || it.string("type") != "application/opds+json") invalid()
            try { GutenbergUrls.nextToken(it.string("href", true)!!, query, currentPage) } catch (_: IllegalArgumentException) { invalid() }
        }
        return SearchPage(publications, next)
    }
    fun detail(bytes: ByteArray, expected: PublicationId, cancelled: () -> Unit = {}): Publication {
        require(expected.sourceId == GUTENBERG_ID); GutenbergUrls.identifier(expected.localId)
        return publication(document(bytes, cancelled)).also { if (it.id != expected) invalid() }
    }
    private fun publication(root: JsonObject): Publication {
        val metadata = objectValue(root["metadata"] ?: invalid())
        if (metadata.string("@type") != "http://schema.org/Book") invalid()
        val local = metadata.string("identifier", true)?.let(GutenbergUrls::localId) ?: invalid()
        val id = PublicationId(GUTENBERG_ID, local)
        val title = metadata.string("title", true, 2048)!!
        fun strings(key: String, max: Int): List<String> {
            val value = metadata[key] ?: return emptyList()
            val values = if (value is JsonArray) value else listOf(value)
            if (values.size > 64) invalid()
            return values.map { val p = it as? JsonPrimitive ?: invalid()
                if (!p.isString || p.content.isBlank() || p.content.length > max) invalid(); p.content }.distinct()
        }
        val author = metadata["author"]
        val authors = when (author) { null -> emptyList(); is JsonArray -> author; else -> listOf(author) }
        if (authors.size > 64) invalid()
        val names = authors.map { objectValue(it).string("name", true, 512)!! }.distinct()
        val links = links(root)
        val self = links.singleOrNull { "self" in relations(it) } ?: invalid()
        if (self.string("href") != GutenbergUrls.publication(local) || self.string("type") != "application/opds-publication+json") invalid()
        links.forEach { link ->
            val rel = relations(link)
            if (rel.any { it.startsWith("http://opds-spec.org/acquisition") }) {
                // Unknown/malformed acquisition relations are not permission.
                if (rel != listOf("http://opds-spec.org/acquisition/open-access")) invalid()
                link.string("type", true)
                link.string("href", true, 2048)
                // RWPM defines optional `size`, not `length`. Gutenberg's `length`
                // extension (including absence/unsupported values) is never byte authority.
                // Catalog-only: quarantine all delivery metadata, even descriptive EPUB.
                // No delivery URL, size, or resource reference escapes this parser.
            }
        }
        return Publication(id, title, PublicationType.BOOK, names, emptyList(), strings("language", 64),
            GutenbergUrls.canonical(local), metadata.string("rights")?.takeIf { it.isNotBlank() })
        // Rights embedded in a prose description are deliberately not promoted to a license field.
    }
}
