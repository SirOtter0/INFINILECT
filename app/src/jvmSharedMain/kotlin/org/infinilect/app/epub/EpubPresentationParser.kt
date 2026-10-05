// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import kotlinx.coroutines.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*

private const val XHTML = "http://www.w3.org/1999/xhtml"
private val blockTags = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "div", "body", "pre", "figcaption")
private val forbidden = setOf("script", "iframe", "object", "embed", "form", "input", "button", "textarea", "select", "audio", "video", "canvas", "base", "applet")

/** No browser, network, CSS evaluation or image decoder. Bounded current-chapter semantics. */
internal class BoundedEpubParser : EpubParser {
    private suspend fun xml(document: EpubDocument, path: EpubEntryPath): EpubXmlNode {
        val item = document.manifest.singleOrNull { it.path == path } ?: invalid()
        requireEpub(item.mediaType == "application/xhtml+xml")
        // readBytes closes on success/failure/cancellation, exact entry size/CRC checked below it.
        val bytes = try { document.openResource(path).readBytes(1024 * 1024) }
        catch (error: CancellationException) { throw error }
        catch (error: ResourceLimitExceededException) { limit() }
        catch (error: Exception) { throw org.infinilect.app.reader.EpubException(org.infinilect.app.reader.EpubFailure.INVALID, error) }
        val root = parseEpubXml(bytes, EpubLimits(), currentCoroutineContext().job)
        requireEpub(root.name == XmlName(XHTML, "html"))
        requireEpub(root.children(XHTML, "head").size == 1 && root.children(XHTML, "body").size == 1)
        for (node in root.walk()) {
            currentCoroutineContext().ensureActive()
            requireEpub(node.name.namespace == XHTML && node.name.local.lowercase() !in forbidden)
            requireEpub(node.attributes.keys.none { it.local.startsWith("on", true) })
            requireEpub(node.attr("srcset") == null && node.attr("action") == null)
            for (name in listOf("href", "src", "poster", "data")) node.attr(name)?.let { reference ->
                ownedTarget(document, path, reference, spineOnly = name == "href" && node.name.local == "a")
            }
        }
        return root
    }

    override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter = withContext(Dispatchers.IO) {
        requireEpub(document.spine.any { spine -> document.manifest.any { it.id == spine.itemId && it.path == path } })
        val body = xml(document, path).children(XHTML, "body").single()
        val blocks = mutableListOf<EpubBlock>()
        val anchors = linkedMapOf<String, EpubPosition>()
        val pendingAnchors = mutableListOf<String>()
        val offsets = mutableMapOf<List<Int>, Int>()
        val job = currentCoroutineContext().job
        var totalUnits = 0
        var logical = 0
        var runCount = 0
        var linkCount = 0
        var contextPath = listOf(0)
        var kind = EpubBlockKind.PARAGRAPH
        var heading = 1
        var marker: EpubListMarker? = null
        var image: EpubImage? = null
        var imageCount = 0
        val runs = mutableListOf<EpubRun>()
        var units = 0
        fun flush() {
            if (runs.isEmpty() && kind != EpubBlockKind.SEPARATOR) return
            val text = runs.joinToString("") { it.text }
            if (text.isNotBlank() || kind == EpubBlockKind.SEPARATOR) {
                if (blocks.size >= 2048 || totalUnits + text.length > 262_144) limit()
                val start = offsets[contextPath] ?: 0
                val block = EpubBlock(contextPath.toList(), start, kind, runs.toList(), logical, heading, marker, image)
                pendingAnchors.forEach { anchors[it] = EpubPosition(block.elementPath, start) }
                pendingAnchors.clear()
                blocks.add(block); logical += block.codePoints; totalUnits += text.length
                offsets[contextPath] = start + block.codePoints
            }
            runs.clear(); units = 0
        }
        fun append(text: String, em: Boolean, strong: Boolean, target: EpubTarget?, literal: Boolean = false) {
            if (text.isEmpty()) return
            if (units + text.length > 8192 || ++runCount > 8192) limit()
            units += text.length
            // Preserve Unicode/text order; collapse HTML whitespace only, not code points.
            val normalized = if (literal) text else text.replace(Regex("[\\t\\r\\n ]+"), " ")
            val previous = runs.lastOrNull()
            if (previous != null && previous.emphasis == em && previous.strong == strong && previous.target == target)
                runs[runs.lastIndex] = previous.copy(text = previous.text + normalized)
            else runs.add(EpubRun(normalized, em, strong, target))
        }
        fun visit(node: EpubXmlNode, pathParts: List<Int>, em: Boolean = false, strong: Boolean = false, target: EpubTarget? = null, quote: Boolean = false, list: EpubListMarker? = null, pre: Boolean = false) {
            job.ensureActive()
            val tag = node.name.local
            val isBlock = tag in blockTags || tag == "img" || tag == "hr"
            val savedPath = contextPath; val savedKind = kind
            val savedHeading = heading; val savedMarker = marker; val savedImage = image
            if (isBlock) {
                flush(); contextPath = pathParts
                kind = when { tag == "img" -> EpubBlockKind.IMAGE; tag == "hr" -> EpubBlockKind.SEPARATOR; tag == "pre" || pre -> EpubBlockKind.PREFORMATTED; tag == "figcaption" -> EpubBlockKind.CAPTION; tag.matches(Regex("h[1-6]")) -> EpubBlockKind.HEADING; tag == "li" || list != null -> EpubBlockKind.LIST_ITEM; tag == "blockquote" || quote -> EpubBlockKind.QUOTE; else -> EpubBlockKind.PARAGRAPH }
                heading = tag.takeIf { it.matches(Regex("h[1-6]")) }?.last()?.digitToInt() ?: 1
                marker = list; image = null
                if (tag == "img" && runs.isEmpty()) {
                    // Keep historical alt-placeholder code-point semantics for saved progress.
                    // Inline images split a paragraph, but use its existing element path/offsets.
                    contextPath = if (savedPath.size > 1) savedPath else pathParts
                    if (++imageCount > 64) limit()
                    val src = node.attr("src") ?: invalid()
                    requireEpub(!src.contains('#') && src.split('/').none { it == "." || it == ".." })
                    val targetPath = ownedTarget(document, path, src, false).path
                    val item = document.manifest.single { it.path == targetPath }
                    val alt = node.attr("alt")?.let { it.substring(0, it.epubUtf16(it.epubPointAtUtf16(minOf(256, it.length)))) } ?: ""
                    image = EpubImage(targetPath, item.mediaType, alt)
                }
            }
            node.attr("id")?.let { id ->
                if (anchors.size + pendingAnchors.size >= 4096) limit()
                requireEpub(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}").matches(id) && id !in anchors && id !in pendingAnchors)
                if (runs.isEmpty()) pendingAnchors.add(id)
                else anchors[id] = EpubPosition(contextPath.toList(), (offsets[contextPath] ?: 0) + runs.sumOf { it.text.epubCodePoints() })
            }
            val nextTarget = if (tag == "a" && node.attr("href") != null) {
                if (++linkCount > 512) limit()
                ownedTarget(document, path, node.attr("href")!!, true)
            } else target
            if (tag == "hr") { /* semantic separator, no injected reading text */ }
            else if (tag == "br") append("\n", em, strong, nextTarget, literal = true)
            else if (tag == "img") append("[Image${node.attr("alt")?.let { alt -> alt.substring(0, alt.epubUtf16(alt.epubPointAtUtf16(minOf(256, alt.length)))) }?.let { ": $it" } ?: " not rendered"}]", em, strong, null)
            else {
                var child = 0
                var ordinal = if (tag == "ol") node.attr("start")?.toIntOrNull()?.also { requireEpub(it in 1..9999) } ?: 1 else 1
                if (tag == "ol" && node.attr("start") != null) requireEpub(node.attr("start")!!.toIntOrNull() != null)
                for (part in node.content) when (part) {
                    is EpubXmlContent.Text -> append(part.value.toString(), em || tag == "em" || tag == "i", strong || tag == "strong" || tag == "b", nextTarget, literal = pre || tag == "pre")
                    is EpubXmlContent.Element -> {
                        val childList = if (tag in setOf("ol", "ul") && part.value.name.local == "li") {
                            val depth = (list?.depth ?: -1) + 1
                            if (depth > 7 || ordinal > 9999) limit()
                            EpubListMarker(tag == "ol", ordinal++, depth)
                        } else list
                        visit(part.value, pathParts + child++, em || tag == "em" || tag == "i", strong || tag == "strong" || tag == "b", nextTarget, quote || tag == "blockquote", childList, pre || tag == "pre")
                    }
                }
            }
            if (isBlock) { flush(); contextPath = savedPath; kind = savedKind; heading = savedHeading; marker = savedMarker; image = savedImage }
        }
        visit(body, listOf(0))
        flush()
        requireEpub(blocks.isNotEmpty())
        EpubChapter(path, blocks.toList(), anchors.toMap())
    }

