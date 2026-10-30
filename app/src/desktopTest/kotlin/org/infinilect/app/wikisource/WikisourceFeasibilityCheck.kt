// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.file.*
import kotlinx.coroutines.runBlocking
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
            val document=preparer.prepare(publication,resource,loader)
            try {
                val parser=org.infinilect.app.reader.epub.defaultEpubParser()
                var points=0
                for (item in document.spine) {
                    val path=document.manifest.single {it.id==item.itemId}.path
                    points+=parser.chapter(document,path).codePoints
                }
                check(points>1000) {"No substantial readable text"}
                println("EPUB accepted by production preparer/parser; chapters=${document.spine.size}; readableCodePoints=$points")
            }
            finally {document.close()}
        } finally {preparer.close();preparer.awaitClosed();root.toFile().deleteRecursively()}
    }
}
