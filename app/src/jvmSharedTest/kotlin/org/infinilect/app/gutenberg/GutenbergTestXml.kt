// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

internal expect fun testGutenbergXml(bytes: ByteArray): OpdsXmlReader
internal val testGutenbergId = org.infinilect.core.PublicationId(GUTENBERG_ID, "84")
internal fun rdfFixture(size: Long = 12, url: String = "https://www.gutenberg.org/files/84/84-0.txt",
    mime: String = GUTENBERG_TEXT_MIME, id: String = "84", extra: String = ""): String = """
<rdf:RDF xml:base="http://www.gutenberg.org/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
 xmlns:pgterms="http://www.gutenberg.org/2009/pgterms/" xmlns:dcterms="http://purl.org/dc/terms/">
 <pgterms:ebook rdf:about="ebooks/$id">
 <dcterms:title>A Book &amp; 🦦</dcterms:title><dcterms:rights>Public domain in the USA.</dcterms:rights>
 <dcterms:creator><pgterms:agent><pgterms:name>First Author</pgterms:name></pgterms:agent></dcterms:creator>
 <dcterms:creator><pgterms:agent><pgterms:name>Second Author</pgterms:name></pgterms:agent></dcterms:creator>
 <dcterms:language><rdf:Description><rdf:value>en</rdf:value></rdf:Description></dcterms:language>
 <dcterms:hasFormat><pgterms:file rdf:about="$url"><dcterms:isFormatOf rdf:resource="ebooks/$id"/>
 <dcterms:extent>$size</dcterms:extent><dcterms:modified>2026-10-04</dcterms:modified>
 <dcterms:format><rdf:Description><rdf:value>$mime</rdf:value></rdf:Description></dcterms:format>
 </pgterms:file></dcterms:hasFormat>$extra
 </pgterms:ebook><cc:Work xmlns:cc="http://web.resource.org/cc/"><cc:license rdf:resource="https://creativecommons.org/publicdomain/zero/1.0/"/></cc:Work>
</rdf:RDF>"""
internal fun catalogFixture() = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
 <id>https://www.gutenberg.org/ebooks/84.opds</id><title>Catalog title</title><author><name>Author</name></author>
 </entry></feed>"""
