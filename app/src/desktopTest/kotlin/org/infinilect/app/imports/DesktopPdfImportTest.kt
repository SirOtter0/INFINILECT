// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import java.nio.file.*
import kotlinx.coroutines.*
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.epub.FileEpubPreparer
import org.infinilect.app.reader.FileTextPreparer
import org.infinilect.app.reader.page.CbzPagePreparer
import org.infinilect.app.reader.pdf.*
import org.infinilect.app.progress.*
import org.infinilect.core.*
import kotlin.test.*

class DesktopPdfImportTest {
    private class Owner(val base:Path) {
        val text=FileTextPreparer(base.resolve("cache/text"))
        val epub=FileEpubPreparer(base.resolve("cache/epub"))
        val pages=CbzPagePreparer(base.resolve("cache/cbz"))
        val pdf=FilePdfPreparer(base.resolve("cache/pdf"))
        val source=FileLocalPublicationSource(base.resolve("files/$IMPORT_DIRECTORY_NAME"),text,epub,pages,pdf=pdf)
        suspend fun close() {
            source.close();source.awaitClosed();text.close();epub.close();pages.close();pdf.close()
            text.awaitClosed();epub.awaitClosed();pages.awaitClosed();pdf.awaitClosed()
        }
    }
    @Test fun realImportDeduplicatesSurvivesOriginalDeletionCacheDeletionAndNewOwners()=runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-import-test")
        val original=Files.write(base.resolve("misleading.txt"),smallPdf(3))
        var owner=Owner(base)
        var writer:ProgressPersistence?=null
        var reader:PdfReaderController?=null
        try {
            val imported=owner.source.import(desktopLocalFileSelection(original))
            assertEquals(PublicationFormat.PDF,imported.resources.single().format)
            assertEquals("application/pdf",imported.resources.single().mediaType)
            assertEquals(imported.id,owner.source.import(desktopLocalFileSelection(original)).id)
            Files.list(base.resolve("files/$IMPORT_DIRECTORY_NAME")).use { s -> assertEquals(1L,s.filter{it.fileName.toString().endsWith(".import")}.count()) }
            Files.delete(original)
            val store=FileReadingProgressStore(base.resolve("progress"))
            writer=ProgressPersistence(store){10L}
            val document=owner.pdf.prepare(imported,imported.resources.single(),DirectResourceLoader(owner.source))
            reader=PdfReaderController(document,this,writer)
            reader.initialize(null);reader.next()
            // Navigation submits immediately; close does not depend on completing page rendering.
            reader.close();reader=null;writer.close();writer.awaitClosed();writer=null
            owner.close()
            base.resolve("cache").toFile().deleteRecursively()
            owner=Owner(base)
            val restored=assertNotNull(owner.source.getPublication(imported.id))
            assertEquals(imported,restored)
            writer=ProgressPersistence(FileReadingProgressStore(base.resolve("progress"))){20L}
            val reopened=owner.pdf.prepare(restored,restored.resources.single(),DirectResourceLoader(owner.source))
            reader=PdfReaderController(reopened,this,writer)
            reader.initialize(writer.get(reopened.progressId))
            assertEquals(1,reader.state.value.index)
            assertIs<PdfFrame.Ready>(reader.state.value.frame)
        } finally {
            reader?.close();writer?.close();writer?.awaitClosed();owner.close();base.toFile().deleteRecursively()
        }
    }
    @Test fun fakePdfCannotPublishAndExistingUtf8RemainsRecognized()=runBlocking<Unit> {
        val base=Files.createTempDirectory("pdf-import-invalid");val owner=Owner(base)
        try {
            for(bytes in listOf("%PDF garbage".encodeToByteArray(),smallPdf().copyOf(90))) {
                val original=Files.write(base.resolve("looks.pdf"),bytes)
                assertEquals(ImportFailure.UNSUPPORTED,assertFailsWith<LocalImportException>{owner.source.import(desktopLocalFileSelection(original))}.failure)
                assertTrue(owner.source.search("*").publications.isEmpty())
            }
            val text=Files.writeString(base.resolve("text.pdf"),"Ordinary UTF-8 text 🦦")
            assertEquals(PublicationFormat.TEXT,owner.source.import(desktopLocalFileSelection(text)).resources.single().format)
        } finally {owner.close();base.toFile().deleteRecursively()}
    }
}
