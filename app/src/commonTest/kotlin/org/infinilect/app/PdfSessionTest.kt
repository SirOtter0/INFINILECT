// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.collections.*
import org.infinilect.app.imports.*
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.app.reader.pdf.*
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PdfSessionTest {
    private class Local : PublicationSource,LocalPublicationImporter {
        override val id=SourceId("local-imports")
        val publicationId=PublicationId(id,"digest")
        val pub=Publication(publicationId,"Owned PDF",PublicationType.DOCUMENT,
            resources=listOf(PublicationResource(publicationId,"content",PublicationFormat.PDF,"application/pdf")))
        override suspend fun import(selection:LocalFileSelection)=pub
        override suspend fun getPublication(publicationId:PublicationId)=pub
        override suspend fun search(query:String,pageToken:String?)=SearchPage(listOf(pub))
        override suspend fun loadResource(resource:PublicationResource):ResourceContent=error("fake owned preparer")
        override fun close(){}
        override suspend fun awaitClosed(){}
    }
    @Test fun libraryBeforePreparationHistoryAfterFirstRenderAndBackToLibrary()=runTest {
        val local=Local();val fake=FakeCollections();val document=TestPdfDocument()
        val gate=CompletableDeferred<Unit>();document.action={gate.await()}
        val preparer=object:PdfPreparer {
            override suspend fun prepare(publication:Publication,resource:PublicationResource,loader:ResourceLoader):PdfDocument {
                assertTrue(local.pub.id in fake.saved);assertTrue(fake.opened.isEmpty())
                return document
            }
            override fun close(){}
            override suspend fun awaitClosed(){}
        }
        val owner=ApplicationSources(listOf(SourceOption("Imported files",local,pdfReadingEnabled=true)),
            collections=ApplicationCollections(fake.library,fake.history,StandardTestDispatcher(testScheduler)),
            localImports=local,pdfPreparer=preparer){}
        val session=ApplicationSession(owner,this,StandardTestDispatcher(testScheduler)){10L}
        try {
            session.navigate(Destination.LIBRARY)
            session.importLocal(object:LocalFilePicker {override suspend fun pick()=LocalFileSelection("ignored.pdf"){error("fake importer")}})
            runCurrent();assertIs<OpenPublicationState.Loading>(session.opening.value);assertTrue(fake.opened.isEmpty())
            gate.complete(Unit);advanceUntilIdle()
            assertIs<OpenPublicationState.PdfReady>(session.opening.value);assertTrue(local.pub.id in fake.opened)
            session.back();advanceUntilIdle();assertEquals(Destination.LIBRARY,session.destination.value)
            assertEquals(1,document.closes)
            fake.library.remove(local.pub.id);fake.history.clear()
            assertEquals(local.pub,local.getPublication(local.pub.id))
        } finally {session.close();owner.close();owner.awaitProgressClosed()}
    }
    @Test fun cancelledInitialRenderingDoesNotDeliverOrRecordHistory()=runTest {
        val local=Local();val fake=FakeCollections();val document=TestPdfDocument()
        val gate=CompletableDeferred<Unit>();document.action={withContext(NonCancellable){gate.await()}}
        val preparer=object:PdfPreparer {
            override suspend fun prepare(publication:Publication,resource:PublicationResource,loader:ResourceLoader)=document
            override fun close(){}
            override suspend fun awaitClosed(){}
        }
        val owner=ApplicationSources(listOf(SourceOption("Imported files",local,pdfReadingEnabled=true)),
            collections=ApplicationCollections(fake.library,fake.history,StandardTestDispatcher(testScheduler)),pdfPreparer=preparer){}
        val session=ApplicationSession(owner,this)
        try {
            session.openSearch(local.pub);runCurrent();session.back();gate.complete(Unit);advanceUntilIdle()
            assertIs<OpenPublicationState.Idle>(session.opening.value);assertTrue(fake.opened.isEmpty())
            assertEquals(1,document.closes);document.rasters.forEach {assertFailsWith<PdfException>{it.argb}}
        } finally {session.close();owner.close();owner.awaitProgressClosed()}
    }
}
