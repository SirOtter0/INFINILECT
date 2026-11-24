// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.file.Files
import kotlinx.coroutines.*
import org.infinilect.app.discovery.*
import org.infinilect.app.epub.*
import org.infinilect.app.downloads.*
import kotlin.test.*

class WikisourceAvailabilityTest {
    private fun book()=EpubFixture().apply {opf{it.replace("urn:synthetic:foundation","https://en.wikisource.org/wiki/Original_work")
        .replace("Résumé 📚","Original work").replace("<dc:language>fr</dc:language><dc:language>en</dc:language>","<dc:language>en</dc:language>")}}.zip()
    private suspend fun withSource(body:suspend(WikisourceSourceTest.Fixture,FileEpubPreparer)->Unit) {
        val root=Files.createTempDirectory("wiki-availability");val f=WikisourceSourceTest.Fixture(zip=book())
        val preparer=FileEpubPreparer(root,validateDocument=f.source::validateDocument)
        f.source.epubInspector{pub,res,loader->preparer.prepare(pub,res,loader).close()}
        try{body(f,preparer)}finally{f.close();preparer.close();preparer.awaitClosed();assertTrue(payloads(root).isEmpty());root.toFile().deleteRecursively()}
    }
    @Test fun resourceCandidatesAndUncheckedFormatExistWithoutGrantingRightsOrFetchingBytes()=runBlocking<Unit> {
        withSource{f,_->
            f.text="{{header|author=Original creator}}"
            val details=f.source.catalogInfo(f.id)
            assertEquals(CatalogEpubState.POTENTIAL,details.epubState);assertEquals(CatalogRightsState.UNKNOWN,details.rightsState)
            assertEquals(1,details.metadata!!.resources.size);assertFalse(details.mayRead)
            val checked=f.source.checkEpub(f.id);assertEquals(CatalogEpubState.POTENTIAL,checked.epubState);assertFalse(checked.authorized);assertEquals(0,f.exports.get())
            assertFails{f.source.loadResource(details.metadata.resources.single())};assertEquals(0,f.exports.get())
        }
    }
    @Test fun metadataDoesNotExportAndAnExplicitCheckUsesTheRealPreparer()=runBlocking<Unit> {
        withSource{f,_->
            assertEquals(CatalogEpubState.POTENTIAL,f.source.catalogInfo(f.id).epubState);assertEquals(0,f.exports.get())
            val info=f.source.checkEpub(f.id);assertEquals(CatalogEpubState.VALIDATED,info.epubState);assertTrue(info.mayDownload);assertEquals(1,f.exports.get())
            f.exportBytes="not a ZIP".encodeToByteArray()
            val bad=f.source.checkEpub(f.id);assertEquals(CatalogEpubState.INCOMPATIBLE,bad.epubState);assertEquals(CatalogRightsState.VERIFIED,bad.rightsState);assertFalse(bad.mayRead)
            f.exportBytes=book();assertEquals(CatalogEpubState.VALIDATED,f.source.checkEpub(f.id).epubState)
        }
    }
    @Test fun refreshingAnUnresolvedGrantCanDiscoverRightsWithoutExporting()=runBlocking<Unit> {
        withSource{f,_->
            f.text="{{header|author=Original creator}}"
            assertFalse(f.source.catalogInfo(f.id).authorized)
            f.text="{{header|author=Original creator}}\n{{PD-old}}"
            val refreshed=f.source.refreshCatalogInfo(f.id)
            assertTrue(refreshed.authorized);assertEquals(CatalogEpubState.POTENTIAL,refreshed.epubState)
            assertEquals(0,f.exports.get());assertTrue(f.contents.get()>=2)
            assertEquals(CatalogEpubState.VALIDATED,f.source.checkEpub(f.id).epubState);assertEquals(1,f.exports.get())
        }
    }
    @Test fun cancellingAUserCheckRetiresTheExportAndNeverMarksItValidated()=runBlocking<Unit> {
        withSource{f,_->
            val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
            f.beforeExport={entered.complete(Unit);try{awaitCancellation()}finally{cancelled.complete(Unit)}}
            val job=launch{f.source.checkEpub(f.id)};withTimeout(5000){entered.await()};job.cancelAndJoin();withTimeout(5000){cancelled.await()}
            assertNotEquals(CatalogEpubState.VALIDATED,f.source.catalogInfo(f.id).epubState)
        }
    }
    @Test fun anHttpFailureIsTemporaryAndDoesNotErasePositiveRightsEvidence()=runBlocking<Unit> {
        withSource{f,_->
            f.exportStatus=503;val info=f.source.checkEpub(f.id)
            assertEquals(CatalogEpubState.TEMPORARY_ERROR,info.epubState);assertEquals(CatalogRightsState.VERIFIED,info.rightsState)
            f.exportStatus=404;assertEquals(CatalogEpubState.UNAVAILABLE,f.source.checkEpub(f.id).epubState)
        }
    }
    @Test fun aLateInspectorResultCannotPublishValidationAfterNavigationCancelsIt()=runBlocking<Unit> {
        val f=WikisourceSourceTest.Fixture()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.source.epubInspector{_,_,_->withContext(NonCancellable){entered.complete(Unit);release.await()}}
        try {
            val job=launch{f.source.checkEpub(f.id)}
            withTimeout(5000){entered.await()};job.cancel();release.complete(Unit);job.join()
            assertTrue(job.isCancelled);assertEquals(CatalogEpubState.POTENTIAL,f.source.catalogInfo(f.id).epubState)
            assertEquals(0,f.exports.get())
        }finally{release.complete(Unit);f.close()}
    }
    @Test fun strictSearchReusesOnlyCurrentValidationAndNeverExportsByItself()=runBlocking<Unit> {
        withSource { f,_ ->
            f.rows=listOf(101)
            val query=DiscoveryRequest("Original")
            val first=f.source.discoverReadable(query,null)
            assertTrue(first.entries.isEmpty())
            val candidate=first.candidates.single()
            assertEquals(0,f.exports.get())
            assertEquals(CatalogEpubState.VALIDATED,f.source.checkEpub(f.id).epubState)
            assertEquals(CatalogReadability.READABLE_CONFIRMED,f.source.cachedReadability(candidate.publication))
            assertEquals(1,f.source.discoverReadable(query,null).entries.size)
            assertEquals(1,f.exports.get())
            f.revision++
            f.clock+=121000
            assertTrue(f.source.discoverReadable(query,null).entries.isEmpty())
            assertEquals(CatalogReadability.UNVERIFIED,f.source.cachedReadability(candidate.publication))
            assertEquals(1,f.exports.get())
        }
    }

