// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import kotlin.test.*
import org.infinilect.core.*

internal fun archiveFixture(name: String): ByteArray = checkNotNull(ArchiveMetadataTest::class.java.getResourceAsStream("/archive/$name")).use { it.readBytes() }
internal const val FIXTURE_STORAGE_HOST = "dn760105.eu.archive.org"
internal fun itemFixture(change: (String) -> String = { it }) = change(archiveFixture("item.json").toString(Charsets.UTF_8)).toByteArray()

class ArchiveMetadataTest {
    @Test fun mapsIdentityAuthorsLanguagesCanonicalAndOnlySupportedResources() {
        val item = assertNotNull(ArchiveMetadata.item(itemFixture(), "gmb-2015-93040"))
        val publication = item.publication
        assertEquals(PublicationId(SourceId("internet-archive"), "gmb-2015-93040"), publication.id)
        assertEquals(PublicationType.DOCUMENT, publication.type)
        assertEquals(listOf("Nederlandse overheid", "Dutch government", "Heerenveen"), publication.authors)
        assertEquals(listOf("dut", "eng"), publication.languages)
        assertEquals("https://archive.org/details/gmb-2015-93040", publication.sourceUrl)
        assertEquals(listOf(PublicationFormat.TEXT, PublicationFormat.PDF), publication.resources.map { it.format })
        assertEquals(listOf("text/plain", "application/pdf"), publication.resources.map { it.mediaType })
        assertTrue(publication.resources.all { it.publicationId == publication.id && it.revision == null && it.cacheKey == null })
        assertEquals(2566L, item.files.first().size)
    }

    @Test fun keepsRightsAndLicenseSeparateWithoutInventingPublicDomain() {
        val item = assertNotNull(ArchiveMetadata.item(itemFixture { it.replace("\"mediatype\":", "\"rights\":\"Original rights statement\",\"possible-copyright-status\":\"Supplied copyright status\",\"mediatype\":") }, "gmb-2015-93040"))
        assertEquals("Original rights statement", item.rights)
        assertEquals("Original rights statement", item.publication.rights)
        assertEquals("http://creativecommons.org/publicdomain/zero/1.0/", item.licenseUrl)
        assertEquals("Supplied copyright status", item.possibleCopyrightStatus)
    }

    @Test fun incompleteMetadataIsSafeAndMissingItemIsNull() {
        val item = assertNotNull(ArchiveMetadata.item("""{"metadata":{"identifier":"small"},"files":[]}""".toByteArray(), "small"))
        assertEquals("small", item.publication.title)
        assertNull(item.publication.rights)
        assertTrue(item.publication.authors.isEmpty() && item.files.isEmpty())
        assertNull(ArchiveMetadata.item("[]".toByteArray(), "small"))
    }

    @Test fun unknownLicenseAndRestrictedOrLendingItemsHaveNoAcquirableFiles() {
        val transformations: List<(String) -> String> = listOf(
            { it.replace("http://creativecommons.org/publicdomain/zero/1.0/", "https://unknown.example/license") },
            { it.replace("\"mediatype\":", "\"access-restricted-item\":\"true\",\"mediatype\":") },
            { it.replace("\"metadata\":", "\"is_dark\":true,\"metadata\":") },
            { it.replace("\"metadata\":", "\"is_restricted\":true,\"metadata\":") },
            { it.replace("\"metadata\":", "\"nodownload\":true,\"metadata\":") },
            { it.replace("\"metadata\":", "\"is_collection\":true,\"metadata\":") },
            { it.replace("\"metadata\":", "\"lendingInfo\":{},\"metadata\":") },
            { it.replace("dutchgovernmentdocuments", "loggedin") },
            { it.replace("\"mediatype\":", "\"lending_status\":\"AVAILABLE\",\"mediatype\":") },
        )
        transformations.forEach { assertTrue(assertNotNull(ArchiveMetadata.item(itemFixture(it), "gmb-2015-93040")).files.isEmpty()) }
    }

    @Test fun excludesPrivateUnsafeUnknownAndOversizedFiles() {
        for (change in listOf<(String) -> String>(
            { it.replace("\"format\": \"DjVuTXT\"", "\"private\":\"true\",\"format\":\"DjVuTXT\"") },
            { it.replace("\"format\": \"DjVuTXT\"", "\"rights\":\"File-specific rights\",\"format\":\"DjVuTXT\"") },
            { it.replace("\"format\": \"DjVuTXT\"", "\"licenseurl\":\"https://unknown.example/license\",\"format\":\"DjVuTXT\"") },
            { it.replace("gmb-2015-93040_djvu.txt", "../escape.txt") },
            { it.replace("\"2566\"", (MAX_RESOURCE_BYTES + 1).toString()) },
            { it.replace("DjVuTXT", "Unknown") },
            { it.replace("\"2566\"", "\"bad\"") },
        )) assertEquals(listOf(PublicationFormat.PDF), assertNotNull(ArchiveMetadata.item(itemFixture(change), "gmb-2015-93040")).files.map { it.resource.format })
    }

