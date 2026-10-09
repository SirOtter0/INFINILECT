// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import kotlinx.coroutines.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*

private const val XHTML = "http://www.w3.org/1999/xhtml"
private val blockTags = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "div", "body", "pre", "figcaption")
private val htmlWhitespace = Regex("[\\t\\r\\n ]+")
private fun headingTag(tag: String) = tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6'

/** No browser, network, CSS evaluation or image decoder. Bounded current-chapter semantics. */
internal class BoundedEpubParser : EpubParser {
    private suspend fun xml(document: EpubDocument, path: EpubEntryPath): Pair<EpubXmlNode, Map<EpubXmlNode, EpubImage>> {
        val item = document.manifest.singleOrNull { it.path == path } ?: invalid()
        requireEpub(item.mediaType == "application/xhtml+xml")
        // readBytes closes on success/failure/cancellation, exact entry size/CRC checked below it.
        val bytes = try { document.openResource(path).readBytes(1024 * 1024) }
        catch (error: CancellationException) { throw error }
        catch (error: ResourceLimitExceededException) { limit() }
        catch (error: Exception) { throw org.infinilect.app.reader.EpubException(org.infinilect.app.reader.EpubFailure.INVALID, error) }
        val root = parseEpubXml(bytes, EpubLimits(), currentCoroutineContext().job)
        val images = validateEpubContent(root, path, document.manifest, currentCoroutineContext().job)
        for (node in root.walk()) if (node.name == XmlName(XHTML, "a")) node.attr("href")?.let {
            currentCoroutineContext().ensureActive(); ownedTarget(document, path, it, spineOnly = true)
        }
        return root to images
    }

    override suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter = withContext(Dispatchers.IO) {
        val (root, images) = chapterXml(document, path)
        val blocks = mutableListOf<EpubBlock>()
        val scan = scan(document, path, root, images, legacy = true) { block, _, _ -> blocks.add(block) }
        EpubChapter(path, blocks.toList(), scan.anchors)
    }

    private suspend fun chapterXml(document: EpubDocument, path: EpubEntryPath): Pair<EpubXmlNode, Map<EpubXmlNode, EpubImage>> {
        requireEpub(document.spine.any { spine -> document.manifest.any { it.id == spine.itemId && it.path == path } })
        return xml(document, path)
    }
    private data class Window(val start: Int, val logicalStart: Int, var count: Int = 0, var units: Int = 0, var events: Int = 0)
    private data class Scan(val anchors: Map<String, EpubPosition>, val anchorBlocks: Map<String, Int>, val blocks: Int, val points: Int)

    /** Prepared documents build a bounded, session-private semantic file once. Warm
     * requests read only one window. Documents without private cache storage retain
     * the bounded two-pass fallback; no whole XML/semantic chapter is retained in RAM. */
    override suspend fun window(document: EpubDocument, path: EpubEntryPath, request: EpubWindowRequest): EpubChapter = withContext(Dispatchers.IO) {
        if (document is EpubSemanticCacheOwner) {
            try {
                return@withContext document.semanticCache().window(document, path, request) { writer ->
                    val (root, images) = chapterXml(document, path)
                    val scan = scan(document, path, root, images, consume = writer::accept)
                    EpubSemanticMetadata(scan.anchors, scan.anchorBlocks, scan.blocks, scan.points)
                }
            } catch (_: EpubSemanticCacheUnavailable) {
                // Optional acceleration cannot make an otherwise supported chapter fail.
            }
        }
        uncachedWindow(document, path, request)
    }

