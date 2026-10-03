// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.test.*

class LocalCollectionsTest {
    private val id=PublicationId(SourceId("source"),"1")
    @Test fun snapshotKeepsNamespacedIdentityAndOriginalMetadataWithoutResources() {
        val p=Publication(id,"Title",PublicationType.BOOK,listOf("Author"),
            listOf(PublicationResource(id,"resource",PublicationFormat.TEXT,"text/plain")),listOf("en"),"https://example.org","CC0")
        val s=PublicationSnapshot.from(p)
        assertEquals(id,s.id); assertEquals(p.authors,s.authors); assertEquals(p.languages,s.languages)
        assertEquals("CC0",s.rights); assertEquals(p.sourceUrl,s.sourceUrl)
        assertNotEquals(s.id,s.id.copy(sourceId=SourceId("other")))
    }
    @Test fun metadataTitleChangesDoNotChangeIdentity() {
        assertEquals(PublicationSnapshot(id,"A",PublicationType.BOOK).id,PublicationSnapshot(id,"B",PublicationType.BOOK,listOf("Other")).id)
    }
    @Test fun boundsRejectOversizedAndBlankMetadata() {
        assertFailsWith<IllegalArgumentException> { PublicationSnapshot(id,"x".repeat(2049),PublicationType.BOOK) }
        assertFailsWith<IllegalArgumentException> { PublicationSnapshot(id," ",PublicationType.BOOK) }
        assertFailsWith<IllegalArgumentException> { PublicationSnapshot(id,"Title",PublicationType.BOOK,List(65){"Author"}) }
        assertFailsWith<IllegalArgumentException> { PublicationSnapshot(id,"Title",PublicationType.BOOK,rights="x".repeat(8193)) }
    }
    @Test fun unsafeDelimitersAreDataButNulAndOversizedIdsAreRejected() {
        val path=id.copy(localId="../../a:b")
        assertEquals(path,PublicationSnapshot(path,"Title",PublicationType.DOCUMENT).id)
        assertFailsWith<IllegalArgumentException> { PublicationSnapshot(id.copy(localId="a\u0000b"),"Title",PublicationType.BOOK) }
        assertFailsWith<IllegalArgumentException> { PublicationSnapshot(id.copy(sourceId=SourceId("x".repeat(129))),"Title",PublicationType.BOOK) }
    }
    @Test fun unicodeCommasAndNewlinesArePreserved() {
        val names=listOf("Last, First","Two\nLines","作者 📚")
        assertEquals(names,PublicationSnapshot(id,"Title",PublicationType.BOOK,names,listOf("en","ja")).authors)
    }
    @Test fun timestampsAreValidatedAndLastOpenedCanBeAbsent() {
        val s=PublicationSnapshot(id,"Title",PublicationType.BOOK)
        assertNull(LibraryEntry(s,0).lastOpenedAtEpochMillis)
        assertFailsWith<IllegalArgumentException> { LibraryEntry(s,-1) }
        assertFailsWith<IllegalArgumentException> { LibraryEntry(s,0,-1) }
        assertFailsWith<IllegalArgumentException> { HistoryEntry(s,-1) }
    }
    @Test fun snapshotCopiesMutablePublicationLists() {
        val names=mutableListOf("Original"); val s=PublicationSnapshot.from(Publication(id,"Title",PublicationType.BOOK,names))
        names.clear(); assertEquals(listOf("Original"),s.authors)
    }
}
