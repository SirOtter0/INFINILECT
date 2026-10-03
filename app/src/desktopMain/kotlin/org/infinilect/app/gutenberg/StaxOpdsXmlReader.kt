// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

internal actual fun platformOpdsXmlReader(bytes: ByteArray): OpdsXmlReader {
    val factory = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ ->
            throw XMLStreamException("External XML resolution disabled")
        }
    }
    val reader = try { factory.createXMLStreamReader(ByteArrayInputStream(bytes)) }
    catch (error: XMLStreamException) { throw InvalidOpdsException(error) }
    return object : OpdsXmlReader {
        override fun hasNext() = try { reader.hasNext() }
        catch (error: XMLStreamException) { throw InvalidOpdsException(error) }
        override fun next(): OpdsXmlEvent = try {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> OpdsXmlEvent.START
                XMLStreamConstants.END_ELEMENT -> OpdsXmlEvent.END
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> OpdsXmlEvent.TEXT
                XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> OpdsXmlEvent.PROHIBITED
                else -> OpdsXmlEvent.OTHER
            }
        } catch (error: XMLStreamException) { throw InvalidOpdsException(error) }
        override val localName get() = reader.localName
        override val namespaceURI get() = reader.namespaceURI
        override val text get() = reader.text
        override val attributeCount get() = reader.attributeCount
        override val namespaceCount get() = reader.namespaceCount
        override fun getAttributeValue(namespace: String?, name: String) = reader.getAttributeValue(namespace, name)
        override fun getAttributeValue(index: Int): String = reader.getAttributeValue(index)
        override fun close() = reader.close()
    }
}