    private suspend fun uncachedWindow(document: EpubDocument, path: EpubEntryPath, request: EpubWindowRequest): EpubChapter {
        val (root, images) = chapterXml(document, path)
        val windows = mutableListOf<Window>()
        var exact: Int? = null
        val locator = (request as? EpubWindowRequest.Locator)?.value
        val scan = scan(document, path, root, images) { block, index, events ->
            if (locator != null && block.elementPath == locator.elementPath && block.startOffset <= locator.codePointOffset) exact = index
            var current = windows.lastOrNull()
            if (current == null || current.count >= EpubWindowPolicy.BLOCKS || current.units + block.text.length > EpubWindowPolicy.TEXT_UNITS || current.events + events > EpubWindowPolicy.APPEND_EVENTS) {
                if (windows.size >= EpubWindowPolicy.INDEX_ENTRIES) limit()
                current = Window(index, block.logicalStart); windows.add(current)
            }
            current.count++; current.units += block.text.length; current.events += events
        }
        val desired = when (request) {
            is EpubWindowRequest.Block -> request.index.coerceIn(0, scan.blocks - 1)
            is EpubWindowRequest.Anchor -> scan.anchorBlocks[request.value] ?: invalid()
            EpubWindowRequest.End -> scan.blocks - 1
            is EpubWindowRequest.Locator -> exact
        }
        val selected = if (desired != null) windows.last { it.start <= desired }
            else windows.last { it.logicalStart <= (scan.points * locator!!.chapterProgression).toInt() }
        val blocks = ArrayList<EpubBlock>(selected.count)
        scan(document, path, root, images, collectAnchors = false) { block, index, _ ->
            if (index in selected.start until selected.start + selected.count) blocks.add(block)
        }
        currentCoroutineContext().ensureActive()
        return EpubChapter(path, blocks.toList(), scan.anchors, selected.start, scan.blocks, scan.points)
    }

