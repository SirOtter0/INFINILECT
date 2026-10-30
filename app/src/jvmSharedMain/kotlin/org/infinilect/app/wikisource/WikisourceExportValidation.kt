// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.infinilect.app.epub.BoundedEpubParser
import org.infinilect.app.reader.epub.*
import org.infinilect.core.*

/** Bounded semantic fingerprints bind a reviewed complete work and its credit pages,
 * not the changing ZIP timestamp. No whole chapter/text buffer, URL fetch or opener. */
internal data class WikisourceSpineEvidence(val codePoints:Int,val sha256:String)
internal data class WikisourceExportEvidence(val spine:List<WikisourceSpineEvidence>,val references:Set<String>)
internal suspend fun wikisourceExportEvidence(document:EpubDocument):WikisourceExportEvidence {
    val parser=BoundedEpubParser();val references=linkedSetOf<String>()
    val spine=document.spine.map {item ->
        val path=document.manifest.single {it.id==item.itemId}.path
        val digest=MessageDigest.getInstance("SHA-256");var points=0;var start=0
        do {
            currentCoroutineContext().ensureActive()
            val window=parser.window(document,path,EpubWindowRequest.Block(start))
            check(window.startBlock==start)
            for(block in window.blocks) {
                val text=block.text.encodeToByteArray()
                digest.update(ByteBuffer.allocate(4).putInt(text.size).array());digest.update(text)
                points+=block.codePoints
                for(run in block.runs) run.externalReference?.let {
                    check(run.target==null);references+=it.uri
                    check(references.size<=512)
                }
            }
            check(window.endBlock>start);start=window.endBlock
        } while(start<window.totalBlocks)
        WikisourceSpineEvidence(points,digest.digest().joinToString("") {"%02x".format(it)})
    }
    return WikisourceExportEvidence(spine,references)
}
