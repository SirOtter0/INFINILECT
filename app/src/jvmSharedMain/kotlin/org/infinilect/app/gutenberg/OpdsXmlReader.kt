// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

internal enum class OpdsXmlEvent { START, END, TEXT, PROHIBITED, OTHER }

/** Token seam only; limits, namespaces, fields and publication mapping are shared. */
internal interface OpdsXmlReader {
    fun hasNext(): Boolean
    fun next(): OpdsXmlEvent
    val localName: String
    val namespaceURI: String?
    val text: String
    val attributeCount: Int
    val namespaceCount: Int
    fun getAttributeValue(namespace: String?, name: String): String?
    fun getAttributeValue(index: Int): String
    fun close()
}

internal expect fun platformOpdsXmlReader(bytes: ByteArray): OpdsXmlReader
