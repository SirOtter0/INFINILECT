// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import org.infinilect.core.*

private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
private const val PG = "http://www.gutenberg.org/2009/pgterms/"
private const val DC = "http://purl.org/dc/terms/"
private const val XML = "http://www.w3.org/XML/1998/namespace"

/** The documented per-ebook RDF subset, independent of OPDS catalog transport. */
internal class GutenbergRdfParser(private val openXml: (ByteArray) -> OpdsXmlReader = ::platformOpdsXmlReader) {
    fun parse(bytes: ByteArray, expected: PublicationId, checkCancellation: () -> Unit = {}): GutenbergBook {
        require(expected.sourceId == GUTENBERG_ID)
        val id = GutenbergAcquisition.identifier(expected.localId)
        if (bytes.size > MAX_FEED_BYTES) throw InvalidOpdsException()
        val reader = openXml(bytes)
        val path = mutableListOf<Pair<String?, String>>()
        var events = 0; var books = 0; var files = 0; var seenRoot = false
        var title: String? = null; var rights: String? = null
        val authors = mutableListOf<String>(); val languages = mutableListOf<String>()
        val candidates = mutableListOf<GutenbergTextFile>()
        var file: File? = null; var capture: Capture? = null
        fun at(vararg parts: Pair<String, String>) = path == parts.toList()
        try {
            while (reader.hasNext()) {
                if (++events > 100_000) throw InvalidOpdsException()
                if (events % 256 == 0) checkCancellation()
                when (reader.next()) {
                    OpdsXmlEvent.PROHIBITED -> throw InvalidOpdsException()
                    OpdsXmlEvent.START -> {
                        capture?.nested = true
                        path += reader.namespaceURI to reader.localName
                        if (path.size > 32 || reader.attributeCount + reader.namespaceCount > 16) throw InvalidOpdsException()
                        for (i in 0 until reader.attributeCount) if (reader.getAttributeValue(i).length > 16_384) throw InvalidOpdsException()
                        val base = reader.getAttributeValue(XML, "base")
                        if (base != null && (path.size != 1 || base !in listOf("http://www.gutenberg.org/", "https://www.gutenberg.org/"))) throw InvalidOpdsException()
                        if (path.size == 1) {
                            if (seenRoot || !at(RDF to "RDF")) throw InvalidOpdsException()
                            seenRoot = true
                        }
                        if (at(RDF to "RDF", PG to "ebook")) {
                            if (++books != 1 || reader.getAttributeValue(RDF, "about") !in listOf("ebooks/$id", GutenbergAcquisition.canonical(id), "http://www.gutenberg.org/ebooks/$id")) throw InvalidOpdsException()
                        }
                        if (at(RDF to "RDF", PG to "ebook", DC to "hasFormat", PG to "file")) {
                            if (++files > 256) throw InvalidOpdsException()
                            file = File(reader.getAttributeValue(RDF, "about"))
                        }
                        if (at(RDF to "RDF", PG to "ebook", DC to "hasFormat", PG to "file", DC to "isFormatOf")) {
                            if (file!!.belongs != null) throw InvalidOpdsException()
                            file.belongs = reader.getAttributeValue(RDF, "resource")
                        }
                        val field = when {
                            at(RDF to "RDF", PG to "ebook", DC to "title") -> "title"
                            at(RDF to "RDF", PG to "ebook", DC to "rights") -> "rights"
                            at(RDF to "RDF", PG to "ebook", DC to "creator", PG to "agent", PG to "name") -> "author"
                            at(RDF to "RDF", PG to "ebook", DC to "language", RDF to "Description", RDF to "value") -> "language"
                            at(RDF to "RDF", PG to "ebook", DC to "hasFormat", PG to "file", DC to "extent") -> "size"
                            at(RDF to "RDF", PG to "ebook", DC to "hasFormat", PG to "file", DC to "format", RDF to "Description", RDF to "value") -> "format"
                            else -> null
                        }
                        if (field != null) {
                            if (capture != null) throw InvalidOpdsException()
                            capture = Capture(field, path.size)
                        }
                    }
                    OpdsXmlEvent.TEXT -> capture?.let {
                        if (it.text.length + reader.text.length > 16_384) throw InvalidOpdsException()
                        it.text.append(reader.text)
                    }
                    OpdsXmlEvent.END -> {
                        capture?.takeIf { it.depth == path.size }?.let {
                            if (it.nested) throw InvalidOpdsException()
                            val raw = it.text.toString(); val value = raw.trim()
                            when (it.field) {
                                "title" -> { if (title != null || value.isBlank()) throw InvalidOpdsException(); title = value }
                                "rights" -> { if (rights != null) throw InvalidOpdsException(); rights = raw.takeIf(String::isNotBlank) }
                                "author" -> if (value.isNotEmpty()) authors += value
                                "language" -> if (Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*").matches(value)) languages += value.lowercase()
                                "size" -> { if (file!!.size != null) throw InvalidOpdsException(); file.size = value.toLongOrNull() ?: throw InvalidOpdsException() }
                                "format" -> file!!.formats += value
                            }
                            capture = null
                        }
                        if (at(RDF to "RDF", PG to "ebook", DC to "hasFormat", PG to "file")) {
                            val f = file!!
                            if (f.formats.size == 1 && GutenbergAcquisition.utf8(f.formats.single()) && f.belongs in listOf("ebooks/$id", GutenbergAcquisition.canonical(id))) {
                                val url = f.url?.let { GutenbergAcquisition.location(it, id) }
                                val size = f.size
                                if (url != null && size != null && size > 0) candidates += GutenbergTextFile(url, size)
                            }
                            file = null
                        }
                        if (path.isEmpty()) throw InvalidOpdsException()
                        path.removeAt(path.lastIndex)
                    }
                    OpdsXmlEvent.OTHER -> Unit
                }
            }
            if (!seenRoot || books != 1 || path.isNotEmpty()) throw InvalidOpdsException()
            checkCancellation()
            if (candidates.groupBy { it.url }.values.any { it.distinct().size > 1 }) throw InvalidOpdsException()
            // Explicit UTF-8 direct file avoids redirects. Ties use stable URL order, not feed order.
            val selected = candidates.sortedWith(compareBy<GutenbergTextFile> { if ("/files/" in it.url) 0 else if ("/cache/" in it.url) 1 else 2 }.thenBy { it.url }).firstOrNull()
            return GutenbergBook(Publication(expected, title ?: throw InvalidOpdsException(), PublicationType.BOOK,
                authors.distinct(), if (selected == null) emptyList() else listOf(GutenbergAcquisition.resource(expected)),
                languages.distinct(), GutenbergAcquisition.canonical(id), rights), selected)
        } catch (error: IllegalArgumentException) { throw InvalidOpdsException(error) }
        finally { reader.close() }
    }
    private class File(val url: String?) { var belongs: String? = null; var size: Long? = null; val formats = mutableListOf<String>() }
    private class Capture(val field: String, val depth: Int) { val text = StringBuilder(); var nested = false }
}
