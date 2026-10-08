// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.time.Instant
import java.util.zip.ZipFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.infinilect.core.*
private const val OCF = "urn:oasis:names:tc:opendocument:xmlns:container"
private const val OPF = "http://www.idpf.org/2007/opf"
private const val DC = "http://purl.org/dc/elements/1.1/"
private const val XHTML = "http://www.w3.org/1999/xhtml"
internal data class EpubPackage(val path: EpubEntryPath, val metadata: EpubMetadata, val manifest: List<EpubManifestItem>, val spine: List<EpubSpineItem>, val navigation: String)
internal fun readEpubPackage(zip: ZipFile, entries: List<EpubZipEntry>, limits: EpubLimits, job: Job?): EpubPackage {
    val files = entries.filterNot {
        it.directory
    }
    .associateBy {
        it.path
    }
    if (entries.any { it.name.equals("META-INF/encryption.xml", true) })
        throw org.infinilect.app.reader.EpubException(org.infinilect.app.reader.EpubFailure.ENCRYPTED)
    requireEpub(entries.none { it.name.equals("META-INF/signatures.xml", true) })
    fun xml(path: EpubEntryPath): EpubXmlNode {
        job?.ensureActive()
        val entry = files[path] ?: invalid()
        if (entry.size>limits.xmlBytes) limit()
        val bytes = ByteArray(entry.size.toInt())
        zip.getInputStream(zip.getEntry(entry.name)).use {
            stream ->
            var offset = 0
            while (offset<bytes.size) {
                job?.ensureActive()
                val n = stream.read(bytes, offset, minOf(EPUB_BUFFER_BYTES, bytes.size-offset))
                requireEpub(n>0)
                offset+=n
            }
            requireEpub(stream.read()==-1)
        }
        return parseEpubXml(bytes, limits, job)
    }
    val container = xml(EpubEntryPath("META-INF/container.xml"))
    requireEpub(container.name==XmlName(OCF, "container") && container.attr("version")=="1.0")
    val rootfiles = container.children(OCF, "rootfiles").singleOrNull() ?: invalid()
    val rootfile = rootfiles.children(OCF, "rootfile").singleOrNull() ?: invalid()
    requireEpub(rootfile.attr("media-type")=="application/oebps-package+xml")
    val packagePath = resolveEpubPath(null, rootfile.attr("full-path") ?: invalid())
    val opf = xml(packagePath)
    val epub2 = opf.attr("version") == "2.0"
    requireEpub(opf.name==XmlName(OPF, "package") && (epub2 || opf.attr("version")=="3.0"))
    val metadataNode = opf.children(OPF, "metadata").singleOrNull() ?: invalid()
    val manifestNode = opf.children(OPF, "manifest").singleOrNull() ?: invalid()
    val spineNode = opf.children(OPF, "spine").singleOrNull() ?: invalid()
    val ids = HashSet<String>()
    for (node in opf.walk()) node.attr("id")?.let {
        requireEpub(validId(it) && ids.add(it))
    }
    fun values(local: String) = metadataNode.children(DC, local).map {
        it.text.toString().trim()
    }
    .also {
        requireEpub(it.all(String::isNotBlank))
    }
    val unique = opf.attr("unique-identifier") ?: invalid()
    requireEpub(validId(unique))
    val identifier = metadataNode.children(DC, "identifier").singleOrNull {
        it.attr("id")==unique
    }
    ?.text?.toString()?.trim() ?: invalid()
    val titles = values("title")
    val languages = values("language")
    requireEpub(identifier.isNotBlank() && titles.isNotEmpty() && languages.isNotEmpty())
    requireEpub(titles.size<=32 && languages.size<=32 && languages.all {
        it.length<=128
    }
    )
    val creators = values("creator")
    requireEpub(creators.size<=64)
    val rights = values("rights")
    requireEpub(rights.size<=1)
    val modified = metadataNode.children(OPF, "meta").singleOrNull {
        it.attr("property")=="dcterms:modified"
    }
    ?.text?.toString()?.trim()
    if (!epub2) requireEpub(modified != null)
    if (modified != null) {
        requireEpub(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z").matches(modified))
        try { Instant.parse(modified) } catch (_: Exception) { invalid() }
    }
    val items = manifestNode.children(OPF, "item")
    if (items.size>limits.manifest) limit()
    requireEpub(items.isNotEmpty())
    val paths = HashSet<EpubEntryPath>()
    val manifest = items.map {
        node ->
        val id = node.attr("id") ?: invalid()
        requireEpub(validId(id))
        val path = resolveEpubPath(packagePath, node.attr("href") ?: invalid())
        requireEpub(path in files && paths.add(path))
        val type = node.attr("media-type") ?: invalid()
        requireEpub(type.length<=128 && Regex("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*").matches(type))
        requireEpub(type !in setOf("application/zip", "application/epub+zip", "application/java-archive"))
        val properties = node.attr("properties")?.trim()?.split(Regex("\\s+"))?.toSet() ?: emptySet()
        requireEpub(properties.size<=16 && properties.all {
            validId(it)
        }
        && "scripted" !in properties && "remote-resources" !in properties)
        requireEpub(node.attr("media-overlay")==null && node.attr("fallback")==null) // future multimedia/fallback handling
        EpubManifestItem(id, path, type, properties)
    }
    val byId = manifest.associateBy {
        it.id
    }
    requireEpub(byId.size==manifest.size)
    val refs = spineNode.children(OPF, "itemref")
    if (refs.size>limits.spine) limit()
    requireEpub(refs.isNotEmpty())
    val spine = refs.map {
        ref ->
        val id = ref.attr("idref") ?: invalid()
        val item = byId[id] ?: invalid()
        requireEpub(item.mediaType=="application/xhtml+xml" && ref.attr("linear") in listOf(null, "yes", "no"))
        EpubSpineItem(id, ref.attr("linear")!="no")
    }
    requireEpub(spine.any {
        it.linear
    }
    )
    val nav = if (epub2) {
        val toc = spineNode.attr("toc") ?: invalid()
        requireEpub(validId(toc))
        byId[toc]?.also { requireEpub(it.mediaType == NCX_MEDIA_TYPE) } ?: invalid()
    } else {
        manifest.singleOrNull { "nav" in it.properties }
            ?.also { requireEpub(it.mediaType == "application/xhtml+xml") } ?: invalid()
    }
    if (epub2) readEpubNcx(xml(nav.path), nav.path, manifest, spine, job)
    for (item in manifest.filter {
        it.mediaType=="application/xhtml+xml"
    }
    ) {
        val doc = xml(item.path)
        job?.ensureActive()
        validateEpubContent(doc, item.path, manifest, job)
        if (!epub2 && item.id==nav.id) requireEpub(doc.walk().any {
            node -> node.name==XmlName(XHTML, "nav") &&
            node.attributes[XmlName("http://www.idpf.org/2007/ops", "type")]?.split(Regex("\\s+"))?.contains("toc")==true
        }
        )
    }
    return EpubPackage(packagePath, EpubMetadata(identifier, titles.first(), languages, modified, creators, rights.singleOrNull()), manifest, spine, nav.id)
}
private fun validId(value: String) = value.length in 1..128 && Regex("[A-Za-z_][A-Za-z0-9_.-]*").matches(value)
