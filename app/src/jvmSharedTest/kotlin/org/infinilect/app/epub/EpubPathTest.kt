// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import kotlin.test.*
import org.infinilect.app.reader.*
import org.infinilect.core.EpubEntryPath

class EpubPathTest {
    private val base = EpubEntryPath("OPS/Text/chapter.xhtml")
    @Test fun relativeUriReferencesDecodeOnceAndStayWithinArchive() {
        for (href in listOf("../Images/cover image.jpg", "../Images/cover%20image.jpg"))
            assertEquals(EpubEntryPath("OPS/Images/cover image.jpg"), resolveEpubPath(base, href))
        assertEquals(EpubEntryPath("OPS/Text/日本語.xhtml"), resolveEpubPath(base, "%E6%97%A5%E6%9C%AC%E8%AA%9E.xhtml"))
        assertEquals(EpubEntryPath("OPS/Text/a+b.xhtml"), resolveEpubPath(base, "a+b.xhtml"))
        assertEquals(EpubEntryPath("OPS/Text/chapter.xhtml"), resolveEpubPath(base, "./chapter.xhtml"))
    }
    @Test fun ambiguousAndEscapingReferencesFailClosed() {
        for (href in listOf("../../../evil", "%2e%2e/evil", ".%2e/evil", "%252e%252e/evil", "a%2fb", "a%5cb", "a%00b",
            "a%0Ab", "a%FFb", "a%", "a%2", "a%GG", "/evil", "//example.org/a", "C:/evil", "https://example.org/a",
            "..\\evil", "../..\\evil", "a?b", "a#b", "a\u0000b", "a\u0085b", "e\u0301.xhtml")) {
            assertEquals(EpubFailure.INVALID, assertFailsWith<EpubException>(href) { resolveEpubPath(base, href) }.failure)
        }
    }
    @Test fun pathLengthAndDepthStillBounded() {
        assertFailsWith<EpubException> { resolveEpubPath(base, "a".repeat(513)) }
        assertFailsWith<EpubException> { resolveEpubPath(base, List(33) { "a" }.joinToString("/")) }
    }
}
