// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import org.infinilect.core.*

internal data class EpubTarget(val path: EpubEntryPath, val anchor: String? = null)
internal data class EpubRun(val text: String, val emphasis: Boolean = false, val strong: Boolean = false, val target: EpubTarget? = null)
internal enum class EpubBlockKind { PARAGRAPH, HEADING, LIST_ITEM, QUOTE, IMAGE, SEPARATOR, PREFORMATTED, CAPTION }
internal data class EpubListMarker(val ordered: Boolean, val ordinal: Int, val depth: Int)
internal data class EpubImage(val path: EpubEntryPath, val mediaType: String, val alt: String) {
    init { require(alt.length <= 256 && mediaType.length in 1..128) }
}
internal data class EpubPosition(val elementPath: List<Int>, val codePointOffset: Int)
internal data class EpubBlock(
    val elementPath: List<Int>, val startOffset: Int, val kind: EpubBlockKind,
    val runs: List<EpubRun>, val logicalStart: Int,
    val headingLevel: Int = 1, val listMarker: EpubListMarker? = null, val image: EpubImage? = null,
) {
    val text = runs.joinToString("") { it.text }
    val codePoints = text.epubCodePoints()
}
internal data class EpubChapter(
    val path: EpubEntryPath, val blocks: List<EpubBlock>, val anchors: Map<String, EpubPosition>,
    val startBlock: Int = 0, val totalBlocks: Int = blocks.size,
    val totalCodePoints: Int = blocks.sumOf { it.codePoints },
) {
    // Logical starts/paths/offsets always refer to the whole XHTML document.
    val codePoints = totalCodePoints
    val endBlock get() = startBlock + blocks.size
    fun locate(locator: ReadingLocator.Epub?): Pair<Int, Int> {
        if (locator == null || blocks.isEmpty()) return 0 to 0
        val matching = blocks.indices.filter { blocks[it].elementPath == locator.elementPath }
        val index = matching.lastOrNull { blocks[it].startOffset <= locator.codePointOffset }
        if (index != null) return index to (locator.codePointOffset - blocks[index].startOffset).coerceIn(0, blocks[index].codePoints.toLong()).toInt()
        val approximate = (codePoints * locator.chapterProgression).toInt()
        val fallback = blocks.indexOfLast { it.logicalStart <= approximate }.coerceAtLeast(0)
        return fallback to (approximate - blocks[fallback].logicalStart).coerceIn(0, blocks[fallback].codePoints)
    }
    fun locator(block: Int, localCodePoints: Int): ReadingLocator.Epub {
        val value = blocks[block.coerceIn(blocks.indices)]
        val offset = localCodePoints.coerceIn(0, value.codePoints)
        return ReadingLocator.Epub(path, value.elementPath, (value.startOffset + offset).toLong(),
            if (codePoints == 0) 0.0 else (value.logicalStart + offset).toDouble() / codePoints)
    }
}
internal data class EpubTocEntry(val label: String, val target: EpubTarget, val depth: Int)

internal object EpubWindowPolicy {
    const val BLOCKS = 128
    const val TEXT_UNITS = 65_536
    const val APPEND_EVENTS = 8192
    const val INDEX_ENTRIES = 512
    const val RETAINED = 2
}
internal sealed interface EpubWindowRequest {
    data class Block(val index: Int) : EpubWindowRequest
    data class Locator(val value: ReadingLocator.Epub) : EpubWindowRequest
    data class Anchor(val value: String) : EpubWindowRequest
    data object End : EpubWindowRequest
}

/** Presentation only; implementations may open manifest-owned resources, never URLs. */
internal interface EpubParser {
    /** Compatibility full-model API: retains its original 2,048-block ceiling. */
    suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter
    /** Production readers use bounded windows; small existing implementations remain compatible. */
    suspend fun window(document: EpubDocument, path: EpubEntryPath, request: EpubWindowRequest = EpubWindowRequest.Block(0)): EpubChapter = chapter(document, path)
    suspend fun toc(document: EpubDocument): List<EpubTocEntry>
}
internal expect fun defaultEpubParser(): EpubParser

internal fun String.epubCodePoints(): Int {
    var i = 0; var count = 0
    while (i < length) { i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1; count++ }
    return count
}
internal fun String.epubUtf16(offset: Int): Int {
    var i = 0; var count = 0
    while (i < length && count < offset) { i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1; count++ }
    return i
}
internal fun String.epubPointAtUtf16(offset: Int): Int {
    var end = offset.coerceIn(0, length)
    if (end > 0 && end < length && this[end].isLowSurrogate() && this[end - 1].isHighSurrogate()) end--
    return substring(0, end).epubCodePoints()
}
