// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import kotlinx.coroutines.runBlocking
import org.infinilect.app.discovery.*
import kotlin.test.*

/** Original inputs model applied declarations, never downloaded book prose. */
class WikisourceRightsCoverageTest {
    private val rights=WikisourceRights({error("A direct declaration cannot inherit a different page's license")})
    private fun page(text:String,edition:WikisourceEdition)=WikiPage(edition,81,91,0,"Original fixture",CatalogPageKind.TEXT,text)
    @Test fun spanishAndCatalanAppliedDp100AreRecognizedIndependentlyOfTitles()=runBlocking<Unit> {
        for(edition in listOf(WikisourceEdition.ES,WikisourceEdition.CA))for(id in 21..23) {
            val grant=rights.assess(page("{{Encabezado|autor=Original creator}}\nOriginal synthetic prose.\n{{DP-100}}",edition).copy(pageId=id))
            assertTrue(grant.authorized);assertEquals(CatalogRightsState.VERIFIED,grant.state)
            assertEquals("${edition.language}.work.dp100.v1",grant.rule);assertTrue(grant.statements.any{it.contains("curid=$id&oldid=91")})
        }
    }
    @Test fun aModernWorkMayDeclareCopyrightHolderReleaseWithoutAnAgeHeuristic()=runBlocking<Unit> {
        val grant=rights.assess(page("{{header|author=Living original author|year=2026}}\nOriginal synthetic text.\n{{PD-release}}",WikisourceEdition.EN))
        assertTrue(grant.authorized);assertEquals("en.work.release.v1",grant.rule)
    }
    @Test fun translationsEditionsAndArtworkAreNotLicensedByAnOriginalAuthorClaim()=runBlocking<Unit> {
        for(edition in listOf(WikisourceEdition.ES,WikisourceEdition.CA))for(extra in listOf(
            "{{Encabezado|traductor=Protected translator}}","{{Encabezado|notas=Traducción moderna de otra edición}}","{{header|translator=Protected translator}}","{{header|edition=Different edition}}",
            "[[Archivo:Restricted illustration.jpg]]","[[File:Restricted illustration.jpg]]","<img src=\"restricted.png\"/>","{{:Different protected work}}",
            "<ref>[[File:Restricted illustration.jpg]]</ref>","<ref>{{:Different protected work}}</ref>","<ref>Translated by a protected translator</ref>",
            "[[/Different edition chapter]]")) {
            assertFalse(rights.assess(page("$extra\n{{DP-100}}",edition)).authorized,extra)
        }
    }
    @Test fun quotedExamplesDocumentationAndCommentsCannotProvideTheOnlyGrant()=runBlocking<Unit> {
        for(text in listOf("<!-- {{DP-100}} -->","<nowiki>{{DP-100}}</nowiki>","<pre>{{DP-100}}</pre>",
            "<blockquote>\n{{DP-100}}\n</blockquote>","<code>\n{{DP-100}}\n</code>","{{example|{{DP-100}}}}", "[[Plantilla:DP-100]]",
            "This page quotes the words public domain and Creative Commons.","{{DP-AUTOR-100}}"))assertFalse(rights.assess(page(text,WikisourceEdition.ES)).authorized,text)
    }
    @Test fun unrelatedCommentsAndNotesNoLongerRejectAnActualDeclaration()=runBlocking<Unit> {
        val grant=rights.assess(page("<!-- editing note -->\n{{Encabezado|autor=Original creator}}\nOriginal text.<ref>Original reference.</ref>\n{{DP-100}}",WikisourceEdition.ES))
        assertTrue(grant.authorized)
    }
    @Test fun aHeaderMayUseTheReviewedPlainWikipediaLinkWithoutImportingAnotherWork()=runBlocking<Unit> {
        val text="{{header|author=Original creator|notes={{WP link|A related article}}}}\nOriginal text.\n{{PD-old}}"
        assertTrue(rights.assess(page(text,WikisourceEdition.EN)).authorized)
        for(extra in listOf("{{WP link|A related article}}","{{header|notes={{WP link|class=arbitrary|Other}}}}","{{header|notes={{WP link|{{:Other}}}}}}"))
            assertFalse(rights.assess(page("$extra\n{{PD-old}}",WikisourceEdition.EN)).authorized)
    }
    @Test fun contradictoryAndTerritorialOnlyStatementsNeverBecomeUniversalAuthorization()=runBlocking<Unit> {
        for(text in listOf("{{DP-100}}\n{{copyvio}}","{{DP-100}}\n{{CC-BY-NC-4.0}}","{{DP-100}}\n{{DP-1923}}","{{DP-España}}","{{DP-1923}}","{{DP-80}}","{{DP-100|scope=original}}"))
            assertFalse(rights.assess(page(text,WikisourceEdition.ES)).authorized,text)
    }
}
