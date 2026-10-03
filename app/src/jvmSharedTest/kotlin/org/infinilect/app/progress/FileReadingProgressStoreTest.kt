// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.cache.CACHE_DIRECTORY_NAME
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileReadingProgressStoreTest {
    private lateinit var root: Path
    private val id = ReadingProgressId(PublicationId(SourceId("fixture"),"1"),"text",PublicationFormat.TEXT)
    private fun progress(key: ReadingProgressId = id, time: Long = 1, offset: Long = 3) =
        ReadingProgress(key,ReadingLocator.Text(offset,10),offset/10.0,time)
    private fun TestScope.store(path: Path? = root.resolve(PROGRESS_DIRECTORY_NAME), entries: Int = 1024,
        bytes: Long = 16L*1024*1024, hook: () -> Unit = {}) =
        FileReadingProgressStore(path,entries,bytes,StandardTestDispatcher(testScheduler),hook)
    private fun files() = Files.list(root.resolve(PROGRESS_DIRECTORY_NAME)).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".progress") }.toList()
    }
    @BeforeTest fun setup() { root = Files.createTempDirectory("infinilect-progress") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun saveReopenAndLoadSurvivesRestart() = runTest {
        assertTrue(store().save(progress()))
        assertEquals(progress(),store().get(id))
    }
    @Test fun structuredFieldsAndAmbiguousDelimitersCannotAlias() = runTest {
        val a = id.copy(publicationId = PublicationId(SourceId("a:b"),"c"))
        val b = id.copy(publicationId = PublicationId(SourceId("a"),"b:c"))
        val keys = listOf(id,a,b,id.copy(resourceKey="other"),id.copy(publicationId=id.publicationId.copy(localId="2")))
        val store = store()
        keys.forEach { assertTrue(store.save(progress(it))) }
        keys.forEach { assertEquals(progress(it),store.get(it)) }
        assertNull(store.get(id.copy(format=PublicationFormat.EPUB)))
        assertEquals(keys.size,files().size)
    }
    @Test fun unsafeIdentifiersAreOnlyDigestedNeverUsedAsPaths() = runTest {
        val key = id.copy(publicationId=PublicationId(SourceId("../../other"),"/absolute\u0000../x"), resourceKey="../\\evil")
        assertTrue(store().save(progress(key)))
        assertEquals(progress(key),store().get(key))
        assertTrue(files().single().fileName.toString().matches(Regex("p-[a-f0-9]{64}\\.progress")))
        assertEquals(1,Files.list(root).use { it.count() })
    }
    @Test fun newerUpdateAtomicallyReplacesPreviousRecord() = runTest {
        val store = store(); assertTrue(store.save(progress()))
        assertTrue(store.save(progress(time=2,offset=8)))
        assertEquals(progress(time=2,offset=8),store().get(id)); assertEquals(1,files().size)
    }
    @Test fun oldOwnerCannotOverwriteNewerProgress() = runTest {
        assertTrue(store().save(progress(time=3,offset=8)))
        assertTrue(store().save(progress(time=2,offset=3)))
        assertEquals(8L,assertNotNull(store().get(id)).let { (it.locator as ReadingLocator.Text).codePointOffset })
    }
    @Test fun equalTimestampWithConflictingLocatorIsNotCommitted() = runTest {
        val store = store(); assertTrue(store.save(progress()))
        assertFalse(store.save(progress(offset=8))); assertEquals(progress(),store.get(id))
    }
    @Test fun corruptedRecordIsRejectedWithoutCrashing() = runTest {
        store().save(progress()); val path=files().single(); val bytes=Files.readAllBytes(path)
        bytes[bytes.lastIndex]=(bytes.last()+1).toByte(); Files.write(path,bytes)
        assertNull(store().get(id))
    }
    @Test fun unsupportedFutureVersionIsRejected() = runTest {
        store().save(progress()); val path=files().single(); val bytes=Files.readAllBytes(path)
        ByteBuffer.wrap(bytes).putInt(8,99); Files.write(path,bytes)
        assertNull(store().get(id))
    }
    @Test fun truncatedRecordIsRejected() = runTest {
        store().save(progress()); val path=files().single(); Files.write(path,Files.readAllBytes(path).copyOf(30))
        assertNull(store().get(id))
    }
    @Test fun oversizedFileAndIdentityAreBounded() = runTest {
        store().save(progress()); Files.write(files().single(),ByteArray(MAX_PROGRESS_RECORD_BYTES+1))
        assertNull(store().get(id))
        assertFalse(store().save(progress(id.copy(resourceKey="x".repeat(10000)))))
    }
    @Test fun validChecksumDoesNotPermitNegativeLocatorOrNanProgression() = runTest {
        store().save(progress()); val path=files().single(); val bytes=Files.readAllBytes(path)
        val bodyEnd=bytes.size-32
        ByteBuffer.wrap(bytes).putLong(bodyEnd-32,-1)
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(16,bodyEnd)).copyInto(bytes,bodyEnd)
        Files.write(path,bytes); assertNull(store().get(id))
        assertTrue(store().save(progress(time=2)))
        val second=Files.readAllBytes(path)
        ByteBuffer.wrap(second).putDouble(second.size-48,Double.NaN)
        MessageDigest.getInstance("SHA-256").digest(second.copyOfRange(16,second.size-32)).copyInto(second,second.size-32)
        Files.write(path,second); assertNull(store().get(id))
    }
    @Test fun unrelatedFilesStayUntouchedAndOrphanTempIsRemovedSafely() = runTest {
        val dir=root.resolve(PROGRESS_DIRECTORY_NAME); Files.createDirectories(dir)
        val unrelated=dir.resolve("notes.txt"); Files.writeString(unrelated,"keep")
        val orphan=dir.resolve("t-00000000-0000-0000-0000-000000000000.part"); Files.writeString(orphan,"partial")
        assertTrue(store().save(progress())); assertEquals("keep",Files.readString(unrelated)); assertFalse(Files.exists(orphan))
    }
    @Test fun quotaRefusesNewUserStateInsteadOfEvictingExistingRecords() = runTest {
        val store=store(entries=1); assertTrue(store.save(progress()))
        assertFalse(store.save(progress(id.copy(resourceKey="2"))))
        assertTrue(store.save(progress(time=2,offset=8)))
        assertEquals(progress(time=2,offset=8),store.get(id))
    }
    @Test fun byteQuotaIsEnforced() = runTest { assertFalse(store(bytes=50).save(progress())) }
    @Test fun failedAtomicCommitPreservesPreviouslyCommittedRecord() = runTest {
        store().save(progress())
        assertFalse(store(hook={ throw IOException("simulated move failure") }).save(progress(time=2)))
        assertEquals(progress(),store().get(id))
        assertTrue(Files.list(root.resolve(PROGRESS_DIRECTORY_NAME)).use { it.noneMatch { p -> p.fileName.toString().endsWith(".part") } })
    }
    @Test fun cancellationBeforeCommitKeepsOldStateAndRemovesTemp() = runTest {
        store().save(progress())
        val job=launch { store(hook={ throw CancellationException("cancel") }).save(progress(time=2)) }
        job.join(); assertTrue(job.isCancelled); assertEquals(progress(),store().get(id))
        assertEquals(2,Files.list(root.resolve(PROGRESS_DIRECTORY_NAME)).use { it.count() })
    }
    @Test fun nullAndRelativeDirectoriesDoNotWriteIntoWorkingDirectory() = runTest {
        assertFalse(store(path=null).save(progress())); assertNull(store(path=null).get(id))
        assertFalse(store(path=Path.of("unsafe-relative-progress")).save(progress()))
        assertFalse(Files.exists(Path.of("unsafe-relative-progress")))
    }
    @Test fun symlinksNeverReadOrModifyOutsideStorageNamespace() = runTest {
        val outside=root.resolve("outside"); Files.createDirectories(outside)
        val link=root.resolve(PROGRESS_DIRECTORY_NAME); Files.createSymbolicLink(link,outside)
        assertFalse(store().save(progress())); assertNull(store().get(id))
        assertEquals(0,Files.list(outside).use { it.count() })
    }
    @Test fun concurrentRecreatedOwnersSerializeIndependentRecords() = runTest {
        val keys=(1..12).map { id.copy(resourceKey=it.toString()) }
        keys.map { async(Dispatchers.Default) { FileReadingProgressStore(root.resolve(PROGRESS_DIRECTORY_NAME)).save(progress(it)) } }
            .awaitAll().forEach { assertTrue(it) }
        keys.forEach { assertNotNull(store().get(it)) }
    }
    @Test fun removeOnlyAffectsRequestedRecord() = runTest {
        val other=id.copy(resourceKey="2"); store().save(progress()); store().save(progress(other))
        assertTrue(store().remove(id)); assertNull(store().get(id)); assertNotNull(store().get(other))
    }
    @Test fun deletingCacheDoesNotDeleteProgressAndCorruptProgressDoesNotInvalidateCache() = runTest {
        val resource=PublicationResource(id.publicationId,id.resourceKey,id.format,"text/plain","stable")
        var loads=0
        val upstream=object : ResourceLoader {
            override suspend fun load(resource: PublicationResource): ResourceContent {
                loads++
                return object : ResourceContent {
                    var done=false
                    override val sizeBytes=4L
                    override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
                        if(done) return -1
                        "text".encodeToByteArray().copyInto(buffer,offset); done=true; return 4
                    }
                    override fun close() {}
                }
            }
        }
        fun cache()=DiskResourceCache(root.resolve(CACHE_DIRECTORY_NAME),ioDispatcher=StandardTestDispatcher(testScheduler))
        store().save(progress())
        cache().use { assertEquals("text",it.loader(resource.publicationId.sourceId,upstream).load(resource).readBytes(10).decodeToString()) }
        Files.write(files().single(),byteArrayOf(1))
        cache().use { assertEquals("text",it.loader(resource.publicationId.sourceId,upstream).load(resource).readBytes(10).decodeToString()) }
        assertEquals(1,loads); assertNull(store().get(id))
        store().save(progress(time=2))
        root.resolve(CACHE_DIRECTORY_NAME).toFile().deleteRecursively()
        assertEquals(progress(time=2),store().get(id))
    }
    @Test fun nullRevisionProgressPersistsWithoutCreatingReusableCacheEntry() = runTest {
        val resource=PublicationResource(id.publicationId,id.resourceKey,id.format,"text/plain")
        assertNull(resource.revision); assertNull(resource.cacheKey)
        assertTrue(store().save(progress())); assertEquals(progress(),store().get(id))
        assertFalse(Files.exists(root.resolve(CACHE_DIRECTORY_NAME)))
    }
}
