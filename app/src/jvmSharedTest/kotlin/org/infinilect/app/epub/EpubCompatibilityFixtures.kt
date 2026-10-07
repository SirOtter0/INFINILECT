// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

/** Original deterministic publications. Text/artwork is project-owned; no copied books. */
internal fun epub2Fixture(): EpubFixture = EpubFixture().apply {
    entries.remove("OPS/nav.xhtml")
    opf { it.replace("version=\"3.0\"", "version=\"2.0\"")
        .replace(Regex("<meta property=\"dcterms:modified\">.*?</meta>"), "")
        .replace("href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"", "href=\"Nav/toc.ncx\" media-type=\"application/x-dtbncx+xml\"")
        .replace("<spine>", "<spine toc=\"nav\">") }
    entries["OPS/Nav/toc.ncx"] = ncx(point("one", "First chapter", "../chapter.xhtml#start")).encodeToByteArray()
}
internal fun ncx(points: String): String = """
    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
      <head><meta name="dtb:uid" content="urn:synthetic:foundation"/></head>
      <docTitle><text>Original compatibility fixture</text></docTitle>
      <navMap>$points</navMap>
    </ncx>
""".trimIndent()
internal fun point(id: String, label: String, src: String, children: String = "") =
    "<navPoint id=\"$id\"><navLabel><text>$label</text></navLabel><content src=\"$src\"/>$children</navPoint>"
