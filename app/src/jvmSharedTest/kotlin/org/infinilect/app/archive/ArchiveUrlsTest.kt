// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import kotlin.test.*

class ArchiveUrlsTest {
    private val primary = ArchiveUrls.storageLocation("ia600308.us.archive.org", "/35/items/item", "item")
    private val alternate = ArchiveUrls.storageLocation(FIXTURE_STORAGE_HOST, "/0/items/item", "item")
    private val locations = setOf(primary, alternate)
    private fun validate(target: String, current: String = ArchiveUrls.download("item", "file.txt"), allowed: Set<ArchiveLocation> = locations) =
        ArchiveUrls.redirect(current, target, "item", "file.txt", allowed)

    @Test fun fileUrlUsesTheOfficialPermanentPathAndEscapesSpaces() {
        assertEquals("https://archive.org/download/item/file%20name.txt", ArchiveUrls.download("item", "file name.txt"))
        assertEquals("https://archive.org/metadata/item", ArchiveUrls.metadata("item"))
        assertEquals(ArchiveUrls.download("item", "file.txt"), validate(ArchiveUrls.download("item", "file.txt"), allowed = emptySet()))
    }

    @Test fun identifiersAndNamesRejectTraversalSchemesAndEncodedSeparators() {
        listOf("../bad", "a/b", "a\\b", "a%2fb", "ftp:bad", "https://evil.example", "a..b", "x\n", "").forEach {
            assertFailsWith<IllegalArgumentException> { ArchiveUrls.identifier(it) }
            assertFailsWith<IllegalArgumentException> { ArchiveUrls.filename(it) }
        }
    }

    @Test fun declaredPrimaryAndAlternateLocationsAcceptTheirExactFilePaths() {
        for (coordinate in locations) {
            val target = "https://${coordinate.host}${coordinate.directory}/file.txt"
            assertEquals(target, validate(target))
            assertEquals(ArchiveUrls.download("item", "file.txt"), validate(ArchiveUrls.download("item", "file.txt"), target))
        }
        val other = ArchiveUrls.storageLocation("ia600309.us.archive.org", "/7/items/item", "item")
        assertEquals("https://${other.host}${other.directory}/file.txt", validate("https://${other.host}${other.directory}/file.txt", allowed = setOf(other)))
    }

    @Test fun anOfficialDomainAloneDoesNotAuthorizeAStorageHostOrDirectory() {
        assertFails { validate("https://ia600309.us.archive.org/35/items/item/file.txt") }
        assertFails { validate("https://$FIXTURE_STORAGE_HOST/35/items/item/file.txt") }
        assertFails { validate("https://$FIXTURE_STORAGE_HOST/0/items/item/file.txt", allowed = emptySet()) }
    }

    @Test fun unrelatedAndSuffixConfusionHostsAreRejected() {
        for (host in listOf("evil.example", "evilarchive.org", "archive.org.attacker.example", "foo.archive.org.evil.example", "archive.org.", "127.0.0.1")) {
            assertFails { validate("https://$host/0/items/item/file.txt") }
            assertFails { ArchiveUrls.storageLocation(host, "/0/items/item", "item") }
        }
    }

    @Test fun punycodeUnicodeAndMalformedHostnamesFailClosed() {
        for (host in listOf("xn--archve-9za.archive.org", "archіve.org", "-node.archive.org", "node-.archive.org", "node..archive.org", "node%2earchive.org", "node:443.archive.org")) {
            assertFails { validate("https://$host/0/items/item/file.txt") }
            assertFails { ArchiveUrls.storageLocation(host, "/0/items/item", "item") }
        }
    }

    @Test fun insecureSchemesUserinfoPortsQueriesAndFragmentsAreRejected() {
        val path = "$FIXTURE_STORAGE_HOST/0/items/item/file.txt"
        for (target in listOf("http://$path", "file:///etc/passwd", "ftp://$path", "javascript:alert(1)",
            "https://user@$path", "https://user%40archive.org@$path", "https://$FIXTURE_STORAGE_HOST:444/0/items/item/file.txt",
            "https://$path?q=1", "https://$path#fragment")) assertFails { validate(target) }
    }

    @Test fun traversalEncodingAndWrongPublicationOrFilenameAreRejected() {
        for (path in listOf("/0/items/other/file.txt", "/0/items/item/other.txt", "/0/items/item/../item/file.txt",
            "/0/items/item/%2e%2e/file.txt", "/0/items/item/%252e%252e/file.txt", "/0/items/item%2ffile.txt",
            "/0/items/item/%2566ile.txt", "/0/items/item/fi%6ce.txt", "/0/items/item/file.txt/")) assertFails { validate("https://$FIXTURE_STORAGE_HOST$path") }
        assertFails { validate("../0/items/item/file.txt") }
    }

    @Test fun hostCaseAndDefaultPortAreCanonicalizedForLoopDetection() {
        assertEquals("https://$FIXTURE_STORAGE_HOST/0/items/item/file.txt", validate("https://${FIXTURE_STORAGE_HOST.uppercase()}:443/0/items/item/file.txt"))
        assertEquals(ArchiveUrls.download("item", "file.txt"), validate("https://ARCHIVE.ORG:443/download/item/file.txt"))
    }

    @Test fun escapedSpacesAreAcceptedOnlyInTheCorrectResourceSegment() {
        val target = "https://$FIXTURE_STORAGE_HOST/0/items/item/file%20name.txt"
        assertEquals(target, ArchiveUrls.redirect(ArchiveUrls.download("item", "file name.txt"), target, "item", "file name.txt", locations))
    }

    @Test fun invalidMismatchedAndOversizedPageTokensAreRejected() {
        listOf("https://evil.example", "ia1.!!!", ArchiveUrls.token("other", 2), ArchiveUrls.token("q", 1001), "x".repeat(1601)).forEach {
            assertFailsWith<IllegalArgumentException> { ArchiveUrls.page(it, "q") }
        }
        assertEquals(3, ArchiveUrls.page(ArchiveUrls.token("q", 3), "q"))
    }
}
