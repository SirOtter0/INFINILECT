// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.app.reader.*
import org.infinilect.core.*

/** Host JVM tests shared by Desktop and Android host targets, NOT Android device tests. */
class FileEpubPreparerTest {
    private suspend fun valid(fixture: EpubFixture = EpubFixture(), action: suspend (EpubDocument) -> Unit = {}) {
        val base = Files.createTempDirectory("epub-test")
        val owner = FileEpubPreparer(base)
        val content = EpubBytes(fixture.zip())
        try {
            val doc = owner.prepare(epubPublication, epubResource, epubLoader(content))
            assertTrue(content.closed)
            assertTrue(content.maximumRequest <= EPUB_BUFFER_BYTES)
            assertEquals(1, payloads(base).size)
            try { action(doc) } finally { doc.close(); doc.close() }
        } finally { owner.close(); owner.close(); owner.awaitClosed() }
        assertTrue(payloads(base).isEmpty())
    }
    private suspend fun rejected(bytes: ByteArray, limits: EpubLimits = EpubLimits(), declared: Long? = bytes.size.toLong()): EpubException {
        val base = Files.createTempDirectory("epub-reject")
        val owner = FileEpubPreparer(base, limits)
        val content = EpubBytes(bytes, declared)
        try {
            val error = assertFailsWith<EpubException> { owner.prepare(epubPublication, epubResource, epubLoader(content)) }
            assertTrue(content.closed)
            assertTrue(payloads(base).isEmpty(), "Invalid input must never retain a prepared file")
            return error
        } finally { owner.close(); owner.awaitClosed() }
    }
    private suspend fun invalidFixture(change: EpubFixture.() -> Unit) {
        assertEquals(EpubFailure.INVALID, rejected(EpubFixture().apply(change).zip()).failure)
    }

