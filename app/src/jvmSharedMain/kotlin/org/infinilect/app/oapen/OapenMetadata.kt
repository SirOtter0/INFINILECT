// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.*
import org.xml.sax.ext.DefaultHandler2
import org.infinilect.app.discovery.*
import org.infinilect.core.*

internal const val OAPEN_METADATA_LIMIT = 1024 * 1024
internal val OAPEN_SOURCE_ID = SourceId("oapen")
internal class InvalidOapenMetadata : Exception("Invalid OAPEN metadata")
private const val OAI = "http://www.openarchives.org/OAI/2.0/"
private const val XOAI = "http://www.lyncode.com/xoai"
private class Node(val ns: String, val name: String, val label: String?, val status: String?, val code: String?) {
    val children = mutableListOf<Node>()
    val text = StringBuilder()
    fun named(label: String) = children.filter { it.ns == XOAI && it.name == "element" && it.label == label }
    fun fields(label: String) = children.filter { it.ns == XOAI && it.name == "field" && it.label == label }.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
}

/** The publicly observed XOAI GetRecord contract. No REST/DSpace schema guessing,
 * bitstream URL authority, HTML rendering, harvesting or XML external resources. */
internal fun oapenMetadata(bytes: ByteArray, expected: PublicationId, check: () -> Unit = {}): PublicationBibliography? {
    require(expected.sourceId == OAPEN_SOURCE_ID && oapenHandle(expected.localId) == expected.localId)
    fun invalid(): Nothing = throw InvalidOapenMetadata()
    if (bytes.size > OAPEN_METADATA_LIMIT) invalid()
    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { invalid() }
    val stack = ArrayDeque<Node>(); var root: Node? = null; var count = 0
    val handler = object : DefaultHandler2() {
        override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
            check()
            if (++count > 4096 || stack.size >= 32 || attrs.length > 16) invalid()
            for (i in 0 until attrs.length) if (attrs.getValue(i).length > 16_384) invalid()
            val node = Node(uri, local, attrs.getValue("name"), attrs.getValue("status"), attrs.getValue("code"))
            if (stack.isEmpty()) { if (root != null) invalid(); root = node } else stack.last().children += node
            stack.addLast(node)
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            check()
            stack.lastOrNull()?.let { if (it.text.length + length > 16_384) invalid(); it.text.append(ch, start, length) }
        }
        override fun endElement(uri: String, local: String, qName: String) { check(); stack.removeLast() }
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { invalid() }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { invalid() }
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource { invalid() }
        override fun error(e: SAXParseException) { invalid() }
        override fun fatalError(e: SAXParseException) { invalid() }
    }
    val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }.newSAXParser().xmlReader
    reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
    reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
    reader.contentHandler = handler; reader.entityResolver = handler; reader.errorHandler = handler
    try { reader.parse(InputSource(StringReader(text))) }
    catch (e: SAXException) {
        var cause: Throwable? = e
        repeat(8) { if (cause is kotlinx.coroutines.CancellationException) throw cause as kotlinx.coroutines.CancellationException; cause = cause?.cause }
        invalid()
    }
    check()
    val document = root ?: invalid()
    if (document.ns != OAI || document.name != "OAI-PMH") invalid()
    val error = document.children.singleOrNull { it.ns == OAI && it.name == "error" }
    if (error != null) {
        // OAI error codes are attributes, not HTTP statuses.
        if (error.code == "idDoesNotExist") return null
        invalid()
    }
    fun Node.child(name: String) = children.singleOrNull { it.ns == OAI && it.name == name } ?: invalid()
    val record = document.child("GetRecord").child("record")
    val header = record.child("header")
    if (header.child("identifier").text.toString().trim() != "oai:library.oapen.org:${expected.localId}") invalid()
    if (header.status == "deleted") return null
    val metadata = record.child("metadata").children.singleOrNull { it.ns == XOAI && it.name == "metadata" } ?: invalid()
    fun values(vararg path: String): List<String> {
        var nodes = listOf(metadata)
        for (part in path) nodes = nodes.flatMap { it.named(part) }
        return nodes.flatMap { node -> node.fields("value") + node.children.flatMap { it.fields("value") } }.distinct()
    }
    fun first(vararg path: String) = values(*path).firstOrNull()
    val title = first("dc", "title")?.take(512) ?: invalid()
    val streams = metadata.named("bundles").flatMap { it.named("bundle") }.filter { it.fields("name") == listOf("ORIGINAL") }
        .flatMap { it.named("bitstreams") }.flatMap { it.named("bitstream") }
    if (streams.size > 32) invalid()
    val rights = (values("dc", "rights") + streams.flatMap { it.fields("rights") + it.fields("rightsuri") }).distinct()
        .joinToString("\n").take(16_384).ifBlank { null }
    val publication = Publication(expected, title, when (first("dc", "type")) { "book" -> PublicationType.BOOK; "chapter" -> PublicationType.ARTICLE; else -> PublicationType.DOCUMENT },
        authors = values("dc", "contributor", "author").take(32).map { it.take(512) },
        languages = values("dc", "language").take(16).map { it.take(64) }, sourceUrl = oapenPage(expected), rights = rights)
    val linkedPublisher = metadata.named("linkedItemsMetadata").flatMap { it.named("oapen.relation.isPublishedBy") }
        .flatMap { it.named("publisher.name") }.flatMap { it.fields("value") }.firstOrNull()
    return PublicationBibliography(publication,
        first("dc", "description", "abstract")?.let(::catalogPlainText),
        (values("dc", "subject", "classification") + values("dc", "subject", "other")).distinct().take(32).map { it.take(512) },
        (first("dc", "publisher") ?: linkedPublisher ?: first("oapen", "imprint"))?.take(512), first("dc", "date", "issued")?.take(64),
        (values("oapen", "identifier", "doi") + values("oapen", "identifier", "isbn")).take(16).map { it.take(256) },
        streams.flatMap { it.fields("format") }.distinct().take(16).map { it.take(128) })
}
