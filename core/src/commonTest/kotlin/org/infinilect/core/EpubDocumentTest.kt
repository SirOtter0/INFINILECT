// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.test.*

class EpubDocumentTest {
    @Test fun canonicalPathsAreStructuredAndCaseSensitive() {
        assertEquals(EpubEntryPath("OPS/Text/chapter.xhtml"), EpubEntryPath("OPS/Text/chapter.xhtml"))
        assertNotEquals(EpubEntryPath("OPS/A.xhtml"), EpubEntryPath("OPS/a.xhtml"))
    }
    @Test fun pathsRejectTraversalAbsoluteAndAmbiguousUriForms() {
        for (path in listOf("", "/book", "a/../b", "a/./b", "a//b", "a/", "a\\b", "../b",
            "https://example.org/book", "file:book", "a%2fb", "a%252fb", "a?b", "a#b", "a\u0000b", "a\u0085b")) {
            assertFailsWith<IllegalArgumentException>(path) { EpubEntryPath(path) }
        }
    }
    @Test fun canonicalPathsKeepLiteralSpacesAndUnicodeWithoutUriDecoding() {
        assertEquals("Images/cover image.jpg", EpubEntryPath("Images/cover image.jpg").value)
        assertEquals("Text/日本語-é.xhtml", EpubEntryPath("Text/日本語-é.xhtml").value)
        assertFailsWith<IllegalArgumentException> { EpubEntryPath("Text/\uD800.xhtml") }
    }
    @Test fun pathsHaveFiniteLengthAndDepth() {
        assertFailsWith<IllegalArgumentException> { EpubEntryPath("a".repeat(513)) }
        assertFailsWith<IllegalArgumentException> { EpubEntryPath(List(33) { "a" }.joinToString("/")) }
        EpubEntryPath("a".repeat(512))
    }
    @Test fun epubModelKeepsUnicodeMetadataAndOrderedReferences() {
        val metadata = EpubMetadata("urn:test", "日本語 📚", listOf("ja"), "2026-10-04T00:00:00Z")
        val item = EpubManifestItem("chapter", EpubEntryPath("chapter.xhtml"), "application/xhtml+xml")
        assertEquals("日本語 📚", metadata.title)
        assertEquals("chapter", EpubSpineItem(item.id).itemId)
        assertTrue(EpubSpineItem(item.id).linear)
    }
}
