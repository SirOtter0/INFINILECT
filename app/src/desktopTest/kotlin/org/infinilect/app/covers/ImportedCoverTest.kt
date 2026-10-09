// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.covers
import java.nio.file.Files
import kotlinx.coroutines.*
import org.infinilect.app.epub.*
import org.infinilect.app.imports.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.page.CbzPagePreparer
import org.infinilect.app.page.developmentCbzBytes
import org.infinilect.core.*
import kotlin.test.*

class ImportedCoverTest {
    private suspend fun owner(action:suspend(FileLocalPublicationSource,java.nio.file.Path)->Unit) {
        val root=Files.createTempDirectory("owned-cover-test")
        val text=FileTextPreparer(root.resolve("cache/text"));val epub=FileEpubPreparer(root.resolve("cache/epub"));val pages=CbzPagePreparer(root.resolve("cache/cbz"))
        val source=FileLocalPublicationSource(root.resolve("files/$IMPORT_DIRECTORY_NAME"),text,epub,pages)
        try { action(source,root) } finally { source.close();source.awaitClosed();text.close();epub.close();pages.close();text.awaitClosed();epub.awaitClosed();pages.awaitClosed();root.toFile().deleteRecursively() }
    }
    private suspend fun imported(source:FileLocalPublicationSource, bytes:ByteArray)=source.import(LocalFileSelection("original.fixture") {EpubBytes(bytes)})
    @Test fun declaredEpubCoverUsesExistingPrivatePayloadWithoutPreparationCopy()=runBlocking<Unit> {
        owner {source,root ->
            val f=EpubFixture();f.entries["OPS/art.png"]=developmentPng();f.opf {it.replace("</manifest>","<item id=\"art\" href=\"art.png\" media-type=\"image/png\" properties=\"cover-image\"/></manifest>")}
            val book=imported(source,f.zip());val before=Files.walk(root).use{it.filter { path -> Files.isRegularFile(path) }.toList()}
            val cover=assertNotNull(source.cover(book.id));assertEquals(PublicationFormat.EPUB,cover.format);assertEquals(96,assertNotNull(cover.raster).width)
            assertEquals(before,Files.walk(root).use{it.filter { path -> Files.isRegularFile(path) }.toList()})
        }
    }
    @Test fun cbzCoverUsesFirstPageAndTextUsesKnownFormatFallback()=runBlocking<Unit> {
        owner {source,_ ->
            val comic=imported(source,developmentCbzBytes());val cover=assertNotNull(source.cover(comic.id))
            assertEquals(PublicationFormat.CBZ,cover.format);assertNotNull(cover.raster)
            val text=imported(source,"Original text".encodeToByteArray());val fallback=assertNotNull(source.cover(text.id))
            assertEquals(PublicationFormat.TEXT,fallback.format);assertNull(fallback.raster)
            assertNull(source.cover(text.id.copy(sourceId=SourceId("different"))))
        }
    }
    @Test fun mutatedPayloadCannotReuseDigestIdentityAndCloseRejectsWork()=runBlocking<Unit> {
        owner {source,root ->
            val text=imported(source,"Original text".encodeToByteArray())
            val payload=root.resolve("files/$IMPORT_DIRECTORY_NAME/${text.id.localId}.import/payload")
            Files.write(payload,"Tampered text".encodeToByteArray());assertFails {source.cover(text.id)}
            source.close();source.awaitClosed();assertFails {source.cover(text.id)}
        }
    }
}
