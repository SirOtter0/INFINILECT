// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.infinilect.app.epub.*
import org.infinilect.app.ui.DescriptionPolicy
import org.infinilect.core.EpubEntryPath

/** Only container + OPF, from digest-checked, previously validated private import bytes.
 * Chapter content and images are never parsed, acquired or prepared for a synopsis. */
internal suspend fun localEpubDescription(path: Path): String? {
    val limits = EpubLimits()
    val entries = inspectEpubZip(path, limits).filterNot { it.directory }.associateBy { it.path }
    require(entries.keys.none { it.value.equals("META-INF/encryption.xml", true) || it.value.equals("META-INF/signatures.xml", true) })
    ZipFile(path.toFile()).use { zip ->
        suspend fun bytes(path: EpubEntryPath, ceiling: Int): ByteArray {
            val entry = requireNotNull(entries[path])
            require(entry.size in 1..ceiling.toLong())
            val bytes = ByteArray(entry.size.toInt())
            zip.getInputStream(requireNotNull(zip.getEntry(entry.name))).use { input ->
                var at = 0
                while (at < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(bytes, at, minOf(8192, bytes.size - at))
                    require(count > 0); at += count
                }
                require(input.read() == -1)
            }
            require(CRC32().apply { update(bytes) }.value == entry.crc)
            return bytes
        }
        require(bytes(EpubEntryPath("mimetype"), 20).decodeToString() == "application/epub+zip")
        val ns = "urn:oasis:names:tc:opendocument:xmlns:container"
        val container = parseEpubXml(bytes(EpubEntryPath("META-INF/container.xml"), limits.xmlBytes), limits, currentCoroutineContext().job)
        require(container.name == XmlName(ns, "container") && container.attr("version") == "1.0")
        val root = container.children(ns, "rootfiles").single().children(ns, "rootfile").single()
        require(root.attr("media-type") == "application/oebps-package+xml")
        val opfPath = resolveEpubPath(null, requireNotNull(root.attr("full-path")))
        val opf = parseEpubXml(bytes(opfPath, limits.xmlBytes), limits, currentCoroutineContext().job)
        val opfNs = "http://www.idpf.org/2007/opf"
        require(opf.name == XmlName(opfNs, "package") && opf.attr("version") in listOf("2.0", "3.0"))
        val values = opf.children(opfNs, "metadata").single().children("http://purl.org/dc/elements/1.1/", "description")
        require(values.size <= DescriptionPolicy.VALUES)
        var units = 0
        val job = currentCoroutineContext().job
        val descriptions = values.mapNotNull { node ->
            val out = StringBuilder()
            fun append(text: String) {
                units += text.length
                require(units <= DescriptionPolicy.CHARACTERS)
                out.append(text)
            }
            fun visit(n: EpubXmlNode) {
                job.ensureActive()
                val local = n.name.local.lowercase()
                if (local in setOf("script", "style", "iframe", "object")) return
                val block = local in setOf("p", "div", "li", "br")
                if (block) append("\n\n")
                n.content.forEach { part -> when (part) {
                    is EpubXmlContent.Text -> append(part.value.toString())
                    is EpubXmlContent.Element -> visit(part.value)
                } }
                if (block) append("\n\n")
            }
            visit(node)
            plainDescription(out.toString()).takeIf { it.isNotBlank() }
        }.distinct()
        return descriptions.joinToString("\n\n").takeIf { it.isNotBlank() }.also {
            require(it == null || it.length <= DescriptionPolicy.CHARACTERS)
        }
    }
}

/** Passive plain-text projection, not an HTML renderer/parser. Decode a tiny fixed entity set
 * and numeric scalars once; discard markup and active-element bodies, never follow a URI. */
internal fun plainDescription(rawValue: String): String {
    require(rawValue.length <= DescriptionPolicy.CHARACTERS)
    val value = rawValue.replace("\r\n", "\n").replace('\r', '\n')
    val out = StringBuilder()
    var at = 0
    var suppressed: String? = null
    while (at < value.length) {
        val ch = value[at]
        if (ch == '<') {
            val end = value.indexOf('>', at + 1)
            if (end >= 0) {
                val raw = value.substring(at + 1, end).trim()
                val name = raw.removePrefix("/").takeWhile { it.isLetterOrDigit() }.lowercase()
                if (suppressed != null) { if (raw.startsWith('/') && name == suppressed) suppressed = null }
                else if (name in setOf("script", "style", "iframe", "object") && !raw.startsWith('/')) suppressed = name
                else if (name in setOf("p", "div", "li", "br")) out.append("\n\n")
                if (name.firstOrNull()?.isLetter() == true || raw.startsWith('!')) { at = end + 1; continue }
            }
        }
        if (suppressed != null) { at++; continue }
        if (ch == '&') {
            val end = ((at + 2)..minOf(value.length - 1, at + 16)).firstOrNull { value[it] == ';' }
            val entity = end?.let { value.substring(at + 1, it) }
            val decoded = when (entity) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> entity?.takeIf { it.startsWith('#') }?.let {
                    val hex = it.startsWith("#x", true)
                    it.drop(if (hex) 2 else 1).toIntOrNull(if (hex) 16 else 10)
                }?.takeIf { it in 32..0x10ffff && it !in 0xd800..0xdfff && it !in 127..159 }
                    ?.let { String(Character.toChars(it)) }
            }
            if (decoded != null) { out.append(decoded); at = end!! + 1; continue }
        }
        if (ch == '\n' || ch == '\r') out.append('\n')
        else if (ch.isWhitespace() || ch == '\u00a0') out.append(' ')
        else if (!ch.isISOControl()) out.append(ch)
        at++
    }
    return out.toString().split(Regex("\n[ \t]*\n+"))
        .map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
}
