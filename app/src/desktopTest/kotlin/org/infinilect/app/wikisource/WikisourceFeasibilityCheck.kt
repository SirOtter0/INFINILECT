// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.infinilect.app.reader.epub.*
import org.infinilect.app.epub.FileEpubPreparer
import org.infinilect.core.*

/** Local, opt-in validation of one officially exported file; no network or book logging. */
object WikisourceFeasibilityCheck {
    @JvmStatic fun main(args: Array<String>)=runBlocking {
        require(args.size==1)
        val file=Path.of(args.single());require(Files.size(file) in 1..16L*1024*1024)
        val root=Files.createTempDirectory("ws-feasibility")
        val id=PublicationId(SourceId("wikisource-feasibility"),"es:1425")
        val resource=PublicationResource(id,"official-export",PublicationFormat.EPUB,"application/epub+zip")
        val publication=Publication(id,"Official export verification",PublicationType.BOOK,resources=listOf(resource))
        val preparer=FileEpubPreparer(root)
        val loader=object:ResourceLoader {
            override suspend fun load(resource:PublicationResource):ResourceContent {
                val input=Files.newInputStream(file)
                return object:ResourceContent {
                    override val sizeBytes=Files.size(file)
                    override suspend fun read(buffer:ByteArray,offset:Int,length:Int)=input.read(buffer,offset,length)
                    override fun close()=input.close()
                }
            }
        }
        try {
            println("Wikisource validation phase=prepare")
            val document=preparer.prepare(publication,resource,loader)
            try {
                check(document.metadata.languages == listOf("es"))
                check(document.metadata.rights == listOf(
                    EpubRightsStatement("Creative Commons BY-SA 3.0", "en"),
                    EpubRightsStatement("GNU Free Documentation License", "en"),
                )) {"Official export rights metadata changed; review required"}
                println("Wikisource validation phase=chapters; rightsStatements=${document.metadata.rights.size}; spine=${document.spine.size}")
                val parser=org.infinilect.app.reader.epub.defaultEpubParser()
                check(document.metadata.title == "El Matadero")
                check(document.metadata.creators == listOf("Esteban Echeverría"))
                var points=0
                val references=mutableSetOf<String>()
                for (item in document.spine) {
                    val path=document.manifest.single {it.id==item.itemId}.path
                    val chapter=parser.chapter(document,path)
                    points+=chapter.codePoints
                    references+=chapter.blocks.flatMap {it.runs}.mapNotNull {it.externalReference?.uri}
                    check(chapter.blocks.flatMap {it.runs}.filter {it.externalReference!=null}.all {it.target==null})
                }
                check(points>1000) {"No substantial readable text"}
                check(references.size>=4) {"Attribution references were lost"}
                val evidence=wikisourceExportEvidence(document)
                check(evidence.references==references)
                println("Wikisource semantic evidence="+evidence.spine.joinToString {"${it.codePoints}:${it.sha256}"})
                val readerScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
                val reader=EpubReaderController(document,ReadingProgressId(id,resource.key,PublicationFormat.EPUB),readerScope)
                try {
                    reader.initialize(null)
                    for(index in document.spine.indices.toList()+document.spine.indices.reversed().toList()) {
                        reader.chapter(index)
                        val state=withTimeout(10_000) {reader.state.first {it is EpubReaderState.Error || it is EpubReaderState.Ready && (it.spineIndex==index || it.error!=null)}}
                        check(state is EpubReaderState.Ready && state.spineIndex==index && state.error==null) {"Reader navigation failed"}
                        reader.presented(state.ticket)
                    }
                    check(reader.toc.isNotEmpty())
                } finally {reader.close();readerScope.cancel()}
                println("EPUB accepted by production preparer/parser/reader; chapters=${document.spine.size}; readableCodePoints=$points; passiveReferences=${references.size}; navigation=forward-and-back")
            }
            finally {document.close()}
        } finally {
            preparer.close();preparer.awaitClosed()
            try {
                Files.walk(root).use {paths->check(paths.noneMatch {it.fileName.toString().endsWith(".zip")}) {"Prepared file cleanup incomplete"}}
                println("Wikisource validation cleanup=complete")
            } finally {root.toFile().deleteRecursively()}
        }
    }
}