    @Test fun minimalEpub3HasMetadataSpineAndFreshLocalHandles() = runBlocking<Unit> {
        valid { doc ->
            assertEquals(epubId, doc.publicationId)
            assertEquals("Résumé 📚", doc.metadata.title)
            assertEquals(listOf("fr", "en"), doc.metadata.languages)
            assertEquals(listOf("作者"), doc.metadata.creators)
            assertEquals("Original test fixture", doc.metadata.rights)
            assertEquals("nav", doc.navigationItemId)
            assertEquals(listOf(EpubSpineItem("chapter")), doc.spine)
            val chapter = doc.manifest.single { it.id == "chapter" }.path
            val first = doc.openResource(chapter).readBytes(4096)
            val second = doc.openResource(chapter).readBytes(4096)
            assertContentEquals(first, second)
            assertTrue(first.decodeToString().contains("世界 📖"))
            assertFailsWith<IllegalArgumentException> { doc.openResource(EpubEntryPath("META-INF/container.xml")) }
        }
    }
    @Test fun deflatedEntriesAndDataDescriptorsAreSupported() = runBlocking<Unit> {
        valid(EpubFixture().apply { deflate = true })
    }
    @Test fun nestedPackageAndSafeParentReferencesResolveWithinArchive() = runBlocking<Unit> {
        val fixture = EpubFixture("OPS/Package/book.opf")
        fixture.entries["OPS/chapter.xhtml"] = fixture.entries.remove("OPS/Package/chapter.xhtml")!!
        fixture.opf { it.replace("href=\"chapter.xhtml\"", "href=\"../chapter.xhtml\"") }
        fixture.change("OPS/Package/nav.xhtml") { it.replace("chapter.xhtml#start", "../chapter.xhtml#start") }
        valid(fixture) { assertEquals(EpubEntryPath("OPS/chapter.xhtml"), it.manifest.single { item -> item.id == "chapter" }.path) }
    }
    @Test fun multipleManifestAndSpineItemsKeepOrderAndLinearSemantics() = runBlocking<Unit> {
        valid(EpubFixture().apply {
            entries["OPS/second.xhtml"] = entries["OPS/chapter.xhtml"]!!
            opf { it.replace("</manifest>", "<item id=\"second\" href=\"second.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>")
                .replace("</spine>", "<itemref idref=\"second\" linear=\"no\"/></spine>") }
        }) { assertEquals(listOf(EpubSpineItem("chapter"), EpubSpineItem("second", false)), it.spine) }
    }
    @Test fun unknownAcquisitionSizeStillHasFiniteStreamingLimit() = runBlocking<Unit> {
        val base = Files.createTempDirectory("epub-unknown")
        val owner = FileEpubPreparer(base)
        val content = EpubBytes(EpubFixture().zip(), null, 1)
        try { owner.prepare(epubPublication, epubResource, epubLoader(content)).close(); assertTrue(content.closed) }
        finally { owner.close(); owner.awaitClosed() }
        assertTrue(payloads(base).isEmpty())
    }
    @Test fun missingContainerIsRejected() = runBlocking<Unit> { invalidFixture { entries.remove("META-INF/container.xml") } }
    @Test fun malformedContainerIsRejected() = runBlocking<Unit> { invalidFixture { entries["META-INF/container.xml"] = "<broken".encodeToByteArray() } }
    @Test fun multipleRootfilesAreRejected() = runBlocking<Unit> {
        invalidFixture { change("META-INF/container.xml") { it.replace("</rootfiles>", "<rootfile full-path=\"OPS/package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles>") } }
    }
    @Test fun unsafeAndMissingRootfileReferencesAreRejected() = runBlocking<Unit> {
        for (path in listOf("../book.opf", "/book.opf", "https://example.org/book", "%2e%2e/book", "missing.opf")) {
            invalidFixture { change("META-INF/container.xml") { it.replace(packagePath, path) } }
        }
    }
    @Test fun missingOpfIsRejected() = runBlocking<Unit> { invalidFixture { entries.remove(packagePath) } }
    @Test fun malformedOpfIsRejected() = runBlocking<Unit> { invalidFixture { entries[packagePath] = "<package>".encodeToByteArray() } }
    @Test fun requiredMetadataAndPackageStructuresAreEnforced() = runBlocking<Unit> {
        for (tag in listOf("title", "language", "identifier")) {
            invalidFixture { opf { it.replace(Regex("<dc:$tag[^>]*>.*?</dc:$tag>"), "") } }
        }
        for (tag in listOf("metadata", "manifest", "spine")) {
            invalidFixture { opf { it.replace(Regex("<$tag[^>]*>[\\s\\S]*?</$tag>"), "") } }
        }
        invalidFixture { opf { it.replace("dcterms:modified", "unknown") } }
        invalidFixture { opf { it.replace("2026-10-04T00:00:00Z", "2026-99-99T00:00:00Z") } }
        invalidFixture { opf { it.replace("version=\"3.0\"", "version=\"2.0\"") } }
    }
    @Test fun duplicateManifestIdsAndPathsAreRejected() = runBlocking<Unit> {
        invalidFixture { opf { it.replace("id=\"chapter\"", "id=\"nav\"") } }
        invalidFixture { opf { it.replace("href=\"chapter.xhtml\"", "href=\"nav.xhtml\"") } }
    }
    @Test fun missingOrInvalidSpineItemCannotBePrepared() = runBlocking<Unit> {
        invalidFixture { opf { it.replace("idref=\"chapter\"", "idref=\"missing\"") } }
        invalidFixture { entries.remove("OPS/chapter.xhtml") }
        invalidFixture { opf { it.replace("linear", "unused").replace("<itemref idref=\"chapter\"/>", "<itemref idref=\"chapter\" linear=\"maybe\"/>") } }
        invalidFixture { opf { it.replace("<itemref idref=\"chapter\"/>", "<itemref idref=\"chapter\" linear=\"no\"/>") } }
    }
    @Test fun missingOrInvalidNavigationIsRejected() = runBlocking<Unit> {
        invalidFixture { opf { it.replace("properties=\"nav\"", "") } }
        invalidFixture { change("OPS/nav.xhtml") { it.replace("epub:type=\"toc\"", "epub:type=\"landmarks\"") } }
    }
    @Test fun zipTraversalAbsoluteBackslashAndEncodedNamesAreRejected() = runBlocking<Unit> {
        for (path in listOf("../evil", "/evil", "C:/evil", "OPS\\evil", "OPS/./evil", "OPS/../evil", "OPS//evil",
            "OPS/%2e%2e/evil", "OPS/%252e%252e/evil", "OPS/é.xhtml")) {
            invalidFixture { entries[path] = byteArrayOf(42) }
        }
    }
    @Test fun duplicateCaseAndFileDirectoryAliasesAreRejected() = runBlocking<Unit> {
        invalidFixture { entries["OPS/CHAPTER.xhtml"] = entries["OPS/chapter.xhtml"]!! }
        invalidFixture { entries["OPS"] = byteArrayOf(42) }
        invalidFixture { entries["OPS/chapter.xhtml/"] = byteArrayOf() }
    }
    @Test fun exactDuplicateZipNamesAreRejectedBeforeZipFileLookup() = runBlocking<Unit> {
        val bytes = EpubFixture().apply { entries["OPS/CHAPTER.xhtml"] = entries["OPS/chapter.xhtml"]!! }.zip()
        val old = "OPS/CHAPTER.xhtml".encodeToByteArray()
        val replacement = "OPS/chapter.xhtml".encodeToByteArray()
        for (i in 0..bytes.size - old.size) if (bytes.copyOfRange(i, i + old.size).contentEquals(old)) replacement.copyInto(bytes, i)
        assertEquals(EpubFailure.INVALID, rejected(bytes).failure)
    }
    @Test fun encryptedAndSymlinkZipFlagsAreRejected() = runBlocking<Unit> {
        val original = EpubFixture().zip()
        val central = signature(original, byteArrayOf(80, 75, 1, 2))
        val encrypted = original.copyOf().apply { this[central + 8] = (this[central + 8].toInt() or 1).toByte() }
        assertEquals(EpubFailure.INVALID, rejected(encrypted).failure)
        val symlink = original.copyOf().apply { this[central + 40] = 0; this[central + 41] = 0xa0.toByte() }
        assertEquals(EpubFailure.INVALID, rejected(symlink).failure)
    }
    @Test fun excessiveEntryCountIsBounded() = runBlocking<Unit> {
        assertEquals(EpubFailure.LIMIT, rejected(EpubFixture().zip(), EpubLimits(entries = 4)).failure)
    }
    @Test fun excessiveIndividualAndExpandedSizesAreBounded() = runBlocking<Unit> {
        assertEquals(EpubFailure.LIMIT, rejected(EpubFixture().zip(), EpubLimits(entryBytes = 128)).failure)
        assertEquals(EpubFailure.LIMIT, rejected(EpubFixture().zip(), EpubLimits(expandedBytes = 256)).failure)
    }
    @Test fun extremeCompressionRatioIsRejected() = runBlocking<Unit> {
        val fixture = EpubFixture().apply { deflate = true; entries["padding.bin"] = ByteArray(32_000) }
        assertEquals(EpubFailure.LIMIT, rejected(fixture.zip()).failure)
    }
    @Test fun manifestSpineAndXmlBoundsAreEnforced() = runBlocking<Unit> {
        assertEquals(EpubFailure.LIMIT, rejected(EpubFixture().zip(), EpubLimits(manifest = 1)).failure)
        assertEquals(EpubFailure.LIMIT, rejected(EpubFixture().zip(), EpubLimits(xmlBytes = 64)).failure)
        val fixture = EpubFixture().apply { opf { it.replace("</spine>", "<itemref idref=\"chapter\"/></spine>") } }
        assertEquals(EpubFailure.LIMIT, rejected(fixture.zip(), EpubLimits(spine = 1)).failure)
    }
    @Test fun dtdAndExternalEntityInputsAreRejectedWithoutResolution() = runBlocking<Unit> {
        for (prefix in listOf("<!DOCTYPE package SYSTEM 'https://example.org/evil'>",
            "<!DOCTYPE package [<!ENTITY x SYSTEM 'file:///private'>]>",
            "<!DOCTYPE package [<!ENTITY a 'aaaaaaaa'><!ENTITY b '&a;&a;'>]>")) {
            invalidFixture { opf { prefix + it } }
        }
    }
    @Test fun excessiveXmlDepthAttributesNodesAndStringsAreBounded() = runBlocking<Unit> {
        val limits = EpubLimits()
        for (xml in listOf("<a>".repeat(33) + "</a>".repeat(33), "<a>" + "<b/>".repeat(20_000) + "</a>",
            "<a ${List(33) { "a$it='v'" }.joinToString(" ")}/>", "<a>" + "x".repeat(8193) + "</a>")) {
            assertEquals(EpubFailure.LIMIT, assertFailsWith<EpubException> { parseEpubXml(xml.encodeToByteArray(), limits, null) }.failure)
        }
    }
    @Test fun xmlEncodingXIncludeBaseAndProcessingInstructionsFailClosed() = runBlocking<Unit> {
        for (xml in listOf("<?xml version='1.0' encoding='UTF-16'?><a/>", "<?xml version='1.1'?><a/>",
            "<a xml:base='https://example.org'/>", "<include xmlns='http://www.w3.org/2001/XInclude'/>", "<?execute x?><a/>")) {
            assertFails { parseEpubXml(xml.encodeToByteArray(), EpubLimits(), null) }
        }
        assertFails { parseEpubXml(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), EpubLimits(), null) }
    }
    @Test fun remoteAndUnsafeManifestOrContentReferencesFailClosed() = runBlocking<Unit> {
        for (href in listOf("https://example.org/chapter", "//example.org/chapter", "../../../chapter", "chapter.xhtml?x=1", "chapter.xhtml%23start")) {
            invalidFixture { opf { it.replace("href=\"chapter.xhtml\"", "href=\"$href\"") } }
            invalidFixture { change("OPS/nav.xhtml") { it.replace("chapter.xhtml#start", href) } }
        }
    }
    @Test fun activeXhtmlAndUnsupportedMediaFeaturesAreRejected() = runBlocking<Unit> {
        for (body in listOf("<script>bad()</script>", "<iframe src='chapter.xhtml'/>", "<p onclick='bad()'>x</p>",
            "<img src='https://example.org/image'/>", "<form/>", "<p id='start'>duplicate</p>")) {
            invalidFixture { change("OPS/chapter.xhtml") { it.replace("</body>", "$body</body>") } }
        }
        for (property in listOf("scripted", "remote-resources")) {
            invalidFixture { opf { it.replace("id=\"chapter\"", "id=\"chapter\" properties=\"$property\"") } }
        }
        invalidFixture { opf { it.replace("id=\"chapter\"", "id=\"chapter\" media-overlay=\"overlay\"") } }
    }
    @Test fun encryptionAndSignaturesAreExplicitlyUnsupported() = runBlocking<Unit> {
        for (name in listOf("META-INF/encryption.xml", "META-INF/signatures.xml")) invalidFixture { entries[name] = "<root/>".encodeToByteArray() }
    }
    @Test fun nestedArchivesAreNotAcceptedAsOpaquePayloads() = runBlocking<Unit> {
        invalidFixture { entries["nested.zip"] = byteArrayOf(1) }
        invalidFixture { entries["nested.bin"] = byteArrayOf(80, 75, 3, 4) }
        invalidFixture { entries["nested.bin"] = byteArrayOf(80, 75, 3, 4) + ByteArray(32) }
    }
    @Test fun truncatedArchivePrefixTrailerAndHeaderDisagreementAreRejected() = runBlocking<Unit> {
        val bytes = EpubFixture().zip()
        for (bad in listOf(bytes.copyOf(bytes.size - 3), byteArrayOf(0) + bytes, bytes + byteArrayOf(0),
            bytes.copyOf().apply { this[8] = 8 })) assertEquals(EpubFailure.INVALID, rejected(bad).failure)
    }
    @Test fun payloadCorruptionIsDetectedByCrcBeforePublishingDocument() = runBlocking<Unit> {
        val bytes = EpubFixture().zip()
        bytes[signature(bytes, "Hello,".encodeToByteArray())] = 0
        assertEquals(EpubFailure.INVALID, rejected(bytes).failure)
    }
    @Test fun mimetypeMustBeExactFirstStoredAndWithoutExtras() = runBlocking<Unit> {
        invalidFixture { entries["mimetype"] = "application/not-epub".encodeToByteArray() }
        val fixture = EpubFixture()
        val mime = fixture.entries.remove("mimetype")!!; fixture.entries["mimetype"] = mime
        assertEquals(EpubFailure.INVALID, rejected(fixture.zip()).failure)
    }
    @Test fun declaredShortOverlongNegativeAndZeroTransfersAreRejectedAndClosed() = runBlocking<Unit> {
        val bytes = EpubFixture().zip()
        for (declared in listOf(bytes.size.toLong() - 1, bytes.size.toLong() + 1, -1L, 0L)) {
            assertEquals(EpubFailure.TRANSFER, rejected(bytes, declared = declared).failure)
        }
        assertEquals(EpubFailure.TRANSFER, rejected(byteArrayOf()).failure)
    }
    @Test fun archiveLimitAcceptsExactBoundaryAndRejectsOneExtraByte() = runBlocking<Unit> {
        val bytes = EpubFixture().zip()
        val base = Files.createTempDirectory("epub-exact")
        val owner = FileEpubPreparer(base, EpubLimits(archiveBytes = bytes.size.toLong()))
        try { owner.prepare(epubPublication, epubResource, epubLoader(EpubBytes(bytes))).close() }
        finally { owner.close(); owner.awaitClosed() }
        assertEquals(EpubFailure.LIMIT, rejected(bytes, EpubLimits(archiveBytes = bytes.size.toLong() - 1)).failure)
        assertEquals(EpubFailure.LIMIT, rejected(bytes, EpubLimits(archiveBytes = bytes.size.toLong() - 1), null).failure)
    }
    @Test fun knownOversizeIsRejectedBeforeReading() = runBlocking<Unit> {
        val owner = FileEpubPreparer(Files.createTempDirectory("epub-size"))
        val content = EpubBytes(byteArrayOf(0), EpubLimits().archiveBytes + 1)
        try {
            assertEquals(EpubFailure.LIMIT, assertFailsWith<EpubException> { owner.prepare(epubPublication, epubResource, epubLoader(content)) }.failure)
            assertEquals(0, content.maximumRequest); assertTrue(content.closed)
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun foreignResourceOrWrongFormatNeverInvokesLoader() = runBlocking<Unit> {
        val owner = FileEpubPreparer(Files.createTempDirectory("epub-foreign"))
        val loader = object : ResourceLoader { override suspend fun load(resource: PublicationResource): ResourceContent = error("Must not acquire") }
        try {
            for (resource in listOf(epubResource.copy(publicationId = epubId.copy(sourceId = SourceId("foreign"))),
                epubResource.copy(format = PublicationFormat.TEXT), epubResource.copy(mediaType = "application/pdf"))) {
                assertFailsWith<IllegalArgumentException> { owner.prepare(epubPublication, resource, loader) }
            }
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun cancellationDuringAcquisitionClosesContentAndDiscardsBackingFile() = runBlocking<Unit> {
        val base = Files.createTempDirectory("epub-cancel")
        val owner = FileEpubPreparer(base)
        val entered = CompletableDeferred<Unit>()
        val content = EpubBytes(EpubFixture().zip(), beforeRead = { entered.complete(Unit); awaitCancellation() })
        val job = launch { owner.prepare(epubPublication, epubResource, epubLoader(content)) }
        entered.await(); job.cancelAndJoin()
        assertTrue(content.closed); assertTrue(payloads(base).isEmpty())
        owner.close(); owner.awaitClosed()
    }
    @Test fun ownerCloseCancelsActivePreparationAndIsIdempotent() = runBlocking<Unit> {
        val base = Files.createTempDirectory("epub-owner")
        val owner = FileEpubPreparer(base)
        val entered = CompletableDeferred<Unit>()
        val content = EpubBytes(EpubFixture().zip(), beforeRead = { entered.complete(Unit); awaitCancellation() })
        val job = launch { owner.prepare(epubPublication, epubResource, epubLoader(content)) }
        entered.await(); owner.close(); owner.close(); owner.awaitClosed(); job.join()
        assertTrue(content.closed); assertTrue(payloads(base).isEmpty())
    }
    @Test fun sourceReadFailureOrZeroCountCannotPublishAndAlwaysCloses() = runBlocking<Unit> {
        for (zero in listOf(true, false)) {
            val base = Files.createTempDirectory("epub-read-failure")
            val owner = FileEpubPreparer(base)
            var closed = false
            val content = object : ResourceContent {
                override val sizeBytes = 512L
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (zero) return 0
                    throw java.io.IOException("Synthetic failure")
                }
                override fun close() { closed = true }
            }
            try {
                assertFailsWith<EpubException> { owner.prepare(epubPublication, epubResource, epubLoader(content)) }
                assertTrue(closed); assertTrue(payloads(base).isEmpty())
            } finally { owner.close(); owner.awaitClosed() }
        }
    }
    @Test fun finiteDocumentCountBoundsPrivateSessionDiskUsage() = runBlocking<Unit> {
        val base = Files.createTempDirectory("epub-count")
        val owner = FileEpubPreparer(base)
        try {
            val first = owner.prepare(epubPublication, epubResource, epubLoader(EpubBytes(EpubFixture().zip())))
            val second = owner.prepare(epubPublication, epubResource, epubLoader(EpubBytes(EpubFixture().zip())))
            var invoked = false
            val loader = object : ResourceLoader {
                override suspend fun load(resource: PublicationResource): ResourceContent { invoked = true; error("Not allowed") }
            }
            assertEquals(EpubFailure.LIMIT, assertFailsWith<EpubException> { owner.prepare(epubPublication, epubResource, loader) }.failure)
            assertFalse(invoked); assertEquals(2, payloads(base).size)
            first.close(); second.close()
        } finally { owner.close(); owner.awaitClosed() }
        assertTrue(payloads(base).isEmpty())
    }
    @Test fun cancellationDuringXmlValidationPropagates() {
        val job = Job().apply { cancel() }
        assertFailsWith<CancellationException> { parseEpubXml("<root/>".encodeToByteArray(), EpubLimits(), job) }
    }
    @Test fun sourceCloseFailureAfterValidationNeverHandsOffAPreparedDocument() = runBlocking<Unit> {
        val base = Files.createTempDirectory("epub-finally")
        val owner = FileEpubPreparer(base)
        val bytes = EpubBytes(EpubFixture().zip())
        val content = object : ResourceContent by bytes {
            override fun close() { bytes.close(); throw java.io.IOException("Synthetic close failure") }
        }
        try {
            assertFailsWith<EpubException> { owner.prepare(epubPublication, epubResource, epubLoader(content)) }
            assertTrue(bytes.closed)
        } finally { owner.close(); owner.awaitClosed() }
        assertTrue(payloads(base).isEmpty())
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun preparationDeadlineClosesStalledTransfer() = runTest {
        val base = Files.createTempDirectory("epub-timeout")
        val owner = FileEpubPreparer(base, dispatcher = StandardTestDispatcher(testScheduler))
        val content = EpubBytes(EpubFixture().zip(), beforeRead = { awaitCancellation() })
        try {
            assertFailsWith<TimeoutCancellationException> { owner.prepare(epubPublication, epubResource, epubLoader(content)) }
            assertEquals(60_000L, currentTime)
            assertTrue(content.closed); assertTrue(payloads(base).isEmpty())
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun nonCooperativeLateLoaderHandoffStillClosesItsHandle() = runBlocking<Unit> {
        val base = Files.createTempDirectory("epub-late")
        val owner = FileEpubPreparer(base)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val content = EpubBytes(EpubFixture().zip())
        val loader = object : ResourceLoader {
            override suspend fun load(resource: PublicationResource): ResourceContent = withContext(NonCancellable) {
                entered.complete(Unit); release.await(); content
            }
        }
        val task = launch { owner.prepare(epubPublication, epubResource, loader) }
        entered.await(); owner.close(); release.complete(Unit)
        task.join(); owner.awaitClosed()
        assertTrue(content.closed); assertTrue(payloads(base).isEmpty())
    }
    @Test fun closedDocumentInvalidatesOpenHandlesAndRefusesNewOnes() = runBlocking<Unit> {
        valid { doc ->
            val path = doc.manifest.first().path
            val handle = doc.openResource(path)
            doc.close(); doc.close()
            assertFailsWith<IllegalStateException> { handle.read(ByteArray(8)) }
            assertFailsWith<IllegalStateException> { doc.openResource(path) }
            handle.close(); handle.close()
        }
    }
    @Test fun entryHandlesHaveFiniteWorkingBuffersAndHandleCount() = runBlocking<Unit> {
        valid { doc ->
            val handles = List(8) { doc.openResource(doc.manifest.first().path) }
            assertEquals(EpubFailure.LIMIT, assertFailsWith<EpubException> { doc.openResource(doc.manifest.first().path) }.failure)
            assertTrue(handles.first().read(ByteArray(16_384)) <= EPUB_BUFFER_BYTES)
            handles.forEach { it.close() }
            doc.openResource(doc.manifest.first().path).close()
        }
    }
    @Test fun unsupportedStorageNeverPublishesAndStillClosesSource() = runBlocking<Unit> {
        for (directory in listOf(null, Path.of("relative-directory"))) {
            val owner = FileEpubPreparer(directory)
            val content = EpubBytes(EpubFixture().zip())
            try {
                assertEquals(EpubFailure.STORAGE, assertFailsWith<EpubException> { owner.prepare(epubPublication, epubResource, epubLoader(content)) }.failure)
                assertTrue(content.closed)
            } finally { owner.close(); owner.awaitClosed() }
        }
    }
    @Test fun symlinkRootIsRejectedAndTargetContentsAreUntouched() = runBlocking<Unit> {
        val base = Files.createTempDirectory("epub-links")
        val target = Files.createDirectory(base.resolve("target"))
        val marker = Files.writeString(target.resolve("keep"), "private")
        val link = Files.createSymbolicLink(base.resolve("link"), target)
        val owner = FileEpubPreparer(link)
        try {
            assertEquals(EpubFailure.STORAGE, assertFailsWith<EpubException> { owner.prepare(epubPublication, epubResource, epubLoader(EpubBytes(EpubFixture().zip()))) }.failure)
            assertEquals("private", Files.readString(marker))
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun staleOwnedPayloadsCleanedButUnrelatedFilesAndActiveOwnersPreserved() = runBlocking<Unit> {
        val base = Files.createTempDirectory("epub-stale")
        val stale = Files.createDirectory(base.resolve("session-${UUID.randomUUID()}"))
        Files.createFile(stale.resolve(".owner.lock"))
        val payload = Files.write(stale.resolve("epub-${UUID.randomUUID()}.zip"), byteArrayOf(1))
        val marker = Files.writeString(stale.resolve("unrelated"), "keep")
        val first = FileEpubPreparer(base)
        val second = FileEpubPreparer(base)
        try {
            val doc = first.prepare(epubPublication, epubResource, epubLoader(EpubBytes(EpubFixture().zip())))
            assertFalse(Files.exists(payload)); assertTrue(Files.exists(marker))
            second.prepare(epubPublication, epubResource, epubLoader(EpubBytes(EpubFixture().zip()))).close()
            assertTrue(doc.openResource(doc.manifest.first().path).readBytes(4096).isNotEmpty())
        } finally { first.close(); second.close(); first.awaitClosed(); second.awaitClosed() }
        assertTrue(Files.exists(marker)); assertTrue(payloads(base).isEmpty())
    }
    @Test fun platformPathsArePrivateCacheNamespacesWithoutRelativeFallback() {
        assertEquals(Path.of("/private/cache/epub-preparation-v1"), androidEpubDirectory(Path.of("/private/cache")))
        assertNull(androidEpubDirectory(Path.of("relative")))
        assertEquals(Path.of("/home/test/.cache/org.infinilect.app/epub-preparation-v1"), desktopEpubDirectory("Linux", "/home/test", emptyMap()))
        assertNull(desktopEpubDirectory("Linux", "relative", emptyMap()))
    }
    @Test fun productionBoundsCannotBeRaisedByConfiguration() {
        val limits = EpubLimits()
        assertEquals(32L * 1024 * 1024, limits.archiveBytes)
        assertEquals(64L * 1024 * 1024, limits.expandedBytes)
        assertEquals(8192, EPUB_BUFFER_BYTES)
        assertFailsWith<IllegalArgumentException> { EpubLimits(archiveBytes = limits.archiveBytes + 1) }
        assertFailsWith<IllegalArgumentException> { EpubLimits(entries = 513) }
    }
    private fun signature(bytes: ByteArray, value: ByteArray): Int =
        (0..bytes.size - value.size).first { bytes.copyOfRange(it, it + value.size).contentEquals(value) }
    @Test fun concurrentDocumentCleanupAndOwnerCloseRemainIdempotent() = runBlocking<Unit> {
        repeat(64) {
            val base = Files.createTempDirectory("epub-close-race")
            val owner = FileEpubPreparer(base)
            try {
                val doc = owner.prepare(epubPublication, epubResource, epubLoader(EpubBytes(EpubFixture().zip())))
                doc.close() // schedules removal on the IO cleanup scope
                withContext(Dispatchers.Default) { owner.close(); owner.close() }
                owner.awaitClosed()
                assertTrue(payloads(base).isEmpty())
            } finally { owner.close(); owner.awaitClosed(); base.toFile().deleteRecursively() }
        }
    }

}
