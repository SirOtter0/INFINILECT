// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.page

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.CRC32
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.infinilect.app.media.Raster
import org.infinilect.app.media.RasterPolicy
import org.infinilect.app.reader.page.*
import org.infinilect.core.*
import kotlin.test.*

class CbzPagePreparerTest {
    private val id = PublicationId(SourceId("fixture-cbz"), "comic")
    private val png = runBlocking { comicPng(1, PageDimensions(16, 24)) }
    private val png2 = runBlocking { comicPng(2, PageDimensions(18, 24)) }
    private val png3 = runBlocking { comicPng(3, PageDimensions(20, 24)) }
    private val pngNested = runBlocking { comicPng(4, PageDimensions(22, 24)) }
    private val pngZeroPadded = runBlocking { comicPng(5, PageDimensions(21, 24)) }
    private val jpeg = comicJpeg()
    private val sourceResource = PublicationResource(id, "archive", PublicationFormat.CBZ, "application/vnd.comicbook+zip")
    private fun publication(resource: PublicationResource = sourceResource) = Publication(id, "Original CBZ", PublicationType.COMIC, resources = listOf(resource))
    private fun archive(items: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip -> items.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply {
                time = 0L
                if (name.endsWith('/')) { method = ZipEntry.STORED; size = 0; compressedSize = 0; crc = CRC32().apply { update(bytes) }.value }
            }); zip.write(bytes); zip.closeEntry()
        } }
        out.toByteArray()
    }
    private fun storedArchive(name: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name).apply {
                method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            })
            zip.write(bytes); zip.closeEntry()
        }
        out.toByteArray()
    }
    private fun loader(bytes: ByteArray, chunk: Int = 37, onClose: () -> Unit = {}): ResourceLoader = object : ResourceLoader {
        override suspend fun load(resource: PublicationResource): ResourceContent {
            assertEquals(sourceResource, resource)
            return object : ResourceContent {
                override val sizeBytes = bytes.size.toLong()
                private var position = 0; private var closed = false
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    check(!closed); currentCoroutineContext().ensureActive()
                    if (position == bytes.size) return -1
                    val n = minOf(length, chunk, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + n); position += n; return n
                }
                override fun close() { closed = true; onClose() }
            }
        }
    }
    @Test fun epubCompatibilityDoesNotRelaxCbzNamesOrDirectoryPolicy() = runBlocking<Unit> {
        val root = Files.createTempDirectory("cbz-policy-regression")
        val owner = CbzPagePreparer(root)
        try {
            for (name in listOf("page one.png", "é.png", "../page.png", "page%20one.png"))
                assertFails { owner.prepare(publication(), loader(archive(listOf(name to png)))) }
            val compressedDirectory = ByteArrayOutputStream().use { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("pages/")); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("pages/1.png")); zip.write(png); zip.closeEntry()
                }
                out.toByteArray()
            }
            assertFails { owner.prepare(publication(), loader(compressedDirectory)) }
            val valid = owner.prepare(publication(), loader(archive(listOf("pages/" to byteArrayOf(), "pages/1.png" to png))))
            assertEquals(1, valid.pages.size); valid.close()
        } finally { owner.close(); owner.awaitClosed(); root.toFile().deleteRecursively() }
    }
    @Test fun preparesValidatedPngJpegPagesInNaturalPathOrderAndPageReaderCanOpenThem() = runBlocking<Unit> {
        val bytes = archive(listOf("pages/10.jpg" to jpeg, "pages/2.png" to png2, "pages/1.png" to png,
            "extras/nested/page4.png" to pngNested, "pages/03.png" to pngZeroPadded, "pages/3.png" to png3))
        val base = Files.createTempDirectory("cbz-valid")
        val owner = CbzPagePreparer(base)
        try {
            val doc = owner.prepare(publication(), loader(bytes))
            assertEquals(id, doc.publicationId)
            assertEquals(listOf("cbz-page-0000", "cbz-page-0001", "cbz-page-0002", "cbz-page-0003", "cbz-page-0004", "cbz-page-0005"), doc.pages.map { it.key })
            assertEquals(listOf("image/png", "image/png", "image/png", "image/png", "image/png", "image/jpeg"), doc.pages.map { it.resource.mediaType })
            assertEquals(listOf(22, 16, 18, 20, 21, 640), doc.pages.map { it.dimensions.width })
            val undeclared = PageEntry(PublicationResource(id, "unlisted", PublicationFormat.PAGES,
                "image/png", pageDimensions = PageDimensions(16, 24)))
            assertFails { doc.openPage(undeclared) }
            val foreign = undeclared.copy(resource = undeclared.resource.copy(publicationId = PublicationId(SourceId("other"), "comic")))
            assertFails { doc.openPage(foreign) }
            val handle = doc.openPage(doc.pages[0])
            val read = handle.readBytes(RasterPolicy.ENCODED_BYTES)
            assertContentEquals(pngNested, read); handle.close()
            val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = PageReaderController(doc, controllerScope, decoder = object : org.infinilect.app.media.RasterDecoder {
                override suspend fun decode(bytes: ByteArray, mediaType: String): Raster {
                    val (width, height) = org.infinilect.app.media.inspectRaster(bytes, mediaType)
                    return Raster(width, height, IntArray(width * height))
                }
            })
            controller.initialize(null)
            assertTrue(withTimeout(3_000) { controller.state.first { it.frames[0] is PageFrame.Ready } }.frames[0] is PageFrame.Ready)
            controller.close(); controllerScope.cancel()
        } finally { owner.close(); owner.awaitClosed() }
        assertTrue(Files.exists(base.resolve(".owner.lock")))
    }

    @Test fun naturalOrderingIsLocaleIndependentAndNumbersAreComparedByValue() {
        assertEquals(listOf("x/1.png", "x/2.png", "x/3.png", "x/03.png", "x/10.png"),
            listOf("x/10.png", "x/3.png", "x/03.png", "x/2.png", "x/1.png").sortedWith(::compareNaturalPath))
        assertTrue(compareNaturalPath("A/2.png", "a/2.png") < 0) // original ordinal tie break
    }

    @Test fun rejectsEmptyArchiveUnsupportedPayloadAndPageCountOverflow() = runBlocking<Unit> {
        val base = Files.createTempDirectory("cbz-reject")
        val owner = CbzPagePreparer(base)
        try {
            for (bytes in listOf(archive(emptyList()), archive(listOf("note.txt" to byteArrayOf(1)))) )
                assertFails { owner.prepare(publication(), loader(bytes)) }
            val many = archive((0..PagePolicy.MAX_PAGES).map { "${it}.png" to png })
            assertFails { owner.prepare(publication(), loader(many)) }
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun rejectsExtensionMismatchCorruptImageAndTraversal() = runBlocking<Unit> {
        val owner = CbzPagePreparer(Files.createTempDirectory("cbz-invalid"))
        try {
            for (items in listOf(
                listOf("1.jpg" to png),
                listOf("1.png" to png.copyOf().apply { this[29] = (this[29].toInt() xor 1).toByte() }),
                listOf("1.jpg" to jpeg.copyOf().apply { this[this.lastIndex] = 0.toByte() }),
                listOf("../1.png" to png),
            ))
                assertFails { owner.prepare(publication(), loader(archive(items))) }
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun rejectsCaseAliasAndDuplicatePagePaths() = runBlocking<Unit> {
        val owner = CbzPagePreparer(Files.createTempDirectory("cbz-alias"))
        try {
            for (items in listOf(listOf("a/1.png" to png, "A/1.png" to png), listOf("1.png" to png, "1.PNG" to png)))
                assertFails { owner.prepare(publication(), loader(archive(items))) }
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun acceptsExplicitDirectoriesButRejectsFileDirectoryAncestorAmbiguity() = runBlocking<Unit> {
        val withDirectory = archive(listOf("pages/" to byteArrayOf(), "pages/1.png" to png))
        val owner = CbzPagePreparer(Files.createTempDirectory("cbz-dirs"))
        try {
            assertEquals(1, owner.prepare(publication(), loader(withDirectory)).pages.size)
            assertFails { owner.prepare(publication(), loader(archive(listOf("pages" to png, "pages/1.png" to png)))) }
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun rejectsArchivePrefixTrailerAndCrcCorruption() = runBlocking<Unit> {
        val good = archive(listOf("1.png" to png))
        val owner = CbzPagePreparer(Files.createTempDirectory("cbz-structural"))
        try {
            assertFails { owner.prepare(publication(), loader(byteArrayOf(1) + good)) }
            assertFails { owner.prepare(publication(), loader(good + byteArrayOf(1))) }
            val stored = storedArchive("1.png", png)
            val badCrc = stored.copyOf().apply { this[35] = (this[35].toInt() xor 1).toByte() }
            assertFails { owner.prepare(publication(), loader(badCrc)) }
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun rejectsEncryptedFlagsAndEntryLimits() = runBlocking<Unit> {
        val good = archive(listOf("1.png" to png))
        val central = good.indexOfSignature(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        val encrypted = good.copyOf().apply { this[central + 8] = (this[central + 8].toInt() or 1).toByte() }
        val owner = CbzPagePreparer(Files.createTempDirectory("cbz-flags"))
        try {
            assertFails { owner.prepare(publication(), loader(encrypted)) }
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun rejectsZip64SplitSpecialFilesAndCentralLocalDisagreement() = runBlocking<Unit> {
        val good = archive(listOf("1.png" to png))
        val central = good.indexOfSignature(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        val eocd = good.indexOfSignature(byteArrayOf(0x50, 0x4b, 0x05, 0x06))
        val zip64 = good.copyOf().apply { repeat(4) { this[central + 20 + it] = 0xff.toByte() } }
        val split = good.copyOf().apply { this[eocd + 4] = 1 }
        val badLocal = good.copyOf().apply { this[6] = (this[6].toInt() xor 1).toByte() }
        val symlink = good.copyOf().apply { this[central + 40] = 0; this[central + 41] = 0xa0.toByte() }
        val unsupportedMethod = good.copyOf().apply {
            this[8] = 12; this[9] = 0
            this[central + 10] = 12; this[central + 11] = 0
        }
        val owner = CbzPagePreparer(Files.createTempDirectory("cbz-zip-types"))
        try {
            for (bad in listOf(zip64, split, badLocal, symlink, unsupportedMethod)) assertFails { owner.prepare(publication(), loader(bad)) }
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun rejectsOversizedEncodedPageAndCompressionBombBeforeImageDecode() = runBlocking<Unit> {
        val oversized = ByteArray(RasterPolicy.ENCODED_BYTES + 1) { 1 }
        val bomb = archive(listOf("1.png" to ByteArray(1024 * 1024)))
        val owner = CbzPagePreparer(Files.createTempDirectory("cbz-bounds"))
        try {
            assertFails { owner.prepare(publication(), loader(archive(listOf("1.png" to oversized)))) }
            assertFails { owner.prepare(publication(), loader(bomb)) }
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun cancellationClosesAcquisitionAndCloseInvalidatesPageHandles() = runBlocking<Unit> {
        val base = Files.createTempDirectory("cbz-lifecycle")
        val owner = CbzPagePreparer(base)
        val entered = CompletableDeferred<Unit>(); var closed = false
        val stalled = object : ResourceLoader {
            override suspend fun load(resource: PublicationResource) = object : ResourceContent {
                override val sizeBytes = 1024L
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int { entered.complete(Unit); awaitCancellation() }
                override fun close() { closed = true }
            }
        }
        try {
            val prep = launch { owner.prepare(publication(), stalled) }
            entered.await(); prep.cancelAndJoin(); assertTrue(closed)
            val doc = owner.prepare(publication(), loader(archive(listOf("1.png" to png))))
            val handle = doc.openPage(doc.pages.single()); doc.close()
            assertFailsWith<IllegalStateException> { handle.read(ByteArray(10)) }
            assertFails { doc.openPage(doc.pages.single()) }
            handle.close()
        } finally { owner.close(); owner.awaitClosed() }
        Files.newDirectoryStream(base).use { files -> assertTrue(files.none { it.fileName.toString().endsWith(".zip") || it.fileName.toString().endsWith(".part") }) }
    }

    @Test fun closingOwnerDuringPreparationCancelsTransferAndRemovesPartialArchive() = runBlocking<Unit> {
        val base = Files.createTempDirectory("cbz-owner-close")
        val owner = CbzPagePreparer(base)
        val entered = CompletableDeferred<Unit>(); var closed = false
        val stalled = object : ResourceLoader {
            override suspend fun load(resource: PublicationResource) = object : ResourceContent {
                override val sizeBytes = 1024L
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.complete(Unit); awaitCancellation()
                }
                override fun close() { closed = true }
            }
        }
        val preparation = launch { owner.prepare(publication(), stalled) }
        entered.await(); owner.close(); preparation.join(); owner.awaitClosed()
        assertTrue(closed)
        Files.newDirectoryStream(base).use { files -> assertTrue(files.none { it.fileName.toString().endsWith(".zip") || it.fileName.toString().endsWith(".part") }) }
    }

    @Test fun staleOwnedPartialIsCleanedButUnrelatedFileRemains() = runBlocking<Unit> {
        val base = Files.createTempDirectory("cbz-stale")
        val stale = Files.write(base.resolve("cbz-12345678-1234-1234-1234-123456789abc.part"), byteArrayOf(1))
        val unrelated = Files.writeString(base.resolve("keep.txt"), "keep")
        val owner = CbzPagePreparer(base)
        try { owner.prepare(publication(), loader(archive(listOf("1.png" to png)))).close(); assertFalse(Files.exists(stale)); assertTrue(Files.exists(unrelated)) }
        finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun developmentSourceFeedsTheSameArchivePageReaderAdapter() = runBlocking<Unit> {
        val expectedPaths = listOf("pages/1.png", "pages/2.png", "pages/03.jpg", "pages/04/nested.png",
            "pages/5.png", "pages/6.png", "pages/7.png", "pages/8.jpg", "pages/9.png", "pages/10.jpg")
        val archivePages = ZipInputStream(ByteArrayInputStream(developmentCbzBytes())).use { zip ->
            buildList {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name to zip.readBytes())
                    zip.closeEntry()
                }
            }
        }
        assertNotEquals(expectedPaths, archivePages.map { it.first })
        assertEquals(expectedPaths, archivePages.map { it.first }.sortedWith(::compareNaturalPath))
        val expectedBytes = archivePages.toMap()
        val source = DevelopmentCbzSource(); val publication = source.search("original", null).publications.single()
        assertEquals(PublicationType.COMIC, publication.type); assertEquals(PublicationFormat.CBZ, publication.resources.single().format)
        val owner = CbzPagePreparer(Files.createTempDirectory("cbz-development"))
        try {
            val doc = owner.prepare(publication, org.infinilect.app.acquisition.DirectResourceLoader(source))
            assertEquals(10, doc.pages.size)
            assertEquals("cbz-page-0000", doc.pages.first().key)
            for ((page, path) in doc.pages.zip(expectedPaths)) {
                val handle = doc.openPage(page)
                try { assertContentEquals(expectedBytes.getValue(path), handle.readBytes(RasterPolicy.ENCODED_BYTES), path) }
                finally { handle.close() }
            }
            doc.close()
        } finally { owner.close(); owner.awaitClosed() }
    }

    private fun ByteArray.indexOfSignature(signature: ByteArray): Int = (0..size - signature.size).first { start -> signature.indices.all { this[start + it] == signature[it] } }
}
