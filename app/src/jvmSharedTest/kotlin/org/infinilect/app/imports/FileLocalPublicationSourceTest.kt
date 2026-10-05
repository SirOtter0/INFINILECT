// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import java.nio.file.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.epub.*
import org.infinilect.app.page.developmentCbzBytes
import org.infinilect.app.reader.*
import org.infinilect.app.reader.page.CbzPagePreparer
import org.infinilect.core.*
import kotlin.test.*

class FileLocalPublicationSourceTest {
    @Test fun cancellationAtIoReturnClosesUndeliveredHandle()=runBlocking<Unit> {
        val owner=Owner();val pub=owner.source.import(selection("text".encodeToByteArray()).first)
        owner.source.close();owner.source.awaitClosed()
        val queued=java.util.ArrayDeque<Runnable>()
        val io=object:CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable){queued.add(block)}
        }
        val source=FileLocalPublicationSource(owner.base.resolve("files/$IMPORT_DIRECTORY_NAME"),owner.text,owner.epub,owner.pages,io=io)
        val cancelled=launch(start=CoroutineStart.UNDISPATCHED){source.loadResource(pub.resources.single());error("cancelled handle must not escape")}
        queued.removeFirst().run() // Open on IO; delivery back to the caller is still queued.
        cancelled.cancelAndJoin()
        val handles=mutableListOf<ResourceContent>()
        try {
            repeat(8) {
                val load=async(start=CoroutineStart.UNDISPATCHED){source.loadResource(pub.resources.single())}
                queued.removeFirst().run();handles+=load.await()
            }
            assertEquals(8,handles.size) // A leaked undelivered handle would consume one slot.
        } finally {
            handles.forEach{it.close()};source.close();queued.removeFirst().run();source.awaitClosed();owner.close()
        }
    }
    @Test fun cancellationOrOwnerCloseDuringPreparationClosesValidationHandleAndDiscardsPartial()=runBlocking<Unit> {
        for (closeOwner in listOf(false,true)) {
            val owner=Owner();val entered=CompletableDeferred<Unit>();var handle:ResourceContent?=null
            val preparer=object:TextPreparer {
                override suspend fun prepare(publication:Publication,resource:PublicationResource,loader:ResourceLoader,dispatcher:CoroutineDispatcher):TextDocument {
                    val opened=loader.load(resource);handle=opened
                    try { entered.complete(Unit);awaitCancellation() } finally { opened.close() }
                }
            }
            val source=FileLocalPublicationSource(owner.base.resolve("files/$IMPORT_DIRECTORY_NAME"),preparer,owner.epub,owner.pages)
            val job=launch {source.import(selection("text".encodeToByteArray()).first)}
            entered.await();if(closeOwner)source.close() else job.cancel();job.join()
            assertFailsWith<IllegalStateException>{handle!!.read(ByteArray(1))}
            assertTrue(published(owner).isEmpty());assertTrue(partials(owner).isEmpty())
            source.close();source.awaitClosed();owner.close()
        }
    }
    @Test fun failedMetadataPublicationDoesNotDestroyPreviouslyCommittedImport()=runBlocking<Unit> {
        val owner=Owner();val previous=owner.source.import(selection("previous".encodeToByteArray()).first)
        owner.source.close();owner.source.awaitClosed()
        val preparer=object:TextPreparer {
            override suspend fun prepare(publication:Publication,resource:PublicationResource,loader:ResourceLoader,dispatcher:CoroutineDispatcher):TextDocument {
                val doc=owner.text.prepare(publication,resource,loader,dispatcher)
                // Simulate a failed CREATE_NEW record write, without depending on root/OS permissions.
                Files.createDirectory(partials(owner).single().resolve("record"))
                return doc
            }
        }
        val source=FileLocalPublicationSource(owner.base.resolve("files/$IMPORT_DIRECTORY_NAME"),preparer,owner.epub,owner.pages)
        try {
            assertEquals(ImportFailure.STORAGE,assertFailsWith<LocalImportException>{source.import(selection("new".encodeToByteArray()).first)}.failure)
            assertEquals(previous,source.getPublication(previous.id));assertEquals(1,published(owner).size);assertTrue(partials(owner).isEmpty())
        } finally {source.close();source.awaitClosed();owner.close()}
    }
    private class Owner(val base: Path = Files.createTempDirectory("imports-test"), maxBytes: Long = MAX_LOCAL_IMPORT_BYTES,
                        maxEntries: Int = 256, maxTotal: Long = 512L*1024*1024) {
        val text = FileTextPreparer(base.resolve("cache/text"))
        val epub = FileEpubPreparer(base.resolve("cache/epub"))
        val pages = CbzPagePreparer(base.resolve("cache/cbz"))
        val source = FileLocalPublicationSource(base.resolve("files/$IMPORT_DIRECTORY_NAME"), text, epub, pages,maxBytes,maxEntries,maxTotal)
        suspend fun close() { source.close();source.awaitClosed();text.close();epub.close();pages.close();text.awaitClosed();epub.awaitClosed();pages.awaitClosed() }
    }
    private class Bytes(val bytes: ByteArray, override val sizeBytes: Long? = bytes.size.toLong(), val failAt: Int = Int.MAX_VALUE) : ResourceContent {
        var closed=false; var position=0; var largestRead=0
        override suspend fun read(buffer: ByteArray,offset: Int,length: Int): Int {
            check(!closed); currentCoroutineContext().ensureActive(); largestRead=maxOf(largestRead,length)
            if(position>=failAt) throw java.io.IOException("not shown")
            if(position == bytes.size) return -1
            val n=minOf(length,37,bytes.size-position); bytes.copyInto(buffer,offset,position,position+n);position+=n;return n
        }
        override fun close(){closed=true}
    }
    private fun selection(bytes: ByteArray,name: String?="book.txt",size: Long?=bytes.size.toLong(),failAt: Int=Int.MAX_VALUE): Pair<LocalFileSelection,Bytes> {
        val content=Bytes(bytes,size,failAt);return LocalFileSelection(name){content} to content
    }
    private fun published(owner: Owner): List<Path> = Files.list(owner.base.resolve("files/$IMPORT_DIRECTORY_NAME")).use { s -> s.filter { it.fileName.toString().endsWith(".import") }.toList() }
    private fun partials(owner: Owner): List<Path> = Files.list(owner.base.resolve("files/$IMPORT_DIRECTORY_NAME")).use { s -> s.filter { it.fileName.toString().endsWith(".part") }.toList() }

    @Test fun textImportStreamsAndCommitsAnOwnedStableIdentity()=runBlocking<Unit> {
        val owner=Owner();val data="Hello 🦦\n".repeat(4000).encodeToByteArray();val (offer,content)=selection(data)
        try {
            val publication=owner.source.import(offer)
            assertEquals(LOCAL_SOURCE_ID,publication.id.sourceId);assertTrue(Regex("[0-9a-f]{64}").matches(publication.id.localId))
            assertEquals(publication.id.localId,publication.resources.single().revision)
            assertEquals(PublicationFormat.TEXT,publication.resources.single().format)
            assertTrue(content.closed);assertTrue(content.largestRead<=8192);assertEquals(1,published(owner).size);assertTrue(partials(owner).isEmpty())
            assertContentEquals(data,owner.source.loadResource(publication.resources.single()).readBytes(data.size))
        } finally {owner.close()}
    }
    @Test fun genuineNewSourceRestoresWithoutExternalOriginal()=runBlocking<Unit> {
        val first=Owner();val bytes="Original text\n".encodeToByteArray()
        val external=Files.write(first.base.resolve("external.txt"),bytes)
        val pub=first.source.import(LocalFileSelection("external.txt") { Bytes(Files.readAllBytes(external)) })
        first.close();Files.delete(external)
        val second=Owner(first.base)
        try { assertEquals(pub,second.source.getPublication(pub.id));assertContentEquals(bytes,second.source.loadResource(pub.resources.single()).readBytes(1024)) }
        finally {second.close()}
    }
    @Test fun identicalBytesUnderDifferentNamesDeduplicateWithoutOverwritingMetadata()=runBlocking<Unit> {
        val owner=Owner();try {
            val pub=owner.source.import(selection("same bytes".encodeToByteArray(),"first.txt").first)
            val again=owner.source.import(selection("same bytes".encodeToByteArray(),"second.epub").first)
            assertEquals(pub,again);assertEquals(1,published(owner).size);assertTrue(partials(owner).isEmpty())
        } finally {owner.close()}
    }
    @Test fun differentBytesWithSameFilenameHaveDifferentIdentity()=runBlocking<Unit> {
        val owner=Owner();try {
            val a=owner.source.import(selection("first".encodeToByteArray()).first)
            val b=owner.source.import(selection("second".encodeToByteArray()).first)
            assertNotEquals(a.id,b.id);assertEquals(2,published(owner).size)
        } finally {owner.close()}
    }
    @Test fun maliciousFilenameIsOnlyASanitizedDisplayHint()=runBlocking<Unit> {
        val owner=Owner();try {
            val pub=owner.source.import(selection("text".encodeToByteArray(),"../../outside/unsafe\u0000\n.txt").first)
            assertEquals("unsafe",pub.title);assertFalse(Files.exists(owner.base.resolve("outside")))
            assertEquals("Imported publication",fallbackTitle(null));assertTrue(fallbackTitle("🦦".repeat(300)).length<=256)
        } finally {owner.close()}
    }
    @Test fun unknownSizeIsBoundedAndAcceptedForSupportedInput()=runBlocking<Unit> {
        val owner=Owner();try { assertEquals(PublicationFormat.TEXT,owner.source.import(selection("text".encodeToByteArray(),size=null).first).resources.single().format) }
        finally {owner.close()}
    }
    @Test fun zeroShortOverlongNegativeAndReadFailuresCloseAndNeverPublish()=runBlocking<Unit> {
        val owner=Owner();try {
            for ((offer,content) in listOf(selection(byteArrayOf()),selection("text".encodeToByteArray(),size=2),selection("text".encodeToByteArray(),size=8),selection("text".encodeToByteArray(),size=-1),selection("text".encodeToByteArray(),failAt=0))) {
                assertFailsWith<LocalImportException>{owner.source.import(offer)};assertTrue(content.closed)
                assertTrue(published(owner).isEmpty());assertTrue(partials(owner).isEmpty())
            }
        } finally {owner.close()}
    }
    @Test fun exactConfiguredLimitAcceptsButOneExtraByteRejectsKnownOrUnknownSize()=runBlocking<Unit> {
        val owner=Owner(maxBytes=100);try {
            owner.source.import(selection(ByteArray(100){65}).first)
            for (size in listOf(101L,null)) {
                val (offer,content)=selection(ByteArray(101){66},size=size)
                assertEquals(ImportFailure.LIMIT,assertFailsWith<LocalImportException>{owner.source.import(offer)}.failure);assertTrue(content.closed)
            }
            assertEquals(1,published(owner).size);assertTrue(partials(owner).isEmpty())
        } finally {owner.close()}
    }
    @Test fun catalogEntryAndByteCapacityAreFinite()=runBlocking<Unit> {
        val owner=Owner(maxEntries=1,maxTotal=10);try {
            owner.source.import(selection("first".encodeToByteArray()).first)
            assertEquals(ImportFailure.LIMIT,assertFailsWith<LocalImportException>{owner.source.import(selection("second".encodeToByteArray()).first)}.failure)
        } finally {owner.close()}
        val bytes=Owner(maxTotal=5);try { assertFailsWith<LocalImportException>{bytes.source.import(selection("sixxxx".encodeToByteArray()).first)};assertTrue(published(bytes).isEmpty()) }
        finally {bytes.close()}
    }
    @Test fun realEpubAndCbzValidateDespiteMisleadingExtensions()=runBlocking<Unit> {
        val owner=Owner();try {
            val epub=owner.source.import(selection(developmentEpubBytes(),"not-a-book.txt").first)
            val cbz=owner.source.import(selection(developmentCbzBytes(),"not-a-comic.epub").first)
            assertEquals(PublicationFormat.EPUB,epub.resources.single().format);assertEquals(PublicationType.BOOK,epub.type)
            assertEquals(PublicationFormat.CBZ,cbz.resources.single().format);assertEquals(PublicationType.COMIC,cbz.type)
            val ebook=owner.epub.prepare(epub,epub.resources.single(),DirectResourceLoader(owner.source));ebook.close()
            val comic=owner.pages.prepare(cbz,DirectResourceLoader(owner.source));assertEquals(10,comic.pages.size);comic.close()
        } finally {owner.close()}
    }
    @Test fun unsupportedBinaryPdfMalformedZipAndNonComicZipNeverPublish()=runBlocking<Unit> {
        val owner=Owner();val ordinaryZip=ByteArrayOutputStream().use { out -> ZipOutputStream(out).use { it.putNextEntry(ZipEntry("note.txt"));it.write("note".encodeToByteArray());it.closeEntry() };out.toByteArray() }
        try {
            for(data in listOf(byteArrayOf(0,1,2),byteArrayOf(0xff.toByte()),"%PDF-1.7".encodeToByteArray(),"PKbad zip".encodeToByteArray(),ordinaryZip)) {
                assertFailsWith<LocalImportException>{owner.source.import(selection(data).first)}
                assertTrue(published(owner).isEmpty());assertTrue(partials(owner).isEmpty())
            }
        } finally {owner.close()}
    }
    @Test fun cancellationMidCopyDiscardsPartialAndClosesProvider()=runBlocking<Unit> {
        val owner=Owner();val entered=CompletableDeferred<Unit>();var closed=false
        val content=object:ResourceContent { override val sizeBytes:Long?=null
            override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int { entered.complete(Unit);awaitCancellation() }
            override fun close(){closed=true} }
        val job=launch {owner.source.import(LocalFileSelection("text.txt"){content})};entered.await();job.cancelAndJoin()
        try {assertTrue(closed);assertTrue(partials(owner).isEmpty());assertTrue(published(owner).isEmpty())}finally{owner.close()}
    }
    @Test fun ownerCloseCancelsImportAndIsIdempotent()=runBlocking<Unit> {
        val owner=Owner();val entered=CompletableDeferred<Unit>();var closed=false
        val content=object:ResourceContent {override val sizeBytes:Long?=null
            override suspend fun read(buffer:ByteArray,offset:Int,length:Int):Int {entered.complete(Unit);awaitCancellation()}
            override fun close(){closed=true}}
        val job=launch {owner.source.import(LocalFileSelection("text.txt"){content})};entered.await();owner.source.close();job.join();owner.close()
        assertTrue(closed);assertTrue(partials(owner).isEmpty());assertFailsWith<CancellationException>{owner.source.import(selection("text".encodeToByteArray()).first)}
    }
    @Test fun stalePartialNamespaceCleansWithoutDeletingUnrelatedOrCommittedFiles()=runBlocking<Unit> {
        val owner=Owner();val root=Files.createDirectories(owner.base.resolve("files/$IMPORT_DIRECTORY_NAME"))
        val partial=Files.createDirectory(root.resolve("import-12345678-1234-1234-1234-123456789abc.part"));Files.write(partial.resolve("payload"),byteArrayOf(1))
        val unrelated=Files.writeString(root.resolve("keep.txt"),"keep")
        try {owner.source.search("*",null);assertFalse(Files.exists(partial));assertTrue(Files.exists(unrelated))}finally{owner.close()}
    }
    @Test fun symlinkRootAndUnsafeResourceIdentityAreRejected()=runBlocking<Unit> {
        val owner=Owner();val target=Files.createTempDirectory("imports-target");Files.createDirectories(owner.base.resolve("files"))
        Files.createSymbolicLink(owner.base.resolve("files/$IMPORT_DIRECTORY_NAME"),target)
        try {assertFailsWith<LocalImportException>{owner.source.import(selection("text".encodeToByteArray()).first)};assertEquals(0,Files.list(target).use{it.count()})}
        finally {owner.close()}
        val safe=Owner();try {
            assertFailsWith<IllegalArgumentException>{safe.source.getPublication(PublicationId(LOCAL_SOURCE_ID,"../../file"))}
            val p=safe.source.import(selection("text".encodeToByteArray()).first)
            assertFailsWith<IllegalArgumentException>{safe.source.loadResource(p.resources.single().copy(key="other"))}
            assertFailsWith<IllegalArgumentException>{safe.source.loadResource(p.resources.single().copy(revision="fake"))}
            assertFailsWith<IllegalArgumentException>{safe.source.getPublication(p.id.copy(sourceId=SourceId("other")))}
        } finally {safe.close()}
    }
    @Test fun corruptionCannotMasqueradeAsValidMetadataOrMatchingRevision()=runBlocking<Unit> {
        val owner=Owner();try {
            val p=owner.source.import(selection("text".encodeToByteArray()).first);val entry=published(owner).single()
            Files.write(entry.resolve("payload"),"bad!".encodeToByteArray())
            assertFailsWith<LocalImportException>{owner.source.loadResource(p.resources.single()).readBytes(64)}
            assertFailsWith<LocalImportException>{owner.source.import(selection("text".encodeToByteArray()).first)}
            Files.write(entry.resolve("record"),ByteArray(16385))
            assertNull(owner.source.getPublication(p.id));assertTrue(owner.source.search("*",null).publications.isEmpty())
        } finally {owner.close()}
    }
    @Test fun documentCleanupAndCacheDeletionDoNotDeleteDurableImports()=runBlocking<Unit> {
        val first=Owner();val p=first.source.import(selection("text🦦".encodeToByteArray()).first)
        val doc=first.text.prepare(p,p.resources.single(),DirectResourceLoader(first.source),Dispatchers.IO);doc.close();first.close()
        first.base.resolve("cache").toFile().deleteRecursively()
        val second=Owner(first.base);try {assertEquals(p,second.source.getPublication(p.id));assertEquals(1,published(second).size)}finally{second.close()}
    }
    @Test fun handlesAreBoundedAndOwnerCloseInvalidatesThem()=runBlocking<Unit> {
        val owner=Owner();val p=owner.source.import(selection("text".encodeToByteArray()).first)
        val handles=List(8){owner.source.loadResource(p.resources.single())}
        assertFailsWith<LocalImportException>{owner.source.loadResource(p.resources.single())}
        owner.close();for(h in handles)assertFailsWith<IllegalStateException>{h.read(ByteArray(1))}
    }
    @Test fun searchReturnsStableBoundedCatalogWithoutExternalLocations()=runBlocking<Unit> {
        val owner=Owner();try {
            val p=owner.source.import(selection("text".encodeToByteArray(),"Book.txt").first)
            assertEquals(listOf(p),owner.source.search("book",null).publications);assertTrue(owner.source.search("absent",null).publications.isEmpty())
            assertNull(p.sourceUrl);assertNull(p.rights);assertFailsWith<IllegalArgumentException>{owner.source.search("*","page2")}
        } finally {owner.close()}
    }
}
