// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import org.infinilect.app.ui.*
import kotlin.test.*

class CatalogPublicationPresentationTest {
    @Test fun technicalValidityDoesNotGrantAcquisitionRights() {
        for(state in CatalogEpubState.entries) {
            val info=CatalogPublicationInfo(CatalogPageKind.WORK,state)
            assertFalse(info.mayRead);assertFalse(info.mayDownload)
        }
    }
    @Test fun anAuthorizedExploratoryPageDoesNotBecomeACompleteWork() {
        for(state in listOf(CatalogEpubState.POTENTIAL,CatalogEpubState.VALIDATED)) {
            val info=CatalogPublicationInfo(CatalogPageKind.UNKNOWN,state,authorized=true)
            assertTrue(info.mayRead);assertTrue(info.mayDownload)
            assertEquals(CatalogPageKind.UNKNOWN,info.kind)
        }
        for(state in CatalogEpubState.entries-listOf(CatalogEpubState.POTENTIAL,CatalogEpubState.VALIDATED)) {
            val info=CatalogPublicationInfo(CatalogPageKind.WORK,state,authorized=true)
            assertFalse(info.mayRead);assertFalse(info.mayDownload)
        }
    }
    @Test fun pendingFailedAndCatalogOnlyMetadataDoNotAdvertiseAnAcquisition() {
        for(presentation in listOf(CatalogPresentation(checking=true),CatalogPresentation(failed=true),
            CatalogPresentation(info=CatalogPublicationInfo(CatalogPageKind.AUTHOR,CatalogEpubState.WEB_ONLY)))) {
            val policy=catalogActionPolicy(true,presentation,false)
            assertFalse(policy.mayRead);assertFalse(policy.mayDownload)
        }
    }
    @Test fun retainedReadingSurvivesMetadataFailureWithoutGrantingAnotherDownload() {
        val policy=catalogActionPolicy(true,CatalogPresentation(failed=true),true)
        assertTrue(policy.mayRead);assertFalse(policy.mayDownload)
    }
    @Test fun existingSourcesKeepTheirActionsWithoutAPageMetadataProvider() {
        val policy=catalogActionPolicy(false,CatalogPresentation(),false)
        assertTrue(policy.mayRead);assertTrue(policy.mayDownload)
    }
    @Test fun officialPageActionNeverAcceptsUnsafeSchemesCredentialsOrPrivateSyntax() {
        for(url in listOf("https://es.wikisource.org/wiki/Obra","https://en.wikisource.org/wiki/Author:Original","https://ca.wikisource.org/wiki/Obra_%C3%A9"))assertTrue(safeCatalogOfficialPage(url),url)
        for(url in listOf("http://es.wikisource.org/wiki/Obra","javascript:alert(1)","file:///original","content://original","intent://original","//es.wikisource.org/x",
            "https://user@es.wikisource.org/x","https://es.wikisource.org:444/x","https://127.0.0.1/x","https://[::1]/x","https://host.local/x","https://es.wikisource.org/with space","https://es.wikisource.org/\\x","https://es.wikisource.org/\n","https://es.wikisource.org/"+"a".repeat(2048)))assertFalse(safeCatalogOfficialPage(url),url)
    }
    @Test fun catalanFilterRecognizesDeclaredAliasesWithoutChangingOtherLanguages() {
        assertEquals("Catalan",DISCOVERY_LANGUAGES["ca"])
        assertTrue(languageMatches("ca","ca"));assertTrue(languageMatches("cat","ca"));assertTrue(languageMatches("ca-ES","ca"))
        assertFalse(languageMatches("unknown","ca"));assertFalse(languageMatches("es","ca"));assertFalse(languageMatches("ca","es"))
    }
}
