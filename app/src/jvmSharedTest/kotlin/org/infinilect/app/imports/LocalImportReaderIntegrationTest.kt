// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.infinilect.app.*
import org.infinilect.app.collections.*
import org.infinilect.app.epub.*
import org.infinilect.app.page.developmentCbzBytes
import org.infinilect.app.progress.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.page.CbzPagePreparer
import org.infinilect.core.*
import kotlin.test.*

class LocalImportReaderIntegrationTest {
    private class Owner(val root: Path) {
        val text=FileTextPreparer(root.resolve("cache/text"))
        val epub=FileEpubPreparer(root.resolve("cache/epub"))
        val pages=CbzPagePreparer(root.resolve("cache/cbz"))
        val local=FileLocalPublicationSource(root.resolve("files/$IMPORT_DIRECTORY_NAME"),text,epub,pages)
        val store=SqlCollectionsStore({JdbcSqliteDriver("jdbc:sqlite:${root.resolve("collections.sqlite")}",collectionsJdbcProperties()).also(::initializeCollectionsSchema)})
        val progress=ProgressPersistence(FileReadingProgressStore(root.resolve("progress")),clock={100L})
        val collections=ApplicationCollections(store.library,store.history,release=store::close)
        val sources=ApplicationSources(listOf(SourceOption("Imported files",local,true,true,true)),progress=progress,collections=collections,
            textPreparer=text,epubPreparer=epub,pagePreparer=pages,localImports=local){}
        suspend fun close(){sources.close();sources.awaitProgressClosed()}
    }
    private fun picker(bytes:ByteArray)=object:LocalFilePicker {
        override suspend fun pick()=LocalFileSelection("misleading.txt") { object:ResourceContent {
            override val sizeBytes=bytes.size.toLong();var pos=0;var closed=false
            override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {check(!closed);if(pos==bytes.size)return -1;val n=minOf(length,8192,bytes.size-pos);bytes.copyInto(buffer,offset,pos,pos+n);pos+=n;return n}
            override fun close(){closed=true}
        } }
    }
    private suspend fun ready(session:ApplicationSession)=withTimeout(15_000){session.opening.first{it is OpenPublicationState.Ready || it is OpenPublicationState.EpubReady || it is OpenPublicationState.PageReady || it is OpenPublicationState.Error}}
    private fun <T> value(result:LocalStoreResult<T>)=assertIs<LocalStoreResult.Success<T>>(result).value

