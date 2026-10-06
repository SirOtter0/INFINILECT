// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.collections.*
import org.infinilect.app.imports.*
import org.infinilect.app.reader.OpenPublicationState
import org.infinilect.core.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalImportSessionTest {
    private class Source : PublicationSource, LocalPublicationImporter {
        override val id=SourceId("local-imports")
        val pub=Publication(PublicationId(id,"owned-id"),"Local text",PublicationType.DOCUMENT,
            resources=listOf(PublicationResource(PublicationId(id,"owned-id"),"content",PublicationFormat.TEXT,"text/plain")))
        var resolves=0;var acquisitions=0;var imported=0;var action:suspend ()->Unit={}
        override suspend fun import(selection:LocalFileSelection):Publication { imported++;action();return pub }
        override suspend fun search(query:String,pageToken:String?)=SearchPage(listOf(pub))
        override suspend fun getPublication(publicationId:PublicationId):Publication {resolves++;return pub}
        override suspend fun loadResource(resource:PublicationResource):ResourceContent { acquisitions++;return object:ResourceContent {
            override val sizeBytes=4L;var done=false
            override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {if(done)return -1;done=true;"text".encodeToByteArray().copyInto(buffer,offset);return 4}
            override fun close(){}
        } }
        override fun close(){}
        override suspend fun awaitClosed(){}
    }
    private fun picker(selection: LocalFileSelection?=LocalFileSelection("text.txt"){error("importer owns opening")})=object:LocalFilePicker { override suspend fun pick()=selection }
    private fun TestScope.owner(source:Source,fake:FakeCollections)=ApplicationSources(listOf(SourceOption("Imported files",source,true)),
        collections=ApplicationCollections(fake.library,fake.history,StandardTestDispatcher(testScheduler)),localImports=source){}
    private suspend fun finish(session:ApplicationSession,owner:ApplicationSources){session.close();owner.close();owner.awaitProgressClosed()}

    @Test fun importCommitsLibraryThenReResolvesAndRecordsHistory()=runTest {
        val source=Source();val fake=FakeCollections();val owner=owner(source,fake)
        val session=ApplicationSession(owner,this,StandardTestDispatcher(testScheduler)){10L}
        session.searchSession.value.editQuery("retained query")
        session.importLocal(picker());advanceUntilIdle()
        assertEquals(1,source.imported);assertEquals(1,source.resolves);assertEquals(1,source.acquisitions)
        assertIs<OpenPublicationState.Ready>(session.opening.value);assertTrue(source.pub.id in fake.saved);assertTrue(source.pub.id in fake.opened)
        session.back();advanceUntilIdle();assertEquals("retained query",session.searchSession.value.query.value)
        finish(session,owner)
    }
    @Test fun pickerCancellationDoesNotAcquireOrMutateCollections()=runTest {
        val source=Source();val fake=FakeCollections();val owner=owner(source,fake);val session=ApplicationSession(owner,this)
        session.importLocal(picker(null));advanceUntilIdle()
        assertEquals(0,source.imported);assertTrue(fake.saved.isEmpty());assertTrue(fake.opened.isEmpty());assertFalse(session.handlesBack())
        finish(session,owner)
    }
    @Test fun failedLibraryWriteCannotLookLikePersistentLibrarySuccess()=runTest {
        val source=Source();val fake=FakeCollections().apply{fail=true};val owner=owner(source,fake);val session=ApplicationSession(owner,this)
        session.importLocal(picker());advanceUntilIdle()
        assertEquals(1,source.imported);assertTrue(fake.saved.isEmpty());assertEquals(0,source.resolves)
        assertIs<OpenPublicationState.Idle>(session.opening.value);assertNotNull(session.importing.value.message)
        finish(session,owner)
    }
    @Test fun backCancelsAndRejectsNonCooperativeLateImport()=runTest {
        val source=Source();val gate=CompletableDeferred<Unit>();source.action={withContext(NonCancellable){gate.await()}}
        val fake=FakeCollections();val owner=owner(source,fake);val session=ApplicationSession(owner,this)
        session.importLocal(picker());runCurrent();assertTrue(session.handlesBack())
        session.back();gate.complete(Unit);advanceUntilIdle()
        assertFalse(session.importing.value.busy);assertIs<OpenPublicationState.Idle>(session.opening.value)
        assertTrue(fake.saved.isEmpty());assertTrue(fake.opened.isEmpty());finish(session,owner)
    }
    @Test fun importFromHistoryReturnsToHistoryAndBusyNavigationCannotRaceIt()=runTest {
        val source=Source();val gate=CompletableDeferred<Unit>();source.action={gate.await()}
        val fake=FakeCollections();val owner=owner(source,fake);val session=ApplicationSession(owner,this,StandardTestDispatcher(testScheduler))
        session.navigate(Destination.HISTORY);session.importLocal(picker());runCurrent()
        session.navigate(Destination.LIBRARY);session.openSaved(PublicationSnapshot.from(source.pub));assertEquals(Destination.HISTORY,session.destination.value)
        gate.complete(Unit);advanceUntilIdle();assertIs<OpenPublicationState.Ready>(session.opening.value)
        session.back();advanceUntilIdle();assertEquals(Destination.HISTORY,session.destination.value);finish(session,owner)
    }
    @Test fun closingSessionCancelsPickerAndLeavesRootBackNormal()=runTest {
        val source=Source();val fake=FakeCollections();val owner=owner(source,fake);val session=ApplicationSession(owner,this)
        var cancelled=false
        val picker=object:LocalFilePicker {override suspend fun pick():LocalFileSelection? {try{awaitCancellation()}finally{cancelled=true}}}
        session.importLocal(picker);runCurrent();session.close();advanceUntilIdle()
        assertTrue(cancelled);assertFalse(session.importing.value.busy);assertEquals(0,source.imported);finish(session,owner)
    }
}
