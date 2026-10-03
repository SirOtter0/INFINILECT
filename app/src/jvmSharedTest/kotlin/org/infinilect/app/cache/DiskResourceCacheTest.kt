// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.cache

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiskResourceCacheTest {
    private lateinit var root: Path
    private val owners = mutableListOf<DiskResourceCache>()
    private var ticks = 1000L
    private val id = SourceId("fixture")
    private val resource = PublicationResource(PublicationId(id, "1"), "text", PublicationFormat.TEXT, "text/plain", "v1")

    @BeforeTest fun setup() { root = Files.createTempDirectory("infinilect-cache-test") }
    @AfterTest fun cleanup() { owners.forEach { it.close() }; root.toFile().deleteRecursively() }

    private fun TestScope.cache(limit: Long = 4096, path: Path? = root.resolve(CACHE_DIRECTORY_NAME)): DiskResourceCache =
        DiskResourceCache(path, limit, StandardTestDispatcher(testScheduler), { ++ticks }).also { owners += it }

    private class FakeContent(
        val bytes: ByteArray,
        var declared: Long? = bytes.size.toLong(),
        private val chunk: Int = 3,
        val action: suspend (FakeContent) -> Unit = {},
        val closeAction: () -> Unit = {},
    ) : ResourceContent {
        override val sizeBytes get() = declared
        var cursor = 0
        var reads = 0
        var closes = 0
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++; action(this)
            if (cursor == bytes.size) return -1
            val n = minOf(chunk, length, bytes.size - cursor)
            bytes.copyInto(buffer, offset, cursor, cursor + n); cursor += n
            return n
        }
        override fun close() { if (closes++ == 0) closeAction() }
    }
    private class Upstream(val factory: () -> FakeContent = { FakeContent("real bytes".encodeToByteArray()) }) : ResourceLoader {
        val handles = mutableListOf<FakeContent>()
        val requested = mutableListOf<PublicationResource>()
        override suspend fun load(resource: PublicationResource): ResourceContent {
            requested += resource
            return factory().also { handles += it }
        }
    }
    private suspend fun bytes(cache: DiskResourceCache, source: Upstream, r: PublicationResource = resource): ByteArray =
        cache.loader(r.publicationId.sourceId, source).load(r).readBytes(2048)
    private fun ownedFiles(suffix: String): List<Path> {
        val dir = root.resolve(CACHE_DIRECTORY_NAME)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream -> stream.filter { it.fileName.toString().endsWith(suffix) }.toList() }
    }
    private fun entry(r: PublicationResource = resource) = root.resolve(CACHE_DIRECTORY_NAME)
        .resolve("e-${cacheDigest(r.cacheKey!!.encodeIdentity())}.entry")
    private fun cost(r: PublicationResource, payload: Int) = 56L + r.cacheKey!!.encodeIdentity().size + payload

    @Test fun structuredFieldsAndDelimiterConfusionCannotAlias() = runTest {
        val resources = listOf(resource,
            resource.copy(publicationId = PublicationId(SourceId("other"), "1")),
            resource.copy(publicationId = PublicationId(id, "2")), resource.copy(key = "other"),
            resource.copy(format = PublicationFormat.PDF), resource.copy(mediaType = "application/pdf"),
            resource.copy(revision = "v2"),
            resource.copy(publicationId = PublicationId(SourceId("a:b"), "c")),
            resource.copy(publicationId = PublicationId(SourceId("a"), "b:c")))
        val encoded = resources.map { cacheDigest(it.cacheKey!!.encodeIdentity()) }
        assertEquals(resources.size, encoded.toSet().size)
        val cache = cache(); val upstream = Upstream()
        for (r in resources) bytes(cache, upstream, r)
        assertEquals(resources.size, ownedFiles(".entry").size)
        assertTrue(ownedFiles(".entry").all { Regex("e-[0-9a-f]{64}\\.entry").matches(it.fileName.toString()) })
    }

    @Test fun unknownRevisionAlwaysReacquiresAcrossRestartAndCreatesNoEntry() = runTest {
        val r = resource.copy(revision = null); val upstream = Upstream(); val first = cache()
        bytes(first, upstream, r); bytes(first, upstream, r); first.close()
        bytes(cache(), upstream, r)
        assertEquals(3, upstream.requested.size); assertTrue(upstream.requested.all { it.revision == null })
        assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun nullRevisionDoesNotReuseExistingVersionedEntry() = runTest {
        val cache = cache(); val upstream = Upstream()
        bytes(cache, upstream); bytes(cache, upstream, resource.copy(revision = null))
        assertEquals(2, upstream.requested.size); assertEquals(1, ownedFiles(".entry").size)
    }

    @Test fun missStreamsPartialReadsAndPublishesOnlyAfterEofAndNormalClose() = runTest {
        val cache = cache(); val upstream = Upstream(); val content = cache.loader(id, upstream).load(resource)
        assertEquals(0, upstream.handles.single().reads)
        val result = ByteArray(10); var n = 0
        while (true) { val count = content.read(result, n, result.size - n)
            if (count == -1) break
            n += count
            if (n == result.size) { assertEquals(-1, content.read(ByteArray(1))); break }
        }
        assertContentEquals("real bytes".encodeToByteArray(), result)
        assertTrue(ownedFiles(".entry").isEmpty()); assertEquals(1, ownedFiles(".part").size)
        content.close(); content.close()
        assertEquals(1, ownedFiles(".entry").size); assertTrue(ownedFiles(".part").isEmpty())
        assertEquals(1, upstream.handles.single().closes)
        assertContentEquals(result, bytes(cache, upstream)); assertEquals(1, upstream.requested.size)
    }

    @Test fun completedStableEntrySurvivesNewCacheInstance() = runTest {
        val upstream = Upstream(); val first = cache(); val original = bytes(first, upstream); first.close()
        assertContentEquals(original, bytes(cache(), upstream)); assertEquals(1, upstream.requested.size)
    }

    @Test fun truncatedSourceCannotPublish() = runTest {
        val upstream = Upstream { FakeContent(byteArrayOf(1, 2), declared = 3) }
        assertFailsWith<IOException> { bytes(cache(), upstream) }
        assertEquals(1, upstream.handles.single().closes); assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun overlongSourceCannotPublish() = runTest {
        val upstream = Upstream { FakeContent(byteArrayOf(1, 2, 3), declared = 2) }
        assertFailsWith<IOException> { bytes(cache(), upstream) }
        assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun changingDeclaredSizeCannotPublish() = runTest {
        val upstream = Upstream { FakeContent(byteArrayOf(1, 2, 3), action = { it.declared = 4 }) }
        assertFailsWith<IOException> { bytes(cache(), upstream) }; assertTrue(ownedFiles(".entry").isEmpty())
    }

    @Test fun unknownSizeStableStreamCanPublishItsActualLength() = runTest {
        val upstream = Upstream { FakeContent(byteArrayOf(1, 2, 3), declared = null) }; val cache = cache()
        assertContentEquals(byteArrayOf(1, 2, 3), bytes(cache, upstream))
        val hit = cache.loader(id, upstream).load(resource)
        assertEquals(3L, hit.sizeBytes); hit.close(); assertEquals(1, upstream.requested.size)
    }

    @Test fun earlyCloseDiscardsTempEvenIfDeclaredBytesWereRead() = runTest {
        val upstream = Upstream { FakeContent(byteArrayOf(1, 2, 3)) }; val cache = cache()
        val handle = cache.loader(id, upstream).load(resource)
        assertEquals(3, handle.read(ByteArray(3))); handle.close()
        assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun cancellationDuringReadDiscardsTempAndClosesSource() = runTest {
        val started = CompletableDeferred<Unit>()
        val upstream = Upstream { FakeContent(byteArrayOf(1), action = { started.complete(Unit); awaitCancellation() }) }
        val cache = cache(); val job = launch { bytes(cache, upstream) }
        started.await(); job.cancelAndJoin()
        assertEquals(1, upstream.handles.single().closes); assertTrue(ownedFiles(".part").isEmpty()); assertTrue(ownedFiles(".entry").isEmpty())
    }

    @Test fun cancellationAfterEofBeforeCloseDoesNotPublish() = runTest {
        val upstream = Upstream { FakeContent(byteArrayOf(1)) }; val cache = cache(); val reached = CompletableDeferred<Unit>()
        val job = launch {
            val content = cache.loader(id, upstream).load(resource)
            try { content.read(ByteArray(1)); assertEquals(-1, content.read(ByteArray(1))); reached.complete(Unit); awaitCancellation() }
            finally { content.close() }
        }
        reached.await(); job.cancelAndJoin(); assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun readFailureAndSourceCloseFailureNeverPublish() = runTest {
        val cache = cache()
        val failure = Upstream { FakeContent(byteArrayOf(1), action = { throw IOException("source") }) }
        assertFailsWith<IOException> { bytes(cache, failure) }
        val closeFailure = Upstream { FakeContent(byteArrayOf(1), closeAction = { throw IOException("close") }) }
        assertFailsWith<IOException> { bytes(cache, closeFailure) }
        assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun zeroSizedStableResourcesCanBeCached() = runTest {
        val cache = cache(); val upstream = Upstream { FakeContent(byteArrayOf()) }
        assertTrue(bytes(cache, upstream).isEmpty()); assertTrue(bytes(cache, upstream).isEmpty())
        assertEquals(1, upstream.requested.size)
    }

    @Test fun corruptedPayloadIsMissAndReplaced() = runTest {
        val cache = cache(); val upstream = Upstream(); bytes(cache, upstream)
        RandomAccessFile(entry().toFile(), "rw").use { it.seek(it.length() - 1); it.writeByte(0) }
        assertContentEquals("real bytes".encodeToByteArray(), bytes(cache, upstream)); assertEquals(2, upstream.requested.size)
        bytes(cache, upstream); assertEquals(2, upstream.requested.size)
    }

    @Test fun missingOrMalformedHeaderIsMiss() = runTest {
        val cache = cache(); val upstream = Upstream(); bytes(cache, upstream)
        Files.write(entry(), byteArrayOf(0, 1, 2)); bytes(cache, upstream)
        RandomAccessFile(entry().toFile(), "rw").use { it.seek(12); it.writeInt(Int.MAX_VALUE) }
        bytes(cache, upstream); assertEquals(3, upstream.requested.size)
    }

    @Test fun mismatchingIdentityAndPayloadLengthAreMisses() = runTest {
        val cache = cache(); val upstream = Upstream(); bytes(cache, upstream)
        RandomAccessFile(entry().toFile(), "rw").use { it.seek(24); it.writeByte(0) }
        bytes(cache, upstream)
        RandomAccessFile(entry().toFile(), "rw").use { it.seek(it.length()); it.writeByte(0) }
        bytes(cache, upstream); assertEquals(3, upstream.requested.size)
    }

    @Test fun missingPayloadFileIsMiss() = runTest {
        val cache = cache(); val upstream = Upstream(); bytes(cache, upstream); Files.delete(entry())
        bytes(cache, upstream); assertEquals(2, upstream.requested.size)
    }

    @Test fun recencyEvictsOlderEntriesAndCountsContainerBytes() = runTest {
        val a = resource; val b = resource.copy(key = "next"); val c = resource.copy(key = "last")
        val cache = cache(cost(a, 10) * 2); val upstream = Upstream()
        bytes(cache, upstream, a); bytes(cache, upstream, b); bytes(cache, upstream, a); bytes(cache, upstream, c)
        assertTrue(Files.exists(entry(a))); assertFalse(Files.exists(entry(b))); assertTrue(Files.exists(entry(c)))
        assertTrue(ownedFiles(".entry").sumOf { Files.size(it) } <= cost(a, 10) * 2)
    }

    @Test fun activeHitIsPinnedWhileAnotherEntryIsEvicted() = runTest {
        val a = resource; val b = resource.copy(key = "next"); val c = resource.copy(key = "last")
        val cache = cache(cost(a, 10) * 2); val upstream = Upstream()
        bytes(cache, upstream, a); bytes(cache, upstream, b)
        val active = cache.loader(id, upstream).load(a); assertEquals(2, active.read(ByteArray(2)))
        bytes(cache, upstream, c)
        assertTrue(Files.exists(entry(a))); assertFalse(Files.exists(entry(b)))
        assertContentEquals("al bytes".encodeToByteArray(), active.readBytes(2048))
    }

    @Test fun fullPinnedCacheSkipsNewWriteWithoutFailingAcquisition() = runTest {
        val cache = cache(cost(resource, 10)); val upstream = Upstream(); bytes(cache, upstream)
        val active = cache.loader(id, upstream).load(resource)
        assertContentEquals("real bytes".encodeToByteArray(), bytes(cache, upstream, resource.copy(key = "next")))
        assertTrue(Files.exists(entry())); assertEquals(1, ownedFiles(".entry").size); active.close()
    }

    @Test fun recencyAndReducedBudgetSurviveRestart() = runTest {
        val upstream = Upstream(); val a = resource; val b = resource.copy(key = "next"); val first = cache()
        bytes(first, upstream, a); bytes(first, upstream, b); bytes(first, upstream, a); first.close()
        val second = cache(cost(a, 10)); bytes(second, upstream, a)
        assertEquals(2, upstream.requested.size); assertTrue(Files.exists(entry(a))); assertFalse(Files.exists(entry(b)))
    }

    @Test fun oversizedAndZeroBudgetCacheStillStreamsUpstream() = runTest {
        val upstream = Upstream(); assertEquals(10, bytes(cache(1), upstream).size)
        owners.last().close(); assertEquals(10, bytes(cache(0), upstream).size)
        assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun unknownSizeOverflowAbandonsCacheButNotValidSourceBytes() = runTest {
        val data = ByteArray(1000) { 65 }; val upstream = Upstream { FakeContent(data, declared = null) }
        assertContentEquals(data, bytes(cache(cost(resource, 10)), upstream))
        assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun unusableStorageFallsBackAndOversizedIdentityDoesNotBecomePath() = runTest {
        val blocked = root.resolve("blocked"); Files.write(blocked, byteArrayOf(1))
        val upstream = Upstream(); assertEquals(10, bytes(cache(path = blocked), upstream).size)
        val tooLong = resource.copy(key = "../".repeat(20000)); assertEquals(10, bytes(cache(), upstream, tooLong).size)
        assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun unrelatedFilesAndSymlinkTargetsAreNeverDeletedOrRead() = runTest {
        val dir = root.resolve(CACHE_DIRECTORY_NAME); Files.createDirectories(dir)
        val outside = root.resolve("outside"); Files.write(outside, byteArrayOf(9))
        val unrelated = dir.resolve("personal-note"); Files.write(unrelated, byteArrayOf(8))
        Files.createSymbolicLink(entry(), outside)
        val staleTemp = dir.resolve("t-${java.util.UUID.randomUUID()}.part"); Files.write(staleTemp, byteArrayOf(0))
        val upstream = Upstream(); bytes(cache(), upstream)
        assertContentEquals(byteArrayOf(9), Files.readAllBytes(outside)); assertContentEquals(byteArrayOf(8), Files.readAllBytes(unrelated))
        assertFalse(Files.exists(staleTemp)); assertFalse(Files.isSymbolicLink(entry()))
    }

    @Test fun symlinkRootIsDisabled() = runTest {
        val target = root.resolve("other-directory"); Files.createDirectories(target)
        val link = root.resolve(CACHE_DIRECTORY_NAME); Files.createSymbolicLink(link, target)
        bytes(cache(), Upstream()); assertEquals(0L, Files.list(target).use { it.count() })
    }

    @Test fun secondDirectoryOwnerFailsClosedWithoutDeletingFirstOwnersTemp() = runTest {
        val upstream = Upstream(); val first = cache(); val handle = first.loader(id, upstream).load(resource)
        assertEquals(1, ownedFiles(".part").size)
        bytes(cache(), upstream); assertEquals(1, ownedFiles(".part").size)
        handle.close(); assertTrue(ownedFiles(".entry").isEmpty())
    }

    @Test fun foreignSourceIsRejectedEvenWhenAnEntryAlreadyExists() = runTest {
        val upstream = Upstream(); val cache = cache(); bytes(cache, upstream)
        assertFailsWith<IllegalArgumentException> { cache.loader(SourceId("foreign"), upstream).load(resource) }
        assertEquals(1, upstream.requested.size)
    }

    @Test fun ownerCloseClosesCachedAndUncachedHandlesAndDiscardsUnfinishedWrites() = runTest {
        val cache = cache(); val upstream = Upstream(); bytes(cache, upstream)
        val hit = cache.loader(id, upstream).load(resource)
        val miss = cache.loader(id, upstream).load(resource.copy(key = "next"))
        val uncached = cache.loader(id, upstream).load(resource.copy(revision = null))
        cache.close(); cache.close()
        for (h in listOf(hit, miss, uncached)) assertFailsWith<IllegalStateException> { h.read(ByteArray(1)) }
        assertTrue(upstream.handles.all { it.closes == 1 }); assertTrue(ownedFiles(".part").isEmpty())
        assertFailsWith<IllegalStateException> { cache.loader(id, upstream).load(resource) }
    }

    @Test fun lateUpstreamHandleAfterOwnerCloseIsReleased() = runTest {
        val cache = cache(); val started = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
        val content = FakeContent(byteArrayOf(1))
        val upstream = object : ResourceLoader {
            override suspend fun load(resource: PublicationResource): ResourceContent { started.complete(Unit); returned.await(); return content }
        }
        val job = async { assertFailsWith<IllegalStateException> { cache.loader(id, upstream).load(resource) } }
        started.await(); cache.close(); returned.complete(Unit); job.await()
        assertEquals(1, content.closes); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun independentHitCursorsAndReadBoundsRemainCorrect() = runTest {
        val cache = cache(); val upstream = Upstream(); bytes(cache, upstream)
        val a = cache.loader(id, upstream).load(resource); val b = cache.loader(id, upstream).load(resource)
        assertEquals(0, a.read(ByteArray(2), length = 0))
        assertFailsWith<IllegalArgumentException> { a.read(ByteArray(2), offset = -1) }
        assertEquals(2, a.read(ByteArray(2))); assertContentEquals("real bytes".encodeToByteArray(), b.readBytes(2048))
        assertContentEquals("al bytes".encodeToByteArray(), a.readBytes(2048))
        assertFailsWith<IllegalStateException> { a.read(ByteArray(1)) }
    }

    @Test fun concurrentMissesForOneKeyDoNotOverwriteOrPublishIncompleteEntries() = runTest {
        val cache = cache(); val upstream = Upstream(); val loader = cache.loader(id, upstream)
        val first = loader.load(resource); val second = loader.load(resource)
        assertEquals(1, ownedFiles(".part").size)
        assertContentEquals("real bytes".encodeToByteArray(), second.readBytes(2048))
        assertTrue(ownedFiles(".entry").isEmpty())
        first.readBytes(2048)
        assertEquals(1, ownedFiles(".entry").size); assertTrue(ownedFiles(".part").isEmpty())
        bytes(cache, upstream); assertEquals(2, upstream.requested.size)
    }

    @Test fun malformedSizeMetadataIsMissWithoutOverflowOrAllocation() = runTest {
        val upstream = Upstream(); val first = cache(); bytes(first, upstream); first.close()
        val sizeOffset = 16L + resource.cacheKey!!.encodeIdentity().size
        RandomAccessFile(entry().toFile(), "rw").use { it.seek(sizeOffset); it.writeLong(Long.MAX_VALUE) }
        bytes(cache(), upstream); assertEquals(2, upstream.requested.size)
    }

    @Test fun malformedReadCountsAndNegativeSizeCannotPublish() = runTest {
        val cache = cache()
        val invalid = object : ResourceLoader {
            override suspend fun load(resource: PublicationResource) = object : ResourceContent {
                override val sizeBytes = 1L
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = 0
                override fun close() = Unit
            }
        }
        assertFailsWith<IllegalStateException> { cache.loader(id, invalid).load(resource).readBytes(10) }
        val negative = Upstream { FakeContent(byteArrayOf(1), declared = -1) }
        assertFailsWith<IOException> { cache.loader(id, negative).load(resource).read(ByteArray(1)) }
        assertTrue(ownedFiles(".entry").isEmpty()); assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun sourceFailureRetainsPrimaryCauseWhenCloseAlsoFails() = runTest {
        val original = IOException("read")
        val upstream = Upstream { FakeContent(byteArrayOf(1), action = { throw original }, closeAction = { throw IOException("close") }) }
        val error = assertFailsWith<IOException> { bytes(cache(), upstream) }
        assertSame(original, error); assertEquals("close", error.suppressed.single().message)
        assertTrue(ownedFiles(".part").isEmpty())
    }

    @Test fun cancellationAtVerifiedHitHandoffUnpinsDescriptor() = runTest {
        var cancelOnTouch = false
        lateinit var request: Job
        val cache = DiskResourceCache(root.resolve(CACHE_DIRECTORY_NAME), cost(resource, 10),
            StandardTestDispatcher(testScheduler), { if (cancelOnTouch) request.cancel(); ++ticks }).also { owners += it }
        val upstream = Upstream(); bytes(cache, upstream)
        cancelOnTouch = true
        request = launch { cache.loader(id, upstream).load(resource).close() }
        request.join(); assertTrue(request.isCancelled)
        cancelOnTouch = false
        val other = resource.copy(key = "next"); bytes(cache, upstream, other)
        assertFalse(Files.exists(entry())); assertTrue(Files.exists(entry(other)))
    }

    @Test fun cancellationAtTempCreationHandoffDiscardsWriterAndClosesSource() = runTest {
        val delegate = StandardTestDispatcher(testScheduler)
        lateinit var request: Job
        var dispatches = 0
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                delegate.dispatch(context, Runnable { block.run(); if (++dispatches == 2) request.cancel() })
            }
        }
        val cache = DiskResourceCache(root.resolve(CACHE_DIRECTORY_NAME), 4096, dispatcher).also { owners += it }
        val upstream = Upstream()
        request = launch { cache.loader(id, upstream).load(resource).close() }
        request.join(); assertTrue(request.isCancelled)
        assertEquals(1, upstream.handles.single().closes)
        assertTrue(ownedFiles(".part").isEmpty()); assertTrue(ownedFiles(".entry").isEmpty())
    }
}
