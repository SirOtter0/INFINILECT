// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import java.lang.reflect.Proxy
import kotlin.test.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.infinilect.core.PublicationFormat

/** Tests the Android token adapter with deterministic tokens, not the host's stub Xml.newPullParser. */
class AndroidOpdsXmlReaderTest {
    private data class Token(val event: Int, val name: String = "", val text: String = "", val depth: Int = 0,
        val attributes: Map<String, String> = emptyMap())
    private class Pull(val tokens: List<Token>, val fail: Boolean = false) {
        var index = -1
        val features = mutableMapOf<String, Boolean>()
        val attributeNamespaces = mutableListOf<String?>()
        private val token get() = tokens.getOrNull(index) ?: Token(XmlPullParser.START_DOCUMENT)
        val reader = Proxy.newProxyInstance(XmlPullParser::class.java.classLoader, arrayOf(XmlPullParser::class.java)) { _, method, args ->
            when (method.name) {
                "setFeature" -> { features[args[0] as String] = args[1] as Boolean; null }
                "setInput" -> null
                "getEventType" -> if (index >= tokens.size) XmlPullParser.END_DOCUMENT else token.event
                "nextToken" -> {
                    if (fail) throw XmlPullParserException("invalid fixture XML")
                    index++; if (index >= tokens.size) XmlPullParser.END_DOCUMENT else token.event
                }
                "getName" -> token.name
                "getText" -> token.text
                "getNamespace" -> "http://www.w3.org/2005/Atom"
                "getDepth" -> token.depth
                "getNamespaceCount" -> 0
                "getAttributeCount" -> token.attributes.size
                "getAttributeValue" -> if (args.size == 1) token.attributes.values.elementAt(args[0] as Int) else {
                    attributeNamespaces += args[0] as String?
                    token.attributes[args[1] as String]
                }
                else -> error("Unexpected parser method: ${method.name}")
            }
        } as XmlPullParser
    }
    private fun start(name: String, depth: Int, attributes: Map<String,String> = emptyMap()) = Token(XmlPullParser.START_TAG, name, depth = depth, attributes = attributes)
    private fun end(name: String, depth: Int) = Token(XmlPullParser.END_TAG, name, depth = depth)
    private fun text(value: String) = Token(XmlPullParser.TEXT, text = value)
    private fun document(title: List<Token>) = listOf(start("feed",1),start("entry",2),start("id",3),
        text("https://www.gutenberg.org/ebooks/11.opds"),end("id",3),start("title",3)) + title + listOf(end("title",3),
        start("link",3,mapOf("rel" to "http://opds-spec.org/acquisition","type" to "text/plain; charset=utf-8","href" to "https://www.gutenberg.org/files/11/11.txt")),
        end("link",3),end("entry",2),end("feed",1))
    private fun parse(pull: Pull) = GutenbergOpdsParser { AndroidOpdsXmlReader(it,pull.reader) }
        .parse(byteArrayOf(),GutenbergUrls.search("books"),"books")

    @Test fun sharesPublicationMappingAndExactUnqualifiedAttributes() {
        val pull = Pull(document(listOf(text("Real title"))))
        val book = parse(pull).publications.single()
        assertEquals("11",book.id.localId); assertEquals("gutenberg",book.id.sourceId.value)
        assertEquals("Real title",book.title)
        assertEquals(PublicationFormat.TEXT,book.resources.single().format)
        assertEquals(true,pull.features[XmlPullParser.FEATURE_PROCESS_NAMESPACES])
        assertEquals(false,pull.features[XmlPullParser.FEATURE_PROCESS_DOCDECL])
        assertTrue(pull.attributeNamespaces.filterNotNull().all { it == "" || it == "http://www.w3.org/XML/1998/namespace" })
        assertTrue("" in pull.attributeNamespaces) // null would mean a wildcard in XmlPull.
    }
    @Test fun predefinedAndNumericReferencesProduceOnlyVerifiedCharacters() {
        val pull = Pull(document(listOf(text("A "),Token(XmlPullParser.ENTITY_REF,"amp","&"),text(" "),
            Token(XmlPullParser.ENTITY_REF,"#x1F9A6","🦦"))))
        assertEquals("A & 🦦",parse(pull).publications.single().title)
    }
    @Test fun documentTypeIsRejectedBeforeEntryOrEntityConsumption() {
        val pull = Pull(listOf(Token(XmlPullParser.DOCDECL,text = "external untrusted DTD")) + document(listOf(text("Title"))))
        assertFailsWith<InvalidOpdsException> { parse(pull) }
        assertEquals(0,pull.index)
    }
    @Test fun unknownEntitiesAndInvalidOrMismatchingReferencesAreRejected() {
        for ((name,value) in listOf("external" to "leaked", "#0" to "\u0000", "amp" to "wrong", "#x110000" to "wrong")) {
            assertFailsWith<InvalidOpdsException> { parse(Pull(document(listOf(Token(XmlPullParser.ENTITY_REF,name,value))))) }
        }
    }
    @Test fun xmlParserFailureIsWrappedInSafeSourceError() {
        val error = assertFailsWith<InvalidOpdsException> { parse(Pull(emptyList(),fail = true)) }
        assertEquals("Project Gutenberg returned an invalid or unsupported catalog.",error.message)
        assertIs<XmlPullParserException>(error.cause)
    }
}
