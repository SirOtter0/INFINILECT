// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import org.infinilect.app.reader.EpubException
import org.xml.sax.*
import org.xml.sax.ext.DefaultHandler2
internal data class XmlName(val namespace: String, val local: String)

internal sealed interface EpubXmlContent {
    class Text(val value: StringBuilder) : EpubXmlContent
    class Element(val value: EpubXmlNode) : EpubXmlContent
}

internal class EpubXmlNode(val name: XmlName, val attributes: Map<XmlName, String>) {
    val children = mutableListOf<EpubXmlNode>()
    val text = StringBuilder()
    val content = mutableListOf<EpubXmlContent>()
    fun attr(name: String) = attributes[XmlName("", name)]
    fun children(namespace: String, local: String) = children.filter {
        it.name==XmlName(namespace, local)
    }
}

/** Namespace-aware bounded XML1.0/UTF8 subset. No DTDs/entities/PI/XInclude or URL base.
* Android API26 supports SAX's lexical handler and external-entity flags (AOSP ExpatReader).
* Required hardening setup fails closed if a provider cannot support it.
*/
internal fun parseEpubXml(bytes: ByteArray, limits: EpubLimits, job: Job?): EpubXmlNode {
    if (bytes.size>limits.xmlBytes) limit()
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
    }
    catch (_: Exception) {
        invalid()
    }
    requireEpub(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true))
    if (text.startsWith("<?xml")) {
        val end = text.indexOf("?>")
        requireEpub(end in 5..256)
        val declaration = text.substring(0, end)
        val encoding = Regex("encoding\\s*=\\s*['\"]([^'\"]+)['\"]").find(declaration)?.groupValues?.get(1)
        requireEpub(encoding==null || encoding.equals("UTF-8", true))
        requireEpub(!Regex("version\\s*=\\s*['\"](?!1\\.0['\"]).*").containsMatchIn(declaration))
    }
    val stack = ArrayList<EpubXmlNode>()
    var root:EpubXmlNode?=null
    var nodes = 0
    var textUnits = 0
    val handler = object: DefaultHandler2() {
        override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
            job?.ensureActive()
            if (++nodes>20_000 || stack.size>=32 || attributes.length>32) limit()
            requireEpub(local.length in 1..128 && uri.length<=256)
            val attrs = linkedMapOf<XmlName, String>()
            for (i in 0 until attributes.length) {
                val value = attributes.getValue(i)
                val name = XmlName(attributes.getURI(i), attributes.getLocalName(i))
                if (value.length>8192) limit()
                requireEpub(name.local.length in 1..128 && name.namespace.length<=256 && attrs.put(name, value)==null)
                requireEpub(name!=XmlName("http://www.w3.org/XML/1998/namespace", "base"))
            }
            requireEpub(uri!="http://www.w3.org/2001/XInclude")
            val node = EpubXmlNode(XmlName(uri, local), attrs)
            if (stack.isEmpty()) {
                requireEpub(root==null)
                root = node
            }
            else {
                stack.last().children.add(node)
                stack.last().content.add(EpubXmlContent.Element(node))
            }
            stack.add(node)
        }
        override fun endElement(uri: String, local: String, qName: String) {
            job?.ensureActive()
            requireEpub(stack.isNotEmpty())
            stack.removeAt(stack.lastIndex)
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            job?.ensureActive()
            textUnits+=length
            if (textUnits>limits.xmlBytes) limit()
            if (stack.isNotEmpty()) {
                val out = stack.last().text
                if (out.length+length>8192) limit()
                out.append(ch, start, length)
                // Coalesce SAX character callbacks: bounded by element transitions, not bytes/events.
                val ordered = stack.last().content
                val part = (ordered.lastOrNull() as? EpubXmlContent.Text)
                    ?: EpubXmlContent.Text(StringBuilder()).also(ordered::add)
                part.value.append(ch, start, length)
            }
        }
        override fun processingInstruction(target: String, data: String) {
            invalid()
        }
        override fun startDTD(name: String, publicId: String?, systemId: String?) {
            invalid()
        }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
            invalid()
        }
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource {
            invalid()
        }
        override fun error(e: SAXParseException) {
            invalid()
        }
        override fun fatalError(e: SAXParseException) {
            invalid()
        }
    }
    val reader = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
    }
    .newSAXParser().xmlReader
    reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
    reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
    reader.contentHandler = handler
    reader.entityResolver = handler
    reader.errorHandler = handler
    // Parse the strict-decoded string: no encoding sniffing or second byte decoding can bypass guards.
    try {
        reader.parse(InputSource(StringReader(text)))
    }
    catch (error: SAXException) {
        // SAX providers may wrap a handler exception. Preserve cancellation and
        // our bounded-input category without exposing provider exception text.
        var cause: Throwable?  = error
        repeat(8) {
            when (val current = cause) {
                is CancellationException -> throw current
                is EpubException -> throw current
                else -> cause = current?.cause
            }
        }
        invalid()
    }
    requireEpub(stack.isEmpty())
    return root ?: invalid()
}
internal fun EpubXmlNode.walk(): Sequence<EpubXmlNode>  = sequence {
    val pending = ArrayDeque<EpubXmlNode>()
    pending.add(this@walk)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        yield(node)
        node.children.forEach(pending::add)
    }
}
