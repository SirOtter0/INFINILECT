// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import android.util.Xml
import java.io.ByteArrayInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** Android's built-in pull parser; no external XML library or resolver is installed. */
internal actual fun platformOpdsXmlReader(bytes: ByteArray): OpdsXmlReader =
    AndroidOpdsXmlReader(bytes, Xml.newPullParser())

internal class AndroidOpdsXmlReader(bytes: ByteArray, private val reader: XmlPullParser) : OpdsXmlReader {
    private val input = ByteArrayInputStream(bytes)
    init {
        try {
            reader.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            reader.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            reader.setInput(input, null)
        } catch (error: Exception) { input.close(); throw InvalidOpdsException(error) }
    }
    override fun hasNext() = reader.eventType != XmlPullParser.END_DOCUMENT
    override fun next(): OpdsXmlEvent = try {
        when (reader.nextToken()) {
            XmlPullParser.START_TAG -> OpdsXmlEvent.START
            XmlPullParser.END_TAG -> OpdsXmlEvent.END
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> OpdsXmlEvent.TEXT
            XmlPullParser.DOCDECL -> OpdsXmlEvent.PROHIBITED
            // nextToken exposes standard XML references that StAX reports as text.
            // Never accept user-defined entities; their DTD is rejected first.
            XmlPullParser.ENTITY_REF -> if (standardReference(reader.name, reader.text))
                OpdsXmlEvent.TEXT else OpdsXmlEvent.PROHIBITED
            else -> OpdsXmlEvent.OTHER
        }
    } catch (error: XmlPullParserException) { throw InvalidOpdsException(error) }
      catch (error: java.io.IOException) { throw InvalidOpdsException(error) }
    override val localName: String get() = reader.name ?: ""
    override val namespaceURI: String? get() = reader.namespace
    override val text: String get() = reader.text ?: ""
    override val attributeCount get() = reader.attributeCount
    override val namespaceCount get() = reader.getNamespaceCount(reader.depth) - reader.getNamespaceCount(reader.depth - 1)
    // XmlPull's null namespace is a wildcard; empty string means unqualified.
    override fun getAttributeValue(namespace: String?, name: String): String? = reader.getAttributeValue(namespace ?: "", name)
    override fun getAttributeValue(index: Int): String = reader.getAttributeValue(index)
    override fun close() = input.close()
}

private fun standardReference(name: String?, text: String?): Boolean {
    val predefined = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'", "quot" to "\"")
    predefined[name]?.let { return it == text }
    val code = when {
        name?.matches(Regex("#[0-9]{1,7}")) == true -> name.drop(1).toIntOrNull()
        name?.matches(Regex("#x[0-9a-fA-F]{1,6}")) == true -> name.drop(2).toIntOrNull(16)
        else -> null
    } ?: return false
    val valid = code == 9 || code == 10 || code == 13 || code in 0x20..0xd7ff ||
        code in 0xe000..0xfffd || code in 0x10000..0x10ffff
    return valid && String(Character.toChars(code)) == text
}