    override suspend fun toc(document: EpubDocument): List<EpubTocEntry> = withContext(Dispatchers.IO) {
        val nav = document.manifest.singleOrNull { it.id == document.navigationItemId } ?: invalid()
        val root = xml(document, nav.path)
        val toc = root.walk().filter { it.name.local == "nav" && it.attributes[XmlName("http://www.idpf.org/2007/ops", "type")]?.split(' ')?.contains("toc") == true }.singleOrNull() ?: invalid()
        val job = currentCoroutineContext().job
        val entries = mutableListOf<EpubTocEntry>()
        fun walk(node: EpubXmlNode, depth: Int) {
            job.ensureActive()
            if (depth > 16) limit()
            if (node.name.local == "a") {
                val label = StringBuilder()
                fun text(n: EpubXmlNode) { n.content.forEach { when (it) { is EpubXmlContent.Text -> label.append(it.value); is EpubXmlContent.Element -> text(it.value) }; if (label.length > 512) limit() } }
                text(node)
                val href = node.attr("href") ?: invalid()
                requireEpub(label.isNotBlank())
                if (entries.size >= 256) limit()
                entries.add(EpubTocEntry(label.toString().trim(), ownedTarget(document, nav.path, href, true), depth))
            }
            node.children.forEach { walk(it, depth + if (it.name.local == "ol") 1 else 0) }
        }
        walk(toc, 0)
        requireEpub(entries.isNotEmpty())
        entries.toList()
    }
}

private fun ownedTarget(document: EpubDocument, base: EpubEntryPath, reference: String, spineOnly: Boolean): EpubTarget {
    requireEpub(reference.length in 1..640 && reference.count { it == '#' } <= 1)
    val file = reference.substringBefore('#')
    val path = if (file.isEmpty()) base else resolveEpubPath(base, file)
    requireEpub(document.manifest.any { it.path == path })
    if (spineOnly) requireEpub(document.spine.any { spine -> document.manifest.any { it.id == spine.itemId && it.path == path } })
    val anchor = reference.substringAfter('#', "").takeIf { it.isNotEmpty() }
    requireEpub(!reference.contains('#') || anchor != null)
    anchor?.let { requireEpub(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}").matches(it)) }
    return EpubTarget(path, anchor)
}
