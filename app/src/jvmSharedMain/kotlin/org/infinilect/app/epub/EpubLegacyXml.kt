// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

/** Recognize inert standard declarations, never parse a DTD or resolve its URL.
 * Internal subsets/custom entities remain forbidden. Only the confirmed XHTML nbsp
 * character alias is mapped, without touching comments or CDATA literal text. */
internal fun legacyEpubXml(text: String): Pair<String, XmlName?> {
    requireEpub(!text.contains("<!ENTITY", ignoreCase = true))
    val start = text.indexOf("<!DOCTYPE", ignoreCase = true)
    if (start < 0) return text to null
    var prefixEnd = 0
    if (text.startsWith("<?xml")) {
        prefixEnd = text.indexOf("?>") + 2
        requireEpub(prefixEnd in 7..258)
    }
    requireEpub(start >= prefixEnd && text.substring(prefixEnd, start).all { it in " \t\r\n" })
    val end = text.indexOf('>', start)
    requireEpub(end in start + 1..minOf(text.lastIndex, start + 512))
    val declaration = text.substring(start, end + 1)
    val parsed = Regex("<!DOCTYPE\\s+(html|ncx)\\s+PUBLIC\\s+(['\"])([^'\"]+)\\2\\s+(['\"])([^'\"]+)\\4\\s*>").matchEntire(declaration) ?: invalid()
    val root = parsed.groupValues[1]
    val publicId = parsed.groupValues[3]; val systemId = parsed.groupValues[5]
    val allowed = when (root) {
        "html" -> publicId to systemId in setOf(
            "-//W3C//DTD XHTML 1.1//EN" to "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd",
            "-//W3C//DTD XHTML 1.0 Strict//EN" to "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd",
            "-//W3C//DTD XHTML 1.0 Transitional//EN" to "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd",
        )
        "ncx" -> publicId == "-//NISO//DTD ncx 2005-1//EN" && systemId == "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"
        else -> false
    }
    requireEpub(allowed)
    val stripped = text.removeRange(start, end + 1)
    requireEpub(!stripped.contains("<!DOCTYPE", ignoreCase = true))
    val normalized = if (root == "html") buildString {
        var offset = 0
        while (offset < stripped.length) {
            val terminator = when {
                stripped.startsWith("<!--", offset) -> "-->"
                stripped.startsWith("<![CDATA[", offset) -> "]]>"
                else -> null
            }
            if (terminator != null) {
                val close = stripped.indexOf(terminator, offset + 4)
                requireEpub(close >= 0)
                val next = close + terminator.length
                append(stripped, offset, next); offset = next
            } else if (stripped.startsWith("&nbsp;", offset)) {
                append("&#160;"); offset += 6
            } else append(stripped[offset++])
        }
    } else stripped
    return normalized to XmlName(if (root == "html") "http://www.w3.org/1999/xhtml" else "http://www.daisy.org/z3986/2005/ncx/", root)
}
