// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import kotlinx.coroutines.runBlocking
import org.infinilect.app.discovery.*
import kotlin.test.*

class WikisourceRightsTest {
    private fun page(text:String,edition:WikisourceEdition=WikisourceEdition.EN,kind:CatalogPageKind=CatalogPageKind.UNKNOWN)=WikiPage(edition,101,77,0,"Any original title",kind,text)
    @Test fun directSourceDeclarationDoesNotDependOnARegisteredTitleRevisionOrChapterCount()=runBlocking<Unit> {
        val rights=WikisourceRights({error("Direct source evidence needs no other endpoint")})
        for(id in listOf(1,1425,999999)) {
            val grant=rights.assess(page("{{header|author=Original creator}}\n{{PD-old}}").copy(pageId=id,revision=id+1,title="Work $id"))
            assertTrue(grant.authorized);assertEquals("{{PD-old}}",grant.statements.first());assertTrue(grant.statements.any{it.contains("curid=$id&oldid=${id+1}")})
        }
    }
    @Test fun footerExamplesConflictingRestrictionsAndUnknownRightsNeverGrant()=runBlocking<Unit> {
        val rights=WikisourceRights({error("No scan to look up")})
        for(text in listOf("{{header|author=Original creator}}", "{{PD-old|author-only=yes}}", "{{PD-US}}", "Example {{PD-old}}",
            "<!-- {{PD-old}} -->", "<nowiki>{{PD-old}}</nowiki>", "{{example|{{PD-old}}}}", "{{PD-old}}\n{{copyvio}}",
            "{{PD-old}}\n{{PD-old-80}}", "{{header|translator=Living translator}}\n{{PD-old}}", "{{header|edition=Modern edition}}\n{{PD-old}}"))
            assertFalse(rights.assess(page(text)).authorized,text)
        for(kind in listOf(CatalogPageKind.AUTHOR,CatalogPageKind.INDEX,CatalogPageKind.VERSIONS,CatalogPageKind.DISAMBIGUATION))assertFalse(rights.assess(page("{{PD-old}}",kind=kind)).authorized)
        assertFalse(rights.assess(page("{{PD-old}}",WikisourceEdition.ES)).authorized)
    }
    @Test fun scanEvidenceUsesOfficialIndexAndExactCommonsFileWithoutFetchingAnyScan()=runBlocking<Unit> {
        for(edition in listOf(WikisourceEdition.ES,WikisourceEdition.CA)) {
            val f=WikisourceSourceTest.Fixture(edition)
            try {
                val publication=assertNotNull(f.source.getPublication(f.id))
                assertEquals(1,publication.resources.size);assertEquals(1,f.commons.get());assertEquals(0,f.exports.get())
                assertTrue(publication.rights!!.contains("{{PD-scan|PD-old-100-expired}}"));assertTrue(publication.rights!!.contains("commons.wikimedia.org/w/index.php?curid="))
            }finally{f.close()}
        }
    }
    @Test fun shorterExpiryAmbiguousAutoExpiryAndCopyrightNoticesCannotAuthorizeAScan()=runBlocking<Unit> {
        for(license in listOf("{{PD-old-80}}","{{PD-scan|PD-old-80}}","{{PD-scan|PD-old-auto-expired|deathyear=1937}}",
            "{{PD-scan|PD-old-100-expired}}\n{{no permission}}","{{PD-scan|PD-old-100-expired|scope=author}}",
            "<nowiki>{{PD-scan|PD-old-100-expired}}</nowiki>","{{example|{{PD-scan|PD-old-100-expired}}}}")) {
            val f=WikisourceSourceTest.Fixture(WikisourceEdition.ES)
            try {f.commonsLicense=license;assertFalse(f.source.catalogInfo(f.id).authorized,license);assertEquals(0,f.exports.get())}
            finally{f.close()}
        }
    }
    @Test fun aTocMultipleScansOrArbitraryTransclusionNeedsAdditionalEvidence()=runBlocking<Unit> {
        val rights=WikisourceRights({error("Unproven graph must not be traversed")})
        for(text in listOf("<pages index=\"First.djvu\" from=1 to=2/>\n[[/Chapter]]",
            "<pages index=\"First.djvu\" from=1 to=2/><pages index=\"Second.djvu\" from=1 to=2/>",
            "<pages index=\"First.djvu\" from=1 to=2/>{{:Other work}}", "<pages index=\"../escape.djvu\" from=1 to=2/>",
            "<pages index=\"https://localhost/private.djvu\" from=1 to=2/>"))assertFalse(rights.assess(page(text,WikisourceEdition.ES)).authorized)
    }
    @Test fun declarationsAreBoundedAndMetadataMarkupCannotBecomeAnAuthor() {
        assertEquals("Original creator",wikiAuthors("{{header|author=[[Author:Original creator|Original creator]]}}").single())
        assertTrue(wikiAuthors("{{header|author={{arbitrary|value}}}}").isEmpty())
        assertFailsWith<WikisourceInvalid>{wikiDeclarations("{{".repeat(25))}
        assertFailsWith<WikisourceInvalid>{wikiDeclarations("x".repeat(524289))}
        assertFailsWith<WikisourceInvalid>{wikiDeclarations("<!--".repeat(10000))}
        assertFailsWith<WikisourceInvalid>{wikiDeclarations("<nowiki>".repeat(10000))}
        assertTrue(wikiDeclarations("<!-- {{PD-old}} --><nowiki>{{PD-old}}</nowiki>").isEmpty())
    }
}
