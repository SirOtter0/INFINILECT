// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.infinilect.app.acquisition.DirectResourceLoader
import org.infinilect.app.discovery.*
import org.infinilect.app.epub.FileEpubPreparer
import org.infinilect.core.PublicationId

/** Explicit bounded sample: no ordinary suite dependency, no binary acquisition
 * for an unverified legal state. Prints public identity and finite outcomes only. */
object WikisourceAvailabilityCheck {
    @JvmStatic fun main(args:Array<String>)=runBlocking<Unit> {
        require(args.size in 2..11)
        val edition=WikisourceEdition.entries.single{it.language==args[0]}
        val root=Files.createTempDirectory("wiki-sample")
        val source=WikisourceSource(edition)
        val preparer=FileEpubPreparer(root,validateDocument=source::validateDocument)
        source.epubInspector{p,r->preparer.prepare(p,r,DirectResourceLoader(source)).close()}
        try {
            for(number in args.drop(1)) {
                val id=PublicationId(edition.id,number.toInt().also{require(it>0)}.toString())
                try {
                    val first=source.catalogInfo(id)
                    val checked=if(first.authorized)source.checkEpub(id) else first
                    val pub=checkNotNull(checked.metadata)
                    println("WIKI_SAMPLE "+buildJsonObject {
                        put("id",number);put("title",pub.title);put("url",pub.sourceUrl);put("resource",pub.resources.firstOrNull()?.key)
                        put("kind",checked.kind.name);put("rights",checked.rightsState.name);put("rule",checked.rightsRule)
                        put("technical",checked.epubState.name);put("authorized",checked.authorized)
                        put("readable",checked.authorized && checked.epubState==CatalogEpubState.VALIDATED)
                    })
                }catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(e:Exception){println("WIKI_SAMPLE_ERROR id=$number class=${e::class.simpleName}")}
            }
        }finally{source.close();source.awaitClosed();preparer.close();preparer.awaitClosed();root.toFile().deleteRecursively()}
    }
}
