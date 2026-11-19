// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.reader.*
import org.infinilect.core.*
import kotlin.test.*

class EpubMetadataLinksTest {
    private suspend fun checkLink(markup:String,accepted:Boolean) {
        val root=Files.createTempDirectory("epub-metadata-link")
        val bytes=EpubFixture().apply{change("OPS/chapter.xhtml"){it.replace("</body>","$markup</body>")}}.zip()
        val content=EpubBytes(bytes);var loads=0
        val loader=object:ResourceLoader {override suspend fun load(resource:PublicationResource):ResourceContent {
            assertEquals(epubResource,resource);loads++;return content
        }}
        val preparer=FileEpubPreparer(root)
        try {
            if(accepted) {
                val document=preparer.prepare(epubPublication,epubResource,loader)
                try {
                    assertContentEquals(bytes,Files.readAllBytes(payloads(root).single()))
                    val chapter=org.infinilect.app.reader.epub.defaultEpubParser().chapter(document,document.manifest.single{it.id==document.spine.single().itemId}.path)
                    assertEquals("Hello, 世界 📖",chapter.blocks.single().text)
                    assertTrue(chapter.blocks.flatMap{it.runs}.all{it.externalReference==null})
                }finally{document.close()}
            }else assertEquals(EpubFailure.INVALID,assertFailsWith<EpubException>{preparer.prepare(epubPublication,epubResource,loader)}.failure)
            assertEquals(1,loads) // No metadata target, stylesheet or URL can become a loader call.
        }finally{preparer.close();preparer.awaitClosed();assertTrue(content.closed);assertTrue(payloads(root).isEmpty());root.toFile().deleteRecursively()}
    }
    @Test fun qualifiedMetadataRelationsArePassiveAcrossPublishersAndPreserveOriginalBytes()=runBlocking<Unit> {
        for(rel in listOf("mw:PageProp/Language","schema:inLanguage","dc:relation"))checkLink("<link rel=\"$rel\" href=\"https://example.org/attribution\"/>",true)
    }
    @Test fun activeAndMixedLinkTypesRemainResourcesAndCannotFetchRemoteContent()=runBlocking<Unit> {
        for(rel in listOf("stylesheet","preload","prefetch","modulepreload","mw:PageProp/Language stylesheet","stylesheet dc:relation","dc:relation preload"))
            checkLink("<link rel=\"$rel\" href=\"https://example.org/style.css\"/>",false)
        checkLink("<link rel=\"dc:relation\" as=\"script\" href=\"https://example.org/script.js\"/>",false)
        checkLink("<link rel=\"dc:relation\" type=\"text/css\" href=\"https://example.org/style.css\"/>",false)
        checkLink("<img rel=\"dc:relation\" src=\"https://example.org/image.png\"/>",false)
    }
    @Test fun qualifiedMetadataCannotBypassHttpsUriOrOwnedPathRules()=runBlocking<Unit> {
        for(uri in listOf("javascript:active()","data:text/plain,unsafe","file:///private","content://private","http://example.org/metadata","//example.org/metadata","https://127.0.0.1/metadata","https://example.org@localhost/private","../outside"))
            checkLink("<link rel=\"dc:relation\" href=\"$uri\"/>",false)
    }
}