    @Test fun authorizedColdCandidateUsesRealPreparerAndWarmResultUsesNoMoreBytes() = runBlocking<Unit> {
        withSource { f, _ ->
            f.rows = listOf(101)
            val query = DiscoveryRequest("Original work")
            val candidate = f.source.discoverReadable(query, null).candidates.single()
            assertEquals(0, f.exports.get())
            assertEquals(CatalogReadability.READABLE_CONFIRMED, f.source.verifyReadable(candidate.publication))
            assertEquals(1L to f.zip.size.toLong(), f.source.automaticTraffic())
            assertEquals(CatalogReadability.READABLE_CONFIRMED, f.source.verifyReadable(candidate.publication))
            assertEquals(1, f.exports.get())
            assertEquals(candidate.publication.id, f.source.discoverReadable(query, null).entries.single().publication.id)
        }
    }

    @Test fun oversizedAdvertisedAndUnannouncedBodiesStayPendingWithoutPoisoningManualCheck() = runBlocking<Unit> {
        withSource { f, _ ->
            val publication = assertNotNull(f.source.catalogInfo(f.id).metadata)
            val cap = 512 * 1024
            f.exportLength = (cap + 1).toLong()
            assertEquals(CatalogReadability.READABLE_CANDIDATE, f.source.verifyReadable(publication))
            assertEquals(1L to 0L, f.source.automaticTraffic())
            f.omitExportLength = true
            f.exportBytes = ByteArray(cap + 4096)
            assertEquals(CatalogReadability.READABLE_CANDIDATE, f.source.verifyReadable(publication))
            assertEquals(2L to cap.toLong(), f.source.automaticTraffic())
            f.exportBytes = book()
            assertEquals(CatalogEpubState.VALIDATED, f.source.checkEpub(f.id).epubState)
        }
    }

    @Test fun revokedAuthorizationAndRevisionAreCheckedBeforeAnyExport() = runBlocking<Unit> {
        withSource { f, _ ->
            val publication = assertNotNull(f.source.catalogInfo(f.id).metadata)
            f.text = "{{header|author=Original creator}}"
            f.revision++
            assertEquals(CatalogReadability.UNVERIFIED, f.source.verifyReadable(publication))
            assertEquals(0, f.exports.get())
        }
    }

    @Test fun manualInspectionJoinsAnAutomaticSuccessForTheSameRevision() = runBlocking<Unit> {
        withSource { f, _ ->
            val publication = assertNotNull(f.source.catalogInfo(f.id).metadata)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeExport = { started.complete(Unit); release.await() }
            val automatic = async { f.source.verifyReadable(publication) }
            withTimeout(5000) { started.await() }
            val manual = async(start = CoroutineStart.UNDISPATCHED) { f.source.checkEpub(f.id) }
            release.complete(Unit)
            assertEquals(CatalogReadability.READABLE_CONFIRMED, automatic.await())
            assertEquals(CatalogEpubState.VALIDATED, manual.await().epubState)
            assertEquals(1, f.exports.get())
        }
    }

    @Test fun automaticFailuresDistinguishTemporaryOutageFromAnInvalidEpub() = runBlocking<Unit> {
        withSource { f, _ ->
            val publication = assertNotNull(f.source.catalogInfo(f.id).metadata)
            f.exportStatus = 503
            assertEquals(CatalogReadability.TEMPORARY_ERROR, f.source.verifyReadable(publication))
            assertEquals(CatalogReadability.TEMPORARY_ERROR, f.source.verifyReadable(publication))
            assertEquals(1, f.exports.get())
            f.clock += 30_001
            f.exportStatus = 200
            f.exportBytes = "not an EPUB".encodeToByteArray()
            assertEquals(CatalogReadability.UNAVAILABLE, f.source.verifyReadable(publication))
            assertEquals(2, f.exports.get())
        }
    }
}
