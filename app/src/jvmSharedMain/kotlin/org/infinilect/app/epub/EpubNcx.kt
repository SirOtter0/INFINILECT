// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.infinilect.app.reader.epub.EpubTocEntry
import org.infinilect.core.*

internal const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
private const val NCX = "http://www.daisy.org/z3986/2005/ncx/"

/** NCX metadata adapter, not a reader/authorization source. Iterative document-order traversal;
 * same navigation ceilings as the existing EPUB3 TOC: 256 nodes, 16 levels, 512-unit labels.
 */
internal fun readEpubNcx(
    root: EpubXmlNode, path: EpubEntryPath, manifest: List<EpubManifestItem>, spine: List<EpubSpineItem>, job: Job?,
): List<EpubTocEntry> {
    requireEpub(root.name == XmlName(NCX, "ncx") && root.attr("version") == "2005-1")
    val map = root.children(NCX, "navMap").singleOrNull() ?: invalid()
    val spinePaths = spine.map { ref -> manifest.single { it.id == ref.itemId }.path }.toSet()
    val entries = mutableListOf<EpubTocEntry>()
    val pending = ArrayDeque<Pair<EpubXmlNode, Int>>()
    val ids = HashSet<String>()
    map.children.asReversed().forEach { pending.addLast(it to 1) }
    while (pending.isNotEmpty()) {
        job?.ensureActive()
        val (point, depth) = pending.removeLast()
        requireEpub(point.name == XmlName(NCX, "navPoint"))
        if (depth > 16 || entries.size >= 256) limit()
        val id = point.attr("id") ?: invalid()
        requireEpub(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}").matches(id) && ids.add(id))
        requireEpub(point.children.all { it.name.namespace == NCX && it.name.local in setOf("navLabel", "content", "navPoint") })
        val labelNode = point.children(NCX, "navLabel").singleOrNull() ?: invalid()
        val text = labelNode.children(NCX, "text").singleOrNull() ?: invalid()
        requireEpub(labelNode.children.size == 1 && text.children.isEmpty())
        if (text.text.length > 512) limit()
        val label = text.text.toString().trim()
        requireEpub(label.isNotEmpty())
        val content = point.children(NCX, "content").singleOrNull() ?: invalid()
        requireEpub(content.children.isEmpty())
        val src = content.attr("src") ?: invalid()
        entries += EpubTocEntry(label, resolveEpubTarget(path, src, manifest, spinePaths), depth)
        point.children(NCX, "navPoint").asReversed().forEach { pending.addLast(it to depth + 1) }
    }
    requireEpub(entries.isNotEmpty())
    return entries.toList()
}
