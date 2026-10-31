// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.infinilect.app.discovery.*
import org.infinilect.core.*
import kotlin.test.*

class WikisourceCatalogTest {
    @Test fun namespacesDisambiguationAndVersionsRemainCatalogPagesEvenWithALicenseTemplate()=runBlocking<Unit> {
        for(edition in WikisourceEdition.entries) {
            val f=WikisourceSourceTest.Fixture(edition)
            try {
                val cases=listOf(
                    f.row(101,edition.authorNamespace,"Author:Original",true,"{{PD-old}}") to CatalogPageKind.AUTHOR,
                    f.row(101,edition.indexNamespace,"Index:Original",true,"{{PD-old}}") to CatalogPageKind.INDEX,
                    JsonObject(f.row(101,0,f.title,true,"{{PD-old}}").toMutableMap().apply {put("pageprops",buildJsonObject{put("disambiguation","")})}) to CatalogPageKind.DISAMBIGUATION,
                    JsonObject(f.row(101,0,f.title,true,"{{PD-old}}").toMutableMap().apply {put("categories",buildJsonArray{add(buildJsonObject{put("title",if(edition==WikisourceEdition.EN)"Category:Versions pages" else "Category:Versions")})})}) to CatalogPageKind.VERSIONS)
                for((row,kind) in cases) {
                    f.clock+=121001;f.metadataBody=f.response(listOf(row))
                    val info=f.source.catalogInfo(f.id);assertEquals(kind,info.kind);assertEquals(CatalogEpubState.WEB_ONLY,info.epubState);assertFalse(info.mayRead)
                }
                assertEquals(0,f.exports.get())
            }finally{f.close()}
        }
    }
    @Test fun titleNamespaceAndHeaderDoNotProveCompleteBooks()=runBlocking<Unit> {
        val f=WikisourceSourceTest.Fixture()
        try {
            for(text in listOf("", "{{header|title=Complete works}}", "{{header|title=Novel|author=Original creator}}\n{{PD-old}}")) {
                f.text=text;f.clock+=121001
                val publication=assertNotNull(f.source.getPublication(f.id));assertEquals(PublicationType.DOCUMENT,publication.type)
                assertEquals(CatalogPageKind.UNKNOWN,f.source.catalogInfo(f.id).kind)
            }
            f.text="{{header|section=Section one|author=Original creator}}\n{{PD-old}}";f.clock+=121001
            assertEquals(CatalogPageKind.CHAPTER,f.source.catalogInfo(f.id).kind)
        }finally{f.close()}
    }
    @Test fun selectedMetadataCacheHasABoundedNumberOfEntriesAndNoBookTextRetention()=runBlocking<Unit> {
        val f=WikisourceSourceTest.Fixture();var calls=0;var clock=0L
        val catalog=WikisourceCatalog(WikisourceEdition.EN,{url->
            calls++;val number=io.ktor.http.Url(url).parameters["pageids"]!!.toInt()
            f.response(listOf(f.row(number,0,"Work $number",true,"Original source text $number"))).encodeToByteArray()
        },{clock},{WikiGrant()})
        try {
            for(number in 1..40)assertTrue(catalog.record(PublicationId(WikisourceEdition.EN.id,number.toString())).page.wikitext.isEmpty())
            assertEquals(40,calls);catalog.record(PublicationId(WikisourceEdition.EN.id,"40"));assertEquals(40,calls)
            catalog.record(PublicationId(WikisourceEdition.EN.id,"1"));assertEquals(41,calls)
            clock=121001;catalog.record(PublicationId(WikisourceEdition.EN.id,"40"));assertEquals(42,calls)
        }finally{f.close()}
    }
    @Test fun invalidPageIdsCannotChangeNamespaceHostOrQueryParameters()=runBlocking<Unit> {
        val f=WikisourceSourceTest.Fixture()
        try {
            for(id in listOf("0","-1","001","1&titles=Other","https://localhost","2147483648"))assertFails{f.source.getPublication(PublicationId(f.edition.id,id))}
            assertFails{f.source.getPublication(PublicationId(WikisourceEdition.CA.id,"101"))};assertEquals(0,f.requests.get())
        }finally{f.close()}
    }
    @Test fun officialIdentityUsesStrictUtf8AndNeverAcceptsAnUnconfiguredOrigin() {
        assertEquals("Résumé 📚",wikiTitleFromUrl(WikisourceEdition.EN.canonical("Résumé 📚"),WikisourceEdition.EN))
        for(uri in listOf("https://en.wikisource.org/wiki/%FF", "https://en.wikisource.org/wiki/%C0%AF", "https://en.wikisource.org/wiki/%00",
            "https://en.wikisource.org@localhost/wiki/Work", "https://127.0.0.1/wiki/Work", "http://en.wikisource.org/wiki/Work", "https://en.wikisource.org/wiki/Work?host=localhost"))assertNull(wikiTitleFromUrl(uri,WikisourceEdition.EN))
    }
    @Test fun continuationCannotRepeatGoBackOrRunWithoutBoundedPageMetadata()=runBlocking<Unit> {
        val f=WikisourceSourceTest.Fixture()
        try {
            f.next=20;val token=f.source.search("book").nextPageToken!!
            f.next=20;assertFails{f.source.search("book",token)}
            f.next=19;assertFails{f.source.search("book",token)}
            f.next=null;f.rows=List(21){it+1};assertFails{f.source.search("book")}
        }finally{f.close()}
    }
}
