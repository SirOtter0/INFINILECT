// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import org.infinilect.app.reader.epub.EpubImage
import org.infinilect.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

private const val XHTML = "http://www.w3.org/1999/xhtml"
private const val SVG = "http://www.w3.org/2000/svg"
private const val XLINK = "http://www.w3.org/1999/xlink"
private val activeContent = setOf("script", "iframe", "object", "embed", "form", "input", "button", "textarea", "select", "audio", "video", "canvas", "base", "applet")

/** Shared preparation/presentation boundary. SVG is never rendered: a very narrow
 * static one-raster cover wrapper projects onto the existing owned-image model. */
internal fun validateEpubContent(root: EpubXmlNode, base: EpubEntryPath, manifest: List<EpubManifestItem>, job: Job?): Map<EpubXmlNode, EpubImage> {
    requireEpub(root.name == XmlName(XHTML, "html") && root.children(XHTML, "head").size == 1 && root.children(XHTML, "body").size == 1)
    val wrappers = linkedMapOf<EpubXmlNode, EpubImage>()
    val imageNodes = hashSetOf<EpubXmlNode>()
    val ids = hashSetOf<String>()
    for (node in root.walk()) {
        job?.ensureActive()
        when (node.name) {
            XmlName(SVG, "svg") -> {
                if (wrappers.size >= 64) limit()
                val child = node.children.singleOrNull() ?: invalid()
                requireEpub(child.name == XmlName(SVG, "image") && child.children.isEmpty() && child.text.isBlank() && node.text.isBlank())
                requireEpub(node.attributes.keys.all { it.namespace.isEmpty() && it.local in setOf("version", "width", "height", "viewBox", "preserveAspectRatio", "id") })
                requireEpub(child.attributes.keys.all { it.namespace.isEmpty() && it.local in setOf("width", "height", "x", "y", "href") || it == XmlName(XLINK, "href") })
                requireEpub(node.attr("version") in listOf(null, "1.1", "2.0"))
                for (part in listOf(node, child)) for (dimension in listOf("width", "height")) part.attr(dimension)?.let {
                    requireEpub(it.length <= 32 && Regex("[0-9]+(?:\\.[0-9]+)?%?").matches(it) && it.removeSuffix("%").toDoubleOrNull()?.let { value -> value > 0 && value <= 1_000_000 } == true)
                }
                for (coordinate in listOf("x", "y")) child.attr(coordinate)?.let { requireEpub(it.toDoubleOrNull() == 0.0) }
                node.attr("viewBox")?.let {
                    val values = it.trim().split(Regex("[ ,]+")).map { value -> value.toDoubleOrNull() ?: invalid() }
                    requireEpub(values.size == 4 && values[0] == 0.0 && values[1] == 0.0 && values.drop(2).all { value -> value > 0 && value <= 1_000_000 })
                }
                node.attr("preserveAspectRatio")?.let { requireEpub(it in setOf("none", "xMidYMid meet")) }
                val references = listOfNotNull(child.attr("href"), child.attributes[XmlName(XLINK, "href")])
                requireEpub(references.size == 1 && '#' !in references.single())
                val target = resolveEpubTarget(base, references.single(), manifest)
                val resource = manifest.single { it.path == target.path }
                requireEpub(resource.mediaType in setOf("image/png", "image/jpeg"))
                wrappers[node] = EpubImage(target.path, resource.mediaType, "")
                imageNodes.add(child)
            }
            XmlName(SVG, "image") -> requireEpub(node in imageNodes)
            else -> requireEpub(node.name.namespace == XHTML && node.name.local.lowercase() !in activeContent)
        }
        for ((name, value) in node.attributes) {
            requireEpub(!name.local.startsWith("on", true) && name.local !in setOf("srcset", "action"))
            if (name.local == "id") requireEpub(validEpubContentAnchor(value) && ids.add(value))
            if (name.local in setOf("href", "src", "poster", "data")) {
                requireEpub(value.length <= 512)
                resolveEpubTarget(base, value, manifest)
            }
        }
    }
    return wrappers
}
