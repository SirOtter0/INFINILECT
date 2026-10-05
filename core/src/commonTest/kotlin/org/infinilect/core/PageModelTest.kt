// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

import kotlin.test.*

class PageModelTest {
    private val id = PublicationId(SourceId("comic"), "chapter")
    private val resource = PublicationResource(id, "page", PublicationFormat.PAGES, "image/png", pageDimensions = PageDimensions(512, 768))
    @Test fun comicSemanticsAreIndependentOfTransport() {
        val publication = Publication(id, "Original", PublicationType.COMIC, resources = listOf(resource))
        assertEquals(PublicationType.COMIC, publication.type); assertEquals(PublicationFormat.PAGES, resource.format)
        assertNull(resource.revision); assertNull(resource.cacheKey)
    }
    @Test fun pageDimensionsAndFormatAreValidated() {
        for ((w,h) in listOf(0 to 1, 1 to -1, 16385 to 1)) assertFailsWith<IllegalArgumentException> { PageDimensions(w,h) }
        assertFailsWith<IllegalArgumentException> { resource.copy(format = PublicationFormat.TEXT) }
        assertFailsWith<IllegalArgumentException> { PageEntry(resource.copy(pageDimensions = null)) }
    }
    @Test fun publicationOrderRemainsLogicalPageOrder() {
        val pages = listOf(resource.copy(key="z"), resource.copy(key="a"), resource.copy(key="10"))
        assertEquals(listOf("z","a","10"), Publication(id,"Title",PublicationType.COMIC,resources=pages).resources.map { it.key })
    }
    @Test fun pageLocatorIsBoundedAndTyped() {
        for ((key,index,fraction) in listOf(Triple("",0,0.0), Triple("x".repeat(513),0,0.0), Triple("p",-1,0.0), Triple("p",100000,0.0), Triple("p",0,Double.NaN), Triple("p",0,1.1)))
            assertFailsWith<IllegalArgumentException> { ReadingLocator.Page(key,index,fraction) }
        val locator=ReadingLocator.Page("p",3,.5)
        assertFailsWith<IllegalArgumentException> { ReadingProgress(ReadingProgressId(id,"pages",PublicationFormat.TEXT),locator,.5,1) }
        assertEquals(locator, ReadingProgress(ReadingProgressId(id,"pages",PublicationFormat.PAGES),locator,.5,1).locator)
    }
    @Test fun scopedIdentityCannotAliasAndHistoricalLocatorsRemainValid() {
        val key=ReadingProgressId(id,"sequence",PublicationFormat.PAGES)
        assertNotEquals(key,key.copy(publicationId=id.copy(sourceId=SourceId("other"))))
        assertNotEquals(key,key.copy(publicationId=id.copy(localId="other")))
        assertNotEquals(key,key.copy(resourceKey="other"))
        assertEquals(1, ReadingLocator.Text(1,2).codePointOffset)
        assertEquals(2, ReadingLocator.Epub(EpubEntryPath("a.xhtml"),listOf(0),2,.3).codePointOffset)
    }
}
