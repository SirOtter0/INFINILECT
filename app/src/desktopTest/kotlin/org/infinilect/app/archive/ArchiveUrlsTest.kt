// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import kotlin.test.*

class ArchiveUrlsTest {
    @Test fun fileUrlUsesTheOfficialPermanentPathAndEscapesSpaces() {
        assertEquals("https://archive.org/download/item/file%20name.txt", ArchiveUrls.download("item", "file name.txt"))
        assertEquals("https://archive.org/metadata/item", ArchiveUrls.metadata("item"))
    }

    @Test fun identifiersAndNamesRejectTraversalSchemesAndEncodedSeparators() {
        listOf("../bad", "a/b", "a\\b", "a%2fb", "ftp:bad", "https://evil.example", "a..b", "x\n", "").forEach {
            assertFailsWith<IllegalArgumentException> { ArchiveUrls.identifier(it) }
            assertFailsWith<IllegalArgumentException> { ArchiveUrls.filename(it) }
        }
    }

    @Test fun redirectAcceptsOnlyTheObservedHostAndExactItemFilePath() {
        val target = "https://${ArchiveUrls.DELIVERY_HOST}/0/items/item/file.txt"
        assertEquals(target, ArchiveUrls.redirect(ArchiveUrls.download("item", "file.txt"), target, "item", "file.txt"))
        assertEquals(ArchiveUrls.download("item", "file.txt"), ArchiveUrls.redirect(target, "https://archive.org/download/item/file.txt", "item", "file.txt"))
    }

    @Test fun unsafeRedirectsFailClosed() {
        val base = "https://${ArchiveUrls.DELIVERY_HOST}/0/items/item/file.txt"
        listOf(
            "http://${ArchiveUrls.DELIVERY_HOST}/0/items/item/file.txt", "file:///etc/passwd", "ftp://archive.org/file.txt",
            "https://evil.example/0/items/item/file.txt", "https://archive.org.evil.example/download/item/file.txt",
            "https://user@${ArchiveUrls.DELIVERY_HOST}/0/items/item/file.txt", "$base#fragment", "$base?token=1",
            "https://${ArchiveUrls.DELIVERY_HOST}:444/0/items/item/file.txt", "https://${ArchiveUrls.DELIVERY_HOST}/0/items/other/file.txt",
            "https://${ArchiveUrls.DELIVERY_HOST}/0/items/item/../item/file.txt", "https://${ArchiveUrls.DELIVERY_HOST}/0/items/item/%2e%2e/file.txt",
            "https://${ArchiveUrls.DELIVERY_HOST}/0/items/item%2ffile.txt", "https://${ArchiveUrls.DELIVERY_HOST}/0/items/item/file%252etxt",
        ).forEach { assertFails { ArchiveUrls.redirect(ArchiveUrls.download("item", "file.txt"), it, "item", "file.txt") } }
    }

    @Test fun invalidMismatchedAndOversizedPageTokensAreRejected() {
        listOf("https://evil.example", "ia1.!!!", ArchiveUrls.token("other", 2), ArchiveUrls.token("q", 1001), "x".repeat(1601)).forEach {
            assertFailsWith<IllegalArgumentException> { ArchiveUrls.page(it, "q") }
        }
        assertEquals(3, ArchiveUrls.page(ArchiveUrls.token("q", 3), "q"))
    }
}
