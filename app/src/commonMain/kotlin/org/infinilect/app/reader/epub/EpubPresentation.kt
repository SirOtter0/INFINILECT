// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import org.infinilect.core.*

internal data class EpubTarget(val path: EpubEntryPath, val anchor: String? = null)
internal data class EpubRun(val text: String, val emphasis: Boolean = false, val strong: Boolean = false, val target: EpubTarget? = null)
internal enum class EpubBlockKind { PARAGRAPH, HEADING, LIST_ITEM, QUOTE }
internal data class EpubPosition(val elementPath: List<Int>, val codePointOffset: Int)
internal data class EpubBlock(
    val elementPath: List<Int>, val startOffset: Int, val kind: EpubBlockKind,
    val runs: List<EpubRun>, val logicalStart: Int,
) {
    val text = runs.joinToString("") { it.text }
    val codePoints = text.epubCodePoints()
}
internal data class EpubChapter(
    val path: EpubEntryPath, val blocks: List<EpubBlock>, val anchors: Map<String, EpubPosition>,
) {
    val codePoints = blocks.sumOf { it.codePoints }
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

/** Presentation only; implementations may open manifest-owned resources, never URLs. */
internal interface EpubParser {
    suspend fun chapter(document: EpubDocument, path: EpubEntryPath): EpubChapter
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
