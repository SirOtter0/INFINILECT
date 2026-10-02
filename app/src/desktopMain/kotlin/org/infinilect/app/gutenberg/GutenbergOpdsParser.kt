// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import org.infinilect.app.search.SearchException
import org.infinilect.core.Publication
import org.infinilect.core.PublicationFormat
import org.infinilect.core.PublicationId
import org.infinilect.core.PublicationResource
import org.infinilect.core.PublicationType
import org.infinilect.core.SearchPage
import org.infinilect.core.SourceId

internal class InvalidOpdsException(cause: Throwable? = null) :
    SearchException("Project Gutenberg returned an invalid or unsupported catalog.", cause)

private const val ATOM = "http://www.w3.org/2005/Atom"
private const val DC_TERMS = "http://purl.org/dc/terms/"
private const val ACQUISITION = "http://opds-spec.org/acquisition"

/** Gutenberg's small Atom/OPDS subset, confined to desktop/JDK; not a universal OPDS engine. */
internal class GutenbergOpdsParser {
    fun parse(bytes: ByteArray, pageUrl: String, query: String, checkCancellation: () -> Unit = {}): SearchPage {
        if (bytes.size > MAX_FEED_BYTES) throw InvalidOpdsException()
        val factory = XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> throw XMLStreamException("External XML resolution disabled") }
        }
        val reader = try { factory.createXMLStreamReader(ByteArrayInputStream(bytes)) }
            catch (error: XMLStreamException) { throw InvalidOpdsException(error) }
        var depth = 0
        var events = 0
        var entries = 0
        var seenFeed = false
        var entry: Entry? = null
        var capture: Capture? = null
        var next: String? = null
        val publications = mutableListOf<Publication>()
        try {
            while (reader.hasNext()) {
                if (++events > 100_000) throw InvalidOpdsException()
                if (events % 256 == 0) checkCancellation()
                when (reader.next()) {
                    XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> throw InvalidOpdsException()
                    XMLStreamConstants.START_ELEMENT -> {
                        if (++depth > 32) throw InvalidOpdsException()
                        if (reader.getAttributeValue(XMLConstants.XML_NS_URI, "base") != null) throw InvalidOpdsException()
                        if (reader.attributeCount + reader.namespaceCount > 16) throw InvalidOpdsException()
                        for (i in 0 until reader.attributeCount) {
                            if (reader.getAttributeValue(i).length > 16_384) throw InvalidOpdsException()
                        }
                        val name = reader.localName
                        val ns = reader.namespaceURI
                        if (depth == 1) {
                            if (seenFeed || name != "feed" || ns != ATOM) throw InvalidOpdsException()
                            seenFeed = true
                        }
                        capture?.hasChildren = true
                        if (depth == 2 && ns == ATOM && name == "entry") {
                            if (++entries > 100) throw InvalidOpdsException()
                            entry = Entry()
                        }
                        if (ns == ATOM && name == "link" && depth in 2..3) {
                            val rel = reader.getAttributeValue(null, "rel") ?: "alternate"
                            val type = reader.getAttributeValue(null, "type") ?: ""
                            val href = reader.getAttributeValue(null, "href")
                            if (href != null) {
                                if (depth == 2 && rel == "next" && type.substringBefore(';').trim().lowercase() == "application/atom+xml") {
                                    if (next != null) throw InvalidOpdsException()
                                    next = GutenbergUrls.nextToken(href, pageUrl, query)
                                } else if (depth == 3 && entry != null && (rel == ACQUISITION || rel.startsWith("$ACQUISITION/"))) {
                                    entry.links += Link(href, type)
                                }
                            }
                        }
                        val field = when {
                            entry == null -> null
                            depth == 3 && ns == ATOM && name in setOf("id", "title", "rights", "content") &&
                                (reader.getAttributeValue(null, "type") ?: "text") == "text" -> name
                            depth == 3 && ns == DC_TERMS && name == "language" -> "language"
                            depth == 4 && ns == ATOM && name == "name" &&
                                entry.inAuthor -> "author"
                            else -> null
                        }
                        if (depth == 3 && ns == ATOM && name == "author") entry?.inAuthor = true
                        if (field != null) capture = Capture(field, depth)
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> capture?.let {
                        if (it.text.length + reader.textLength > 16_384) throw InvalidOpdsException()
                        it.text.append(reader.text)
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        capture?.takeIf { it.depth == depth }?.let {
                            if (!it.hasChildren) entry?.accept(it.field, it.text.toString().trim())
                            capture = null
                        }
                        if (depth == 3 && reader.namespaceURI == ATOM && reader.localName == "author") entry?.inAuthor = false
                        if (depth == 2 && reader.namespaceURI == ATOM && reader.localName == "entry") {
                            entry!!.publication(pageUrl)?.let { publication ->
                                if (publications.any { it.id == publication.id }) throw InvalidOpdsException()
                                publications += publication
                            }
                            entry = null
                        }
                        depth--
                    }
                }
            }
            if (!seenFeed || depth != 0) throw InvalidOpdsException()
            checkCancellation()
            return SearchPage(publications, next)
        } catch (error: XMLStreamException) {
            throw InvalidOpdsException(error)
        } catch (error: IllegalArgumentException) {
            throw InvalidOpdsException(error)
        } finally {
            reader.close()
        }
    }

    private data class Capture(val field: String, val depth: Int, val text: StringBuilder = StringBuilder(), var hasChildren: Boolean = false)
    private data class Link(val href: String, val type: String)
    private class Entry {
        var id: String? = null
        var title: String? = null
        var rights: String? = null
        var authorSummary: String? = null
        var inAuthor = false
        val authors = mutableListOf<String>()
        val languages = mutableListOf<String>()
        val links = mutableListOf<Link>()

        fun accept(field: String, value: String) {
            if (value.isEmpty()) return
            when (field) {
                "id" -> { if (id != null) throw InvalidOpdsException(); id = value }
                "title" -> { if (title != null) throw InvalidOpdsException(); title = value }
                "rights" -> rights = value
                "content" -> authorSummary = value
                "author" -> authors += value
                "language" -> if (Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*").matches(value)) languages += value.lowercase()
            }
        }

        fun publication(pageUrl: String): Publication? {
            val rawId = id ?: throw InvalidOpdsException()
            // Gutenberg search also includes author/subject navigation entries. Never turn them or foreign IDs into books.
            val localId = GutenbergUrls.localId(rawId) ?: return null
            val publicationId = PublicationId(SourceId("gutenberg"), localId)
            val resources = links.mapNotNull { link ->
                val type = link.type.substringBefore(';').trim().lowercase()
                val format = when (type) {
                    "application/epub+zip" -> PublicationFormat.EPUB
                    "application/pdf" -> PublicationFormat.PDF
                    "text/plain" -> PublicationFormat.TEXT
                    "text/html" -> PublicationFormat.HTML
                    else -> return@mapNotNull null
                }
                val url = GutenbergUrls.resolve(link.href, pageUrl) ?: return@mapNotNull null
                PublicationResource(publicationId, url, format, link.type, revision = null)
            }.distinctBy { it.key }
            return Publication(
                id = publicationId,
                title = title ?: throw InvalidOpdsException(),
                type = PublicationType.BOOK,
                authors = authors.distinct().ifEmpty { listOfNotNull(authorSummary) },
                languages = languages.distinct(),
                sourceUrl = "https://www.gutenberg.org/ebooks/$localId",
                rights = rights,
                resources = resources,
            )
        }
    }
}
