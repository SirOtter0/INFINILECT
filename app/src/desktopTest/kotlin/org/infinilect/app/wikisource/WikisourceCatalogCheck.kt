// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import kotlinx.coroutines.runBlocking
import org.infinilect.core.PublicationId

/** Explicit small live metadata sample. Never exports, crawls or runs in test/check. */
object WikisourceCatalogCheck {
    @JvmStatic fun main(args:Array<String>)=runBlocking<Unit> {
        require(args.size>=3) {"Usage: <es|en|ca> <search terms> <page-id> [page-id ...]"}
        require(args.size<=7)
        val edition=WikisourceEdition.entries.single{it.language==args[0]}
        val source=WikisourceSource(edition)
        try {
            val first=source.search(args[1]);println("Wikisource metadata language=${edition.language}; results=${first.publications.size}; continuation=${first.nextPageToken!=null}")
            first.nextPageToken?.let {token->val next=source.search(args[1],token);println("Wikisource metadata page2=${next.publications.size}; duplicateIds=${first.publications.map{it.id}.intersect(next.publications.map{it.id}.toSet()).size}")}
            for(number in args.drop(2)) {
                val id=PublicationId(edition.id,number.toInt().also{require(it>0)}.toString())
                val pub=checkNotNull(source.getPublication(id));val info=source.catalogInfo(id)
                println("Wikisource metadata pageId=${id.localId}; revision=${pub.resources.singleOrNull()?.key}; kind=${info.kind}; epub=${info.epubState}; authorized=${info.authorized}; authors=${pub.authors.size}; url=${pub.sourceUrl}")
            }
        }finally{source.close();source.awaitClosed()}
    }
}