    private suspend fun scan(document: EpubDocument, path: EpubEntryPath, root: EpubXmlNode, rasterWrappers: Map<EpubXmlNode, EpubImage>, legacy: Boolean = false, collectAnchors: Boolean = true, consume: (EpubBlock, Int, Int) -> Unit): Scan {
        val body = root.children(XHTML, "body").single()
        var blockCount = 0
        val anchorBlocks = linkedMapOf<String, Int>()
        var blockEvents = 0
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
        var runStyle: EpubRun? = null
        val runText = StringBuilder()
        var units = 0
        fun finishRun() {
            runStyle?.let { runs.add(it.copy(text = runText.toString())) }
            runStyle = null; runText.setLength(0)
        }
        fun flush() {
            finishRun()
            if (runs.isEmpty() && kind != EpubBlockKind.SEPARATOR) return
            val text = runs.joinToString("") { it.text }
            if (text.isNotBlank() || kind == EpubBlockKind.SEPARATOR) {
                if (legacy && (blockCount >= 2048 || totalUnits + text.length > 262_144)) limit()
                val start = offsets[contextPath] ?: 0
                val block = EpubBlock(contextPath.toList(), start, kind, runs.toList(), logical, heading, marker, image)
                pendingAnchors.forEach { anchors[it] = EpubPosition(block.elementPath, start); anchorBlocks[it] = blockCount }
                pendingAnchors.clear()
                consume(block, blockCount++, blockEvents); logical += block.codePoints; totalUnits += text.length
                offsets[contextPath] = start + block.codePoints
            }
            runs.clear(); units = 0; blockEvents = 0
        }
        fun append(text: String, em: Boolean, strong: Boolean, target: EpubTarget?, literal: Boolean = false) {
            if (text.isEmpty()) return
            if (units + text.length > 8192 || ++blockEvents > EpubWindowPolicy.APPEND_EVENTS || legacy && ++runCount > 8192) limit()
            units += text.length
            // Preserve Unicode/text order; collapse HTML whitespace only, not code points.
            val normalized = if (literal) text else text.replace(htmlWhitespace, " ")
            val previous = runStyle
            if (previous == null || previous.emphasis != em || previous.strong != strong || previous.target != target) {
                finishRun(); runStyle = EpubRun("", em, strong, target)
            }
            // Linear coalescing: thousands of adjacent inline spans must not copy
            // the growing text/run object for every append event.
            runText.append(normalized)
        }
        fun visit(node: EpubXmlNode, pathParts: List<Int>, em: Boolean = false, strong: Boolean = false, target: EpubTarget? = null, quote: Boolean = false, list: EpubListMarker? = null, pre: Boolean = false) {
            job.ensureActive()
            val rasterWrapper = rasterWrappers[node]
            val tag = if (rasterWrapper != null) "img" else node.name.local
            val isBlock = tag in blockTags || tag == "img" || tag == "hr"
            val savedPath = contextPath; val savedKind = kind
            val savedHeading = heading; val savedMarker = marker; val savedImage = image
            if (isBlock) {
                flush(); contextPath = pathParts
                kind = when { tag == "img" -> EpubBlockKind.IMAGE; tag == "hr" -> EpubBlockKind.SEPARATOR; tag == "pre" || pre -> EpubBlockKind.PREFORMATTED; tag == "figcaption" -> EpubBlockKind.CAPTION; headingTag(tag) -> EpubBlockKind.HEADING; tag == "li" || list != null -> EpubBlockKind.LIST_ITEM; tag == "blockquote" || quote -> EpubBlockKind.QUOTE; else -> EpubBlockKind.PARAGRAPH }
                heading = tag.takeIf(::headingTag)?.last()?.digitToInt() ?: 1
                marker = list; image = null
                if (tag == "img" && runs.isEmpty()) {
                    // Keep historical alt-placeholder code-point semantics for saved progress.
                    // Inline images split a paragraph, but use its existing element path/offsets.
                    contextPath = if (savedPath.size > 1) savedPath else pathParts
                    if (++imageCount > 64) limit()
                    image = rasterWrapper ?: run {
                        val src = node.attr("src") ?: invalid()
                        requireEpub(!src.contains('#'))
                        val targetPath = ownedTarget(document, path, src, false).path
                        val item = document.manifest.single { it.path == targetPath }
                        val alt = node.attr("alt")?.let { it.substring(0, it.epubUtf16(it.epubPointAtUtf16(minOf(256, it.length)))) } ?: ""
                        EpubImage(targetPath, item.mediaType, alt)
                    }
                }
            }
            node.attr("id")?.takeIf { collectAnchors }?.let { id ->
                if (anchors.size + pendingAnchors.size >= 4096) limit()
                requireEpub(validEpubContentAnchor(id) && id !in anchors && id !in pendingAnchors)
                if (runs.isEmpty() && runText.isEmpty()) pendingAnchors.add(id)
                else { anchors[id] = EpubPosition(contextPath.toList(), (offsets[contextPath] ?: 0) + runs.sumOf { it.text.epubCodePoints() } + runText.toString().epubCodePoints()); anchorBlocks[id] = blockCount }
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
                        val childList = if ((tag == "ol" || tag == "ul") && part.value.name.local == "li") {
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
        requireEpub(blockCount > 0)
        return Scan(anchors.toMap(), anchorBlocks.toMap(), blockCount, logical)
    }

    override suspend fun toc(document: EpubDocument): List<EpubTocEntry> = withContext(Dispatchers.IO) {
        val nav = document.manifest.singleOrNull { it.id == document.navigationItemId } ?: invalid()
        if (nav.mediaType == NCX_MEDIA_TYPE) {
            val bytes = try { document.openResource(nav.path).readBytes(1024 * 1024) }
            catch (error: ResourceLimitExceededException) { limit() }
            return@withContext readEpubNcx(parseEpubXml(bytes, EpubLimits(), currentCoroutineContext().job),
                nav.path, document.manifest, document.spine, currentCoroutineContext().job)
        }
        val root = xml(document, nav.path).first
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
    val spinePaths = if (spineOnly) document.spine.map { ref -> document.manifest.single { it.id == ref.itemId }.path }.toSet() else null
    return resolveEpubTarget(base, reference, document.manifest, spinePaths)
}