    @Test fun malformedErrorDeepOrWrongIdentityMetadataIsRejected() {
        val inputs = listOf("{", "{\"error\":\"backend error\"}", "{}", "[".repeat(33) + "]".repeat(33), itemFixture().toString(Charsets.UTF_8).replace("gmb-2015-93040\"", "different\""))
        inputs.forEach { assertFailsWith<InvalidArchiveData> { ArchiveMetadata.item(it.toByteArray(), "gmb-2015-93040") } }
        assertFailsWith<InvalidArchiveData> { ArchiveMetadata.item(byteArrayOf(0xff.toByte()), "small") }
    }

    @Test fun searchEmptyOptionalTitleAndOpaquePagination() {
        assertTrue(ArchiveMetadata.search(archiveFixture("empty.json"), "q", 1).publications.isEmpty())
        val page = ArchiveMetadata.search(archiveFixture("search.json"), "q", 1)
        assertEquals(2, ArchiveUrls.page(assertNotNull(page.nextPageToken), "q"))
        assertNull(ArchiveMetadata.search(archiveFixture("search.json"), "q", 2).nextPageToken)
        val noTitle = """{"response":{"numFound":1,"docs":[{"identifier":"small"}]}}"""
        assertEquals("small", ArchiveMetadata.search(noTitle.toByteArray(), "q", 1).publications.single().title)
    }

    @Test fun oversizedAndInvalidSearchResultsAreRejected() {
        assertFailsWith<InvalidArchiveData> { ArchiveMetadata.search(ByteArray(MAX_METADATA_BYTES + 1), "q", 1) }
        assertFailsWith<InvalidArchiveData> { ArchiveMetadata.search("{\"response\":{\"numFound\":-1,\"docs\":[]}}".toByteArray(), "q", 1) }
        assertFailsWith<InvalidArchiveData> { ArchiveMetadata.search("{\"response\":{\"numFound\":1,\"docs\":[{\"identifier\":\"../bad\"}]}}".toByteArray(), "q", 1) }
    }

    @Test fun parserCancellationIsNotReplacedByADataError() {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            ArchiveMetadata.item(itemFixture(), "gmb-2015-93040") { throw kotlinx.coroutines.CancellationException() }
        }
    }

    @Test fun locationsComeOnlyFromRootInfrastructureFieldsForThisItem() {
        val item = assertNotNull(ArchiveMetadata.item(itemFixture {
            it.replace("\"mediatype\":", "\"server\":\"fake.archive.org\",\"dir\":\"/0/items/gmb-2015-93040\",\"mediatype\":")
        }, "gmb-2015-93040"))
        assertEquals(setOf(
            ArchiveLocation("ia903102.us.archive.org", "/35/items/gmb-2015-93040"),
            ArchiveLocation("ia803102.us.archive.org", "/35/items/gmb-2015-93040"),
            ArchiveLocation(FIXTURE_STORAGE_HOST, "/0/items/gmb-2015-93040"),
        ), item.locations)
        assertTrue(assertNotNull(ArchiveMetadata.item("""{"metadata":{"identifier":"small"},"files":[]}""".toByteArray(), "small")).locations.isEmpty())
    }

    @Test fun malformedForeignOrExcessiveLocationMetadataFailsClosed() {
        for (change in listOf<(String) -> String>(
            { it.replace(FIXTURE_STORAGE_HOST, "archive.org.attacker.example") },
            { it.replace(FIXTURE_STORAGE_HOST, "https://$FIXTURE_STORAGE_HOST") },
            { it.replace("/0/items/gmb-2015-93040", "/0/items/another-item") },
            { it.replace("/0/items/gmb-2015-93040", "/0/items/../gmb-2015-93040") },
            { it.replace("\"workable\": [{\"server\":", "\"workable\": [{\"missing\":") },
            { it.replace("\"workable\": [{", "\"workable\": {\"not\":[{").replace("\"/0/items/gmb-2015-93040\"}]}", "\"/0/items/gmb-2015-93040\"}]}}") },
        )) assertFails { ArchiveMetadata.item(itemFixture(change), "gmb-2015-93040") }
        val many = (1..17).joinToString(",") { "\"ia$it.us.archive.org\"" }
        assertFails { ArchiveMetadata.item(itemFixture { it.replace("\"ia903102.us.archive.org\", \"ia803102.us.archive.org\"", many) }, "gmb-2015-93040") }
    }

    @Test fun ambiguousAccessFlagsAreNotTreatedAsExplicitFalse() {
        for (flag in listOf("null", "\"\"", "[]", "{}", "true", "\"unknown\"")) {
            assertTrue(assertNotNull(ArchiveMetadata.item(itemFixture { it.replace("\"metadata\":", "\"nodownload\":$flag,\"metadata\":") }, "gmb-2015-93040")).files.isEmpty())
            val item = assertNotNull(ArchiveMetadata.item(itemFixture { it.replace("\"format\": \"DjVuTXT\"", "\"private\":$flag,\"format\":\"DjVuTXT\"") }, "gmb-2015-93040"))
            assertEquals(listOf(PublicationFormat.PDF), item.files.map { it.resource.format })
        }
        for (flag in listOf("false", "\"false\"", "0", "\"0\"")) assertEquals(2,
            assertNotNull(ArchiveMetadata.item(itemFixture { it.replace("\"metadata\":", "\"nodownload\":$flag,\"metadata\":") }, "gmb-2015-93040")).files.size)
    }
}
