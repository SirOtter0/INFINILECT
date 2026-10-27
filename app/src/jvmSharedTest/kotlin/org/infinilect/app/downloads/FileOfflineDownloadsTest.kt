// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.downloads

import java.nio.file.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.*
import org.infinilect.core.*
import kotlin.test.*

class FileOfflineDownloadsTest {
    private suspend fun download(store: DownloadBackend,id: PublicationId): DownloadEntry {
        val row=store.pending(store.offers(id).first())
        return store.download(row,{},{})
    }
    @Test fun allFourFormatsUsePrivateCopiesAndKeepOriginalResourceIdentityAcrossRestart()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-four");val source=OfflineFixtureSource();val owner=OfflineFixtureOwner(root,source)
        val publications=listOf(PublicationFormat.TEXT,PublicationFormat.EPUB,PublicationFormat.CBZ,PublicationFormat.PDF).map {source.add(it)}
        var store=owner.store()
        try {
            assertTrue(store.load().entries.isEmpty())
            for(p in publications) {
                val entry=download(store,p.id);assertEquals(DownloadStatus.DOWNLOADED,entry.status);assertEquals(p.resources.single(),entry.resource)
                assertNotNull(entry.sha256);assertNotNull(entry.completedAt)
            }
            assertEquals(4,store.snapshot().entries.size);assertEquals(4,source.opens)
            store.close();root.resolve("cache").toFile().deleteRecursively();store=owner.store();source.online=false
            assertEquals(4,store.load().entries.size)
            val calls=source.metadataCalls
            for(p in publications) {
                val copy=assertNotNull(store.acquire(p.id))
                try {assertEquals(p.id,copy.publication.id);assertContentEquals(source.data.getValue(p.id).second,copy.loader.load(copy.publication.resources.single()).readBytes(65536))} finally {copy.close()}
            }
            assertEquals(calls,source.metadataCalls);assertEquals(4,source.opens)
        } finally {store.close();owner.close();root.toFile().deleteRecursively()}
    }
    @Test fun unknownSizeIsCountedAndLeasesPreventEvictionEvenAfterContentRead()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-lease");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT);source.known=false;val owner=OfflineFixtureOwner(root,source);val store=owner.store()
        try {
            store.load();val e=download(store,p.id);assertEquals(source.data.getValue(p.id).second.size.toLong(),e.size)
            val copy=assertNotNull(store.acquire(p.id));copy.loader.load(copy.publication.resources.single()).readBytes(4096)
            assertEquals(DownloadFailure.BUSY,assertFailsWith<DownloadException> {store.remove(p.id)}.failure)
            copy.close();store.remove(p.id);assertNull(store.acquire(p.id));assertTrue(store.snapshot().entries.isEmpty())
            assertEquals(0L,store.snapshot().usedBytes)
        } finally {store.close();owner.close()}
    }
    @Test fun truncatedOversizedFailedAndMalformedFilesNeverBecomeAvailableAndRetryReauthorizes()=runBlocking<Unit> {
        for(mode in listOf("truncated","limit","network","invalid")) {
            val root=Files.createTempDirectory("offline-fail");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT,bytes=if(mode=="invalid") "<html>not a publication</html>".toByteArray() else "Original text for interruption".toByteArray());val owner=OfflineFixtureOwner(root,source);val store=owner.store()
            try {
                store.load();source.known=mode!="limit"
                if(mode=="truncated") source.declared=1000
                if(mode=="limit") source.declared=DOWNLOAD_FILE_LIMIT+1
                if(mode=="network") source.failAfter=11
                val offer=store.offers(p.id).first();val row=store.pending(offer)
                assertFailsWith<DownloadException> {store.download(row,{},{})}
                store.settle(p.id,DownloadStatus.ERROR,DownloadFailure.INCOMPLETE)
                assertNull(store.acquire(p.id));assertFalse(Files.exists(root.resolve("files/$OFFLINE_DIRECTORY/${offlineKey(p.id)}.part")))
                source.known=true;source.declared=null;source.failAfter=null;source.data[p.id]=p to "Original valid replacement text".toByteArray()
                assertEquals(DownloadStatus.DOWNLOADED,download(store,p.id).status)
            } finally {store.close();owner.close()}
        }
    }
    @Test fun interruptedStateAndPartialFilesRecoverAsCancelledWithoutAutomaticNetwork()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-recover");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT);val owner=OfflineFixtureOwner(root,source);var store=owner.store()
        try {
            store.load();val pending=store.pending(store.offers(p.id).first())
            val entered=CompletableDeferred<Unit>();source.beforeRead={entered.complete(Unit);awaitCancellation()}
            val job=launch {store.download(pending,{},{})};entered.await();job.cancelAndJoin()
            assertEquals(DownloadStatus.DOWNLOADING,store.snapshot().entries.single().status)
            store.close();source.online=false;store=owner.store()
            val restored=store.load();assertEquals(DownloadStatus.CANCELLED,restored.entries.single().status);assertEquals(DownloadFailure.INTERRUPTED,restored.entries.single().failure)
            assertNull(store.acquire(p.id));assertEquals(1,source.opens)
        } finally {store.close();owner.close()}
    }
    @Test fun corruptionMissingFilesAndSafeIdentifiersNeverAuthorizeAnotherPublication()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-corrupt");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT,"../../outside");val owner=OfflineFixtureOwner(root,source);val store=owner.store()
        try {
            store.load();download(store,p.id)
            assertNotEquals(offlineKey(PublicationId(SourceId("a"),"bc")),offlineKey(PublicationId(SourceId("ab"),"c")))
            val path=root.resolve("files/$OFFLINE_DIRECTORY/${offlineKey(p.id)}.payload");val bytes=Files.readAllBytes(path);bytes[0]=0;Files.write(path,bytes)
            assertEquals(DownloadFailure.MISSING,assertFailsWith<DownloadException> {store.acquire(p.id)}.failure)
            assertEquals(DownloadStatus.ERROR,store.snapshot().entries.single().status)
            assertNull(store.acquire(PublicationId(source.id,"different")))
            download(store,p.id);Files.delete(path);assertEquals(DownloadStatus.ERROR,store.snapshot().entries.single().status)
            val sentinel=Files.write(root.resolve("original-import"),byteArrayOf(9,8,7));store.remove(p.id);assertContentEquals(byteArrayOf(9,8,7),Files.readAllBytes(sentinel))
        } finally {store.close();owner.close()}
    }
    @Test fun storageLimitsPersistDoNotEvictAndInsufficientSpaceDoesNotOpenPayload()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-space");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT);val owner=OfflineFixtureOwner(root,source);var store=owner.store {0}
        try {
            store.load();val row=store.pending(store.offers(p.id).first());assertEquals(DownloadFailure.SPACE,assertFailsWith<DownloadException> {store.download(row,{},{})}.failure);assertEquals(0,source.opens)
            store.settle(p.id,DownloadStatus.CANCELLED,null);store.close();store=owner.store();store.load();download(store,p.id)
            val payload=root.resolve("files/$OFFLINE_DIRECTORY/${offlineKey(p.id)}.payload")
            FileChannel.open(payload,StandardOpenOption.WRITE).use {it.position(129L*1024*1024);it.write(ByteBuffer.wrap(byteArrayOf(0)))}
            assertEquals(DownloadFailure.LIMIT,assertFailsWith<DownloadException> {store.quota(128L*1024*1024)}.failure);assertTrue(Files.exists(payload))
            store.remove(p.id);store.quota(1024L*1024*1024);store.close();store=owner.store();assertEquals(1024L*1024*1024,store.load().quotaBytes)
        } finally {store.close();owner.close()}
    }
    @Test fun sourceCapabilityIsExplicitAndConcurrentOwnersCannotDeleteRetainedBytes()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-owner");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT);val owner=OfflineFixtureOwner(root,source);val store=owner.store();val other=owner.store()
        try {
            assertEquals(DownloadCapability.IMPORTED,store.capability(PublicationId(SourceId("local-imports"),"existing")))
            assertEquals(DownloadCapability.UNSUPPORTED,store.capability(PublicationId(SourceId("gutenberg"),"1")))
            assertEquals(DownloadFailure.NOT_ALLOWED,assertFailsWith<DownloadException> {store.offers(PublicationId(SourceId("fork"),"id"))}.failure)
            store.load();download(store,p.id);assertEquals(DownloadFailure.BUSY,assertFailsWith<DownloadException> {other.load()}.failure)
            assertNotNull(store.acquire(p.id)).close()
        } finally {other.close();store.close();owner.close()}
    }

    @Test fun atomicWriteFailureKeepsLastGoodQuotaAndCorruptRecordCannotPublishReady()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-atomic");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT);val owner=OfflineFixtureOwner(root,source);var store=owner.store()
        try {
            store.load();download(store,p.id);store.quota(1024L*1024*1024)
            val blocked=root.resolve("files/$OFFLINE_DIRECTORY/quota.tmp");Files.createDirectory(blocked);Files.write(blocked.resolve("sentinel"),byteArrayOf(1))
            assertEquals(DownloadFailure.STORAGE,assertFailsWith<DownloadException> {store.quota(512L*1024*1024)}.failure)
            assertEquals(1024L*1024*1024,store.snapshot().quotaBytes);blocked.toFile().deleteRecursively()
            val marker=root.resolve("files/$OFFLINE_DIRECTORY/${offlineKey(p.id)}.record");val bytes=Files.readAllBytes(marker);bytes[8]=(bytes[8].toInt() xor 1).toByte();Files.write(marker,bytes)
            store.close();store=owner.store();assertTrue(store.load().damagedRecords);assertNull(store.acquire(p.id));assertTrue(store.snapshot().usedBytes>0,"Orphan bytes still count against quota")
            assertEquals(1024L*1024*1024,store.snapshot().quotaBytes)
        } finally {store.close();owner.close();root.toFile().deleteRecursively()}
    }
    @Test fun closedOwnerRetainsLockUntilLastReadingLeaseReleases()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-lock");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT);val owner=OfflineFixtureOwner(root,source);val first=owner.store();val second=owner.store()
        try {
            first.load();download(first,p.id);val copy=assertNotNull(first.acquire(p.id));first.close()
            assertEquals(DownloadFailure.BUSY,assertFailsWith<DownloadException> {second.load()}.failure)
            copy.loader.load(copy.publication.resources.single()).readBytes(4096);copy.close();assertEquals(DownloadStatus.DOWNLOADED,second.load().entries.single().status)
        } finally {first.close();second.close();owner.close();root.toFile().deleteRecursively()}
    }

    @Test fun authorizationRefreshKeepsTheExactChosenResourceWhenTwoFilesShareAFormat()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-chosen");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT);val owner=OfflineFixtureOwner(root,source);val store=owner.store()
        source.offerTransform={offers -> val offer=offers.single();val other=offer.resource.copy(key="different-text-file");val pub=offer.publication.copy(resources=listOf(other,offer.resource));listOf(offer.copy(publication=pub,resource=other),offer.copy(publication=pub))}
        try {
            store.load();val chosen=store.offers(p.id).last();val row=store.pending(chosen);val done=store.download(row,{},{})
            assertEquals(chosen.resource,done.resource);assertEquals(p.resources.single(),assertNotNull(store.acquire(p.id)).also {it.close()}.publication.resources.single())
        } finally {store.close();owner.close();root.toFile().deleteRecursively()}
    }

    @Test fun cancellationAfterAtomicCompletionCannotDowngradeAValidRetainedCopy()=runBlocking<Unit> {
        val root=Files.createTempDirectory("offline-commit-cancel");val source=OfflineFixtureSource();val p=source.add(PublicationFormat.TEXT);val owner=OfflineFixtureOwner(root,source);val store=owner.store()
        try {
            store.load();val pending=store.pending(store.offers(p.id).first())
            assertFailsWith<CancellationException> {store.download(pending,{}, {if(it.bytes==it.total) throw CancellationException()})}
            assertEquals(DownloadStatus.DOWNLOADED,assertNotNull(store.settle(p.id,DownloadStatus.CANCELLED,DownloadFailure.INTERRUPTED)).status)
            assertNotNull(store.acquire(p.id)).close()
        } finally {store.close();owner.close();root.toFile().deleteRecursively()}
    }
}
