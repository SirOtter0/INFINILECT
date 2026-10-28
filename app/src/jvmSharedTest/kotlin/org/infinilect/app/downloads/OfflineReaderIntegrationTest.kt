// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.downloads

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.*
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.infinilect.app.*
import org.infinilect.app.collections.*
import org.infinilect.app.epub.*
import org.infinilect.app.progress.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import kotlin.test.*

internal class OfflineReaderOwner(val root: Path,val source: OfflineFixtureSource,scope: CoroutineScope,realPdf: Boolean=false) {
    val fixture=OfflineFixtureOwner(root,source,realPdf)
    val backend=fixture.store()
    val downloads=OfflineDownloads(backend,scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
    val database=SqlCollectionsStore({JdbcSqliteDriver("jdbc:sqlite:${root.resolve("collections.db")}",collectionsJdbcProperties()).also(::initializeCollectionsSchema)})
    val collections=ApplicationCollections(database.library,database.history,release=database::close)
    val progress=ProgressPersistence(FileReadingProgressStore(root.resolve("progress")))
    val sources=ApplicationSources(listOf(SourceOption("Synthetic authorized provider",source,true,true,true,true)),progress=progress,collections=collections,textPreparer=fixture.text,epubPreparer=fixture.epub,pagePreparer=fixture.pages,pdfPreparer=fixture.pdf,downloads=downloads) {}
    val session=ApplicationSession(sources,scope).also(sources::attach)
    suspend fun download(p: Publication) {
        withTimeout(10000) {downloads.catalogue.first {it.loaded}}
        val before=downloads.catalogue.value.entries.firstOrNull {it.id==p.id}?.revision ?: -1L
        downloads.enqueue(downloads.offers(p.id).first())
        val row=withTimeout(10000) {downloads.catalogue.first {c->c.entries.any {it.id==p.id && it.revision>before && it.status in setOf(DownloadStatus.DOWNLOADED,DownloadStatus.ERROR)}}}.entries.first {it.id==p.id}
        assertEquals(DownloadStatus.DOWNLOADED,row.status,"${p.resources.single().format}: ${row.failure}")
    }
    suspend fun open(p: Publication): OpenPublicationState {
        session.openSaved(PublicationSnapshot.from(p))
        return withTimeout(15000) {session.opening.first {it !is OpenPublicationState.Idle && it !is OpenPublicationState.Loading}}
    }
    suspend fun close() {sources.close();sources.awaitProgressClosed()}
}
internal suspend fun verifyOfflineReaders(scope: CoroutineScope,realPdf: Boolean) {
    val root=Files.createTempDirectory("offline-real-open");val source=OfflineFixtureSource()
    val publications=listOf(PublicationFormat.TEXT,PublicationFormat.EPUB,PublicationFormat.CBZ,PublicationFormat.PDF).map {source.add(it)}
    val first=OfflineReaderOwner(root,source,scope,realPdf)
    try {for(p in publications) first.download(p);assertTrue(assertIs<LocalStoreResult.Success<List<LibraryEntry>>>(first.database.library.list()).value.isEmpty())} finally {first.close()}
    root.resolve("cache").toFile().deleteRecursively();source.online=false
    val networkCalls=source.metadataCalls to source.opens
    val second=OfflineReaderOwner(root,source,scope,realPdf)
    try {
        second.session.navigate(Destination.HOME)
        for((index,p) in publications.withIndex()) {
            val ready=second.open(p)
            val actual=when(index) {
                0->assertNotNull(assertIs<OpenPublicationState.Ready>(ready).publication)
                1->assertIs<OpenPublicationState.EpubReady>(ready).publication
                2->assertIs<OpenPublicationState.PageReady>(ready).publication
                else->assertIs<OpenPublicationState.PdfReady>(ready).publication
            }
            assertEquals(p.id,actual.id);assertEquals(p.resources,actual.resources)
            second.session.back()
        }
        second.collections.flushHistory()
        assertEquals(4,assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(second.database.history.listRecent()).value.size)
        assertTrue(assertIs<LocalStoreResult.Success<List<LibraryEntry>>>(second.database.library.list()).value.isEmpty())
        assertEquals(networkCalls,source.metadataCalls to source.opens,"Offline opener must not call the source or even its metadata endpoint")
    } finally {second.close();root.toFile().deleteRecursively()}
}
class OfflineReaderIntegrationTest {
    @Test fun allReadersOpenAfterFullRestartWithNetworkDisabledAndCacheDeleted()=runBlocking<Unit> {verifyOfflineReaders(this,false)}
    @Test fun originalProgressLibraryHistoryFavoritesAndTagsSurviveIndependentDeletion()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-integrity");val source=OfflineFixtureSource();val text=source.add(PublicationFormat.TEXT)
        val epub=source.add(PublicationFormat.EPUB,bytes=epub2Fixture().zip());val first=OfflineReaderOwner(root,source,this)
        var expected: ReadingLocator.Epub?=null
        try {
            assertIs<LocalStoreResult.Success<LibraryEntry>>(first.database.library.put(PublicationSnapshot.from(text),1))
            val tag=assertIs<LocalStoreResult.Success<LibraryTag>>(first.database.library.createTag("Lectura 🦦")).value
            assertIs<LocalStoreResult.Success<Unit>>(first.database.library.organize(setOf(text.id),LibraryOrganizationChange(LibraryReadingState.PAUSED,true,setOf(tag.id))))
            assertTrue(first.backend.load().entries.isEmpty(),"Adding to Library must never download")
            first.download(text);first.download(epub);source.online=false
            val ready=assertIs<OpenPublicationState.Ready>(first.open(text));assertEquals(text.resources.single().key,ready.document.progressId?.resourceKey)
            assertNotNull(ready.reading).report(12);first.session.back()
            val e=assertIs<OpenPublicationState.EpubReady>(first.open(epub));val chapter=assertIs<EpubReaderState.Ready>(e.reader.state.value)
            e.reader.report(chapter.ticket,0,2);expected=chapter.chapter.locator(0,2);first.session.back();first.collections.flushHistory()
        } finally {first.close()}
        root.resolve("cache").toFile().deleteRecursively()
        val second=OfflineReaderOwner(root,source,this)
        try {
            val textReady=assertIs<OpenPublicationState.Ready>(second.open(text));assertEquals(12,assertNotNull(textReady.reading).codePointOffset.value);second.session.back()
            val e=assertIs<OpenPublicationState.EpubReady>(second.open(epub));assertEquals(expected,assertNotNull(second.progress.get(e.reader.progressId)).locator);second.session.back()
            second.collections.flushHistory()
            val before=assertIs<LocalStoreResult.Success<LibraryEntry?>>(second.database.library.get(text.id)).value!!
            assertTrue(before.favorite);assertEquals(LibraryReadingState.PAUSED,before.readingState);assertEquals("Lectura 🦦",before.tags.single().name)
            second.backend.remove(text.id)
            assertEquals(before,assertIs<LocalStoreResult.Success<LibraryEntry?>>(second.database.library.get(text.id)).value)
            assertNotNull(second.progress.get(ReadingProgressId(text.id,text.resources.single().key,PublicationFormat.TEXT)))
            assertEquals(2,assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(second.database.history.listRecent()).value.size)
            second.database.library.put(PublicationSnapshot.from(epub),2);second.database.library.remove(epub.id)
            assertNotNull(second.backend.acquire(epub.id)).close();assertIs<OpenPublicationState.EpubReady>(second.open(epub));second.session.back()
        } finally {second.close();root.toFile().deleteRecursively()}
    }
    @Test fun offlineMissingCopyGivesRecoverableErrorAndDoesNotInventHistory()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-missing");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT);val owner=OfflineReaderOwner(root,source,this)
        try {
            owner.download(p);source.online=false
            Files.delete(root.resolve("files/$OFFLINE_DIRECTORY/${offlineKey(p.id)}.payload"))
            assertIs<OpenPublicationState.Error>(owner.open(p));owner.session.back();owner.collections.flushHistory()
            assertTrue(assertIs<LocalStoreResult.Success<List<HistoryEntry>>>(owner.database.history.listRecent()).value.isEmpty())
            source.online=true;owner.downloads.reload();withTimeout(10000) {owner.downloads.catalogue.first {c->c.entries.any {it.status==DownloadStatus.ERROR}}}
            owner.download(p);source.online=false;assertIs<OpenPublicationState.Ready>(owner.open(p));owner.session.back()
        } finally {owner.close();root.toFile().deleteRecursively()}
    }

    @Test fun damagedOptionalDownloadStorageDoesNotBlockOriginalOnlineReader()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-optional-storage");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT)
        Files.createDirectories(root.resolve("files/$OFFLINE_DIRECTORY"));Files.write(root.resolve("files/$OFFLINE_DIRECTORY/quota"),byteArrayOf(1,2))
        val owner=OfflineReaderOwner(root,source,this)
        try {
            assertIs<OpenPublicationState.Ready>(owner.open(p));assertEquals(1,source.opens);assertEquals(DownloadFailure.STORAGE,owner.downloads.catalogue.value.failure);owner.session.back()
        } finally {owner.close();root.toFile().deleteRecursively()}
    }
}
