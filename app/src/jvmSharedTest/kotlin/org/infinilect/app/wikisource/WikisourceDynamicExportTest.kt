// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.infinilect.app.epub.*
import org.infinilect.app.reader.*
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*
import kotlin.test.*

/** Original EPUBs with arbitrary text, rights, attribution URLs and spine shapes. */
class WikisourceDynamicExportTest {
    private suspend fun withBook(edition:WikisourceEdition=WikisourceEdition.EN,title:String="Different work",uri:String=edition.canonical(title),count:Int=1,
        mutate:(EpubFixture)->Unit={},body:suspend(EpubDocument,Publication,PublicationResource)->Unit) {
        val root=Files.createTempDirectory("wiki-dynamic")
        val id=PublicationId(edition.id,"1425");val resource=PublicationResource(id,"epub3-policy1-r1393104",PublicationFormat.EPUB,"application/epub+zip")
        val pub=Publication(id,title,PublicationType.DOCUMENT,resources=listOf(resource),languages=listOf(edition.language),sourceUrl=edition.canonical(title))
        val fixture=EpubFixture().apply {
            opf{it.replace("urn:synthetic:foundation",uri.replace("&","&amp;"))
                .replace("Résumé 📚",title).replace("<dc:language>fr</dc:language><dc:language>en</dc:language>","<dc:language>${edition.language}</dc:language>")
                .replace("<dc:rights>Original test fixture</dc:rights>","<dc:rights>Original statement A</dc:rights><dc:rights>Different statement B</dc:rights>")
                .replace("</manifest>",(2..count).joinToString(""){n->"<item id=\"c$n\" href=\"c$n.xhtml\" media-type=\"application/xhtml+xml\"/>"}+"</manifest>")
                .replace("</spine>",(2..count).joinToString(""){n->"<itemref idref=\"c$n\"/>"}+"</spine>")}
            for(n in 2..count)entries["OPS/c$n.xhtml"]="<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter $n</title></head><body><p>Different original chapter $n.</p></body></html>".encodeToByteArray()
            change("OPS/chapter.xhtml"){it.replace("</body>","<p><a href=\"https://creativecommons.org/licenses/by/4.0/\">Original attribution</a></p></body>")}
        }
        mutate(fixture)
        val bytes=fixture.zip()
        val preparer=FileEpubPreparer(root,validateDocument={doc,res,snapshot->validateWikisourceExport(doc,res,snapshot,edition)})
        val loader=object:ResourceLoader{override suspend fun load(resource:PublicationResource)=EpubBytes(bytes)}
        try {
            val doc=preparer.prepare(pub,resource,loader)
            try {body(doc,pub,resource)}finally{doc.close()}
        }finally{preparer.close();preparer.awaitClosed();assertTrue(payloads(root).isEmpty());root.toFile().deleteRecursively()}
    }
    @Test fun oneOrManyChaptersAndUnregisteredAttributionsReadInEveryEdition()=runBlocking<Unit> {
        for(edition in WikisourceEdition.entries)for(count in listOf(1,7))withBook(edition,count=count){doc,_,_->
            assertEquals(count,doc.spine.size)
            assertEquals(listOf("Original statement A","Different statement B"),doc.metadata.rights.map{it.text})
            val evidence=wikisourceExportEvidence(doc);assertTrue(evidence.spine.all{it.codePoints>0});assertTrue(evidence.references.contains("https://creativecommons.org/licenses/by/4.0/"))
        }
    }
    @Test fun localIllustrationsAndInternalNotesRemainOwnedReaderResources()=runBlocking<Unit> {
        withBook(mutate={f->
            f.entries["OPS/illustration.png"]=developmentPng()
            f.opf{it.replace("</manifest>","<item id=\"illustration\" href=\"illustration.png\" media-type=\"image/png\"/></manifest>")}
            f.change("OPS/chapter.xhtml"){it.replace("</body>","<p><a href=\"#note\">Original note reference</a></p><p id=\"note\">Original note.</p><img src=\"illustration.png\" alt=\"Original illustration\"/></body>")}
        }){doc,_,_->
            val chapter=defaultEpubParser().chapter(doc,doc.manifest.single{it.id==doc.spine.first().itemId}.path)
            assertEquals(1,chapter.blocks.count{it.image!=null})
            assertTrue(chapter.blocks.flatMap{it.runs}.any{it.target!=null})
            assertTrue(chapter.blocks.any{it.text=="Original note."})
        }
    }
    @Test fun legacyCaseAliasKeepsTheSamePublicationResourceAndProgressIdentity()=runBlocking<Unit> {
        withBook(WikisourceEdition.ES,"El Matadero","https://es.wikisource.org/wiki/El_matadero"){doc,pub,resource->
            assertEquals(PublicationId(SourceId("wikisource-es"),"1425"),pub.id)
            assertEquals("epub3-policy1-r1393104",resource.key)
            assertEquals(ReadingProgressId(pub.id,resource.key,PublicationFormat.EPUB),ReadingProgressId(doc.publicationId,resource.key,PublicationFormat.EPUB))
        }
    }
    @Test fun officialIdentifierBindsEditionAndPathWhileAllowingBibliographicTitleVariants()=runBlocking<Unit> {
        withBook(title="Different work (1813)",mutate={it.opf{xml->xml.replace("<dc:title>Different work (1813)</dc:title>","<dc:title>Different work</dc:title>")}}){_,_,_->}
        for(uri in listOf("https://en.wikisource.org/wiki/Other_work","https://es.wikisource.org/wiki/Different_work","https://en.wikisource.org@localhost/wiki/Different_work","file:///private"))
            assertEquals(EpubFailure.INVALID,assertFailsWith<EpubException>{withBook(uri=uri){_,_,_->}}.failure)
    }
    @Test fun epub2WithDifferentRightsAndNavigationUsesTheSameDynamicBoundary()=runBlocking<Unit> {
        withBook(mutate={f->
            f.entries.remove("OPS/nav.xhtml")
            f.opf{it.replace("version=\"3.0\"","version=\"2.0\"")
                .replace(Regex("<meta property=\"dcterms:modified\">.*?</meta>"),"")
                .replace("href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"","href=\"Nav/toc.ncx\" media-type=\"application/x-dtbncx+xml\"")
                .replace("<spine>","<spine toc=\"nav\">")}
            f.entries["OPS/Nav/toc.ncx"]=ncx(point("one","Original chapter","../chapter.xhtml#start")).encodeToByteArray()
        }){doc,_,_->assertEquals(1,doc.spine.size);assertEquals(2,doc.metadata.rights.size)}
    }
    @Test fun remoteResourcesScriptsEntitiesAndUnsafePathsRemainRejected()=runBlocking<Unit> {
        for(content in listOf("<img src=\"https://example.org/remote.png\"/>","<script>active()</script>","<iframe src=\"file:///private\"/>","<img src=\"../outside.png\"/>"))
            assertEquals(EpubFailure.INVALID,assertFailsWith<EpubException>{withBook(mutate={f->f.change("OPS/chapter.xhtml"){it.replace("</body>","$content</body>")}}){_,_,_->}}.failure)
    }
}