    @Test fun textEpubAndCbzImportReachExistingReadersAndRestartThroughSqlLibraryHistory()=runBlocking<Unit> {
        val root=Files.createTempDirectory("local-readers")
        val first=Owner(root);val session=ApplicationSession(first.sources,this)
        val snapshots=mutableListOf<PublicationSnapshot>()
        try {
            val fixtures=listOf("Plain UTF-8 text 🦦".encodeToByteArray(),developmentEpubBytes(),developmentCbzBytes())
            for((index,bytes) in fixtures.withIndex()) {
                session.importLocal(picker(bytes));val state=ready(session)
                when(index){0->assertIs<OpenPublicationState.Ready>(state);1->assertIs<OpenPublicationState.EpubReady>(state);2->assertIs<OpenPublicationState.PageReady>(state)}
                val publication=when(state){is OpenPublicationState.Ready->assertNotNull(state.publication);is OpenPublicationState.EpubReady->state.publication;is OpenPublicationState.PageReady->state.publication;else->error("reader failed")}
                snapshots+=PublicationSnapshot.from(publication);session.back()
            }
            first.collections.flushHistory()
            assertEquals(3,value(first.store.library.list()).size);assertEquals(3,value(first.store.history.listRecent()).size)
        } finally {session.close();first.close()}
        // Drop every owner/session and disposable preparation file; only durable stores survive.
        root.resolve("cache").toFile().deleteRecursively()
        val second=Owner(root);val reopened=ApplicationSession(second.sources,this)
        try {
            for(snapshot in snapshots) {
                val row=assertNotNull(value(second.store.library.get(snapshot.id)))
                reopened.navigate(Destination.LIBRARY);reopened.openSaved(row.publication)
                assertFalse(ready(reopened) is OpenPublicationState.Error);reopened.back()
                reopened.navigate(Destination.HISTORY);reopened.openSaved(value(second.store.history.listRecent()).first{it.publication.id==snapshot.id}.publication)
                assertFalse(ready(reopened) is OpenPublicationState.Error);reopened.back()
            }
            value(second.store.library.remove(snapshots.first().id))
            assertNotNull(second.local.getPublication(snapshots.first().id));assertEquals(3,value(second.store.history.listRecent()).size)
            value(second.store.history.clear());assertEquals(2,value(second.store.library.list()).size);assertNotNull(second.local.getPublication(snapshots.first().id))
        } finally {reopened.close();second.close()}
    }
    @Test fun semanticTextProgressRestoresFromFreshPersistentOwner()=runBlocking<Unit> {
        val root=Files.createTempDirectory("local-progress");val first=Owner(root);val session=ApplicationSession(first.sources,this)
        session.importLocal(picker("A long enough 🦦 logical document".encodeToByteArray()))
        val ready=assertIs<OpenPublicationState.Ready>(ready(session));val snapshot=PublicationSnapshot.from(assertNotNull(ready.publication))
        assertNotNull(ready.reading).report(12);session.back();session.close();first.close()
        val second=Owner(root);val reopened=ApplicationSession(second.sources,this)
        try {reopened.openSaved(snapshot);assertEquals(12,assertNotNull(assertIs<OpenPublicationState.Ready>(ready(reopened)).reading).codePointOffset.value)}
        finally {reopened.close();second.close()}
    }
    @Test fun importedEpub2AndEpub3KeepIdentityLibraryHistoryAndProgressAfterOriginalDeletion()=runBlocking<Unit> {
        val root=Files.createTempDirectory("local-epub-compatibility")
        val first=Owner(root);val session=ApplicationSession(first.sources,this)
        val records=mutableListOf<Pair<PublicationSnapshot,ReadingLocator.Epub>>()
        try {
            for(fixture in listOf(epub2Fixture(),EpubFixture())) {
                val original=root.resolve("original.epub")
                Files.write(original,fixture.zip())
                val fromFile=object:LocalFilePicker {
                    override suspend fun pick()=LocalFileSelection("original.epub") { EpubBytes(Files.readAllBytes(original)) }
                }
                session.importLocal(fromFile)
                val open=assertIs<OpenPublicationState.EpubReady>(ready(session))
                val frame=assertIs<org.infinilect.app.reader.epub.EpubReaderState.Ready>(open.reader.state.value)
                val snapshot=PublicationSnapshot.from(open.publication)
                open.reader.report(frame.ticket,0,5)
                records+=snapshot to frame.chapter.locator(0,5)
                session.back()
                // Byte-identical import deduplicates through the same durable import boundary.
                session.importLocal(fromFile);assertEquals(snapshot.id,assertIs<OpenPublicationState.EpubReady>(ready(session)).publication.id)
                session.back();Files.delete(original)
            }
            first.collections.flushHistory()
            assertEquals(2,value(first.store.library.list()).size)
            assertEquals(2,value(first.store.history.listRecent()).size)
        } finally {session.close();first.close()}
        root.resolve("cache").toFile().deleteRecursively()
        val second=Owner(root);val reopened=ApplicationSession(second.sources,this)
        try {
            for((snapshot,expected) in records) {
                val row=assertNotNull(value(second.store.library.get(snapshot.id)))
                reopened.navigate(Destination.LIBRARY);reopened.openSaved(row.publication)
                val open=assertIs<OpenPublicationState.EpubReady>(ready(reopened))
                val frame=assertIs<org.infinilect.app.reader.epub.EpubReaderState.Ready>(open.reader.state.value)
                assertEquals(expected,frame.chapter.locator(frame.initialPosition.first,frame.initialPosition.second))
                assertEquals(listOf("chapter"),open.reader.document.spine.map{it.itemId})
                reopened.back()
                reopened.navigate(Destination.HISTORY)
                reopened.openSaved(value(second.store.history.listRecent()).first{it.publication.id==snapshot.id}.publication)
                assertIs<OpenPublicationState.EpubReady>(ready(reopened));reopened.back()
            }
            value(second.store.library.remove(records.first().first.id))
            value(second.store.history.clear())
            assertNotNull(second.local.getPublication(records.first().first.id))
        } finally {reopened.close();second.close();root.toFile().deleteRecursively()}
    }
    @Test fun malformedNcxNeverPublishesOwnedImportLibraryOrHistory()=runBlocking<Unit> {
        val root=Files.createTempDirectory("local-epub-reject");val owner=Owner(root);val session=ApplicationSession(owner.sources,this)
        try {
            session.importLocal(picker(epub2Fixture().apply{entries["OPS/Nav/toc.ncx"]="<broken".encodeToByteArray()}.zip()))
            withTimeout(5000){session.importing.first{!it.busy}}
            assertNotNull(session.importing.value.message)
            assertIs<OpenPublicationState.Idle>(session.opening.value)
            assertTrue(owner.local.search("*").publications.isEmpty())
            assertTrue(value(owner.store.library.list()).isEmpty());assertTrue(value(owner.store.history.listRecent()).isEmpty())
        } finally {session.close();owner.close();root.toFile().deleteRecursively()}
    }

}
