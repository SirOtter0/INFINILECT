// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import kotlinx.serialization.json.*
import org.infinilect.app.discovery.CatalogPageKind

/** Positive, source-declared evidence, separate from EPUB validity and from a
 * jurisdiction guarantee. This is a deliberately small POLICY, not a title list.
 * Unknown templates, examples and generic site footers never authorize a work. */
internal class WikisourceRights(private val bytes:suspend(String)->ByteArray) {
    suspend fun assess(page:WikiPage):WikiGrant {
        if(page.namespace!=0 || page.kind in setOf(CatalogPageKind.AUTHOR,CatalogPageKind.INDEX,CatalogPageKind.VERSIONS,CatalogPageKind.DISAMBIGUATION))return WikiGrant()
        val text=page.wikitext
        if(text.length>524288 || Regex("<!--|<nowiki|<pre|<syntaxhighlight|<source|</?(?:includeonly|noinclude|onlyinclude|ref)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text))return WikiGrant()
        val declarations=try {wikiDeclarations(text)}catch(_:WikisourceInvalid){return WikiGrant()}
        if(declarations.any {blocked(it.name)})return WikiGrant()
        // PD-old on English Wikisource explicitly declares publication expiry
        // and >=100 years since the author's death. No parameters/alternate scope.
        val direct=declarations.singleOrNull {it.parent==null && it.name=="pd-old" && it.arguments.isEmpty()}
        if(page.edition==WikisourceEdition.EN && direct!=null &&
            declarations.none {it.name.startsWith("pd-") && it!==direct} &&
            Regex("(?m)^\\s*\\{\\{PD-old}}\\s*$",RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            declarations.none {t->t.arguments.any {arg->arg.substringBefore('=').trim().lowercase() in setOf("translator","override_translator","translation","edition") && arg.substringAfter('=',"").isNotBlank()}}) {
            return WikiGrant(true,listOf(direct.raw,revisionUrl(page),"https://en.wikisource.org/wiki/Template:PD-old",
                "https://${page.edition.host}/wiki/Wikisource:Copyright_policy"))
        }
        // A single proofread-page slice can be tied to one scan. Do not infer
        // rights for a TOC's descendants, arbitrary transclusions or embedded art.
        if(declarations.any {it.parent==null && it.name !in setOf("listaref","reflist","smallrefs")})return WikiGrant()
        if(Regex("\\[\\[\\s*/|\\{\\{\\s*:").containsMatchIn(text))return WikiGrant()
        val pages=Regex("<pages\\b([^<>]{1,2048})/\\s*>",RegexOption.IGNORE_CASE).findAll(text).toList()
        if(pages.size!=1 || Regex("<pages\\b",RegexOption.IGNORE_CASE).findAll(text).count()!=1)return WikiGrant()
        val attrs=pages.single().groupValues[1]
        val indexes=Regex("(?:^|\\s)index\\s*=\\s*\"([^\"<>]{1,512})\"",RegexOption.IGNORE_CASE).findAll(attrs).toList()
        if(indexes.size!=1)return WikiGrant()
        val scan=wikiTitle(indexes.single().groupValues[1])
        if(scan.any {it.isISOControl() || it in "/\\:#?[]{}|%"} || !Regex(".+\\.(djvu|pdf|jpg|png)",RegexOption.IGNORE_CASE).matches(scan))return WikiGrant()
        // After removing the slice, accept only category/interlanguage links and
        // reference-list markup. Prose/other pages need their own rights evidence.
        val remaining=text.removeRange(pages.single().range)
            .replace(Regex("\\[\\[[^\\[\\]]{1,2048}]]"),"")
            .let {s->declarations.fold(s){out,t->out.replace(t.raw,"")}}.trim()
        if(remaining.isNotEmpty())return WikiGrant()
        val indexPrefix=when(page.edition){WikisourceEdition.ES->"Índice";WikisourceEdition.EN->"Index";WikisourceEdition.CA->"Llibre"}
        val index=metadata(page.edition.api,"$indexPrefix:$scan",page.edition.indexNamespace) ?: return WikiGrant()
        val indexText=wikiContent(index,setOf("wikitext","proofread-index"))
        if(wikiDeclarations(indexText).none {it.parent==null && it.name==":mediawiki:proofreadpage index template" || it.parent==null && it.name==":mediawiki:proofreadpage_index_template"})return WikiGrant()
        val file=metadata(WIKISOURCE_COMMONS_API,"File:$scan",6) ?: return WikiGrant()
        val fileText=wikiContent(file)
        if(Regex("<!--|<nowiki|<pre|<syntaxhighlight",RegexOption.IGNORE_CASE).containsMatchIn(fileText))return WikiGrant()
        val licenses=try {wikiDeclarations(fileText)}catch(_:WikisourceInvalid){return WikiGrant()}
        if(licenses.any {blocked(it.name)})return WikiGrant()
        val scanLicense=licenses.singleOrNull {it.name=="pd-scan" && (it.parent==null || it.parent=="book")} ?: return WikiGrant()
        if(scanLicense.parent=="book") {
            val book=licenses.singleOrNull {it.parent==null && it.name=="book"} ?: return WikiGrant()
            if(!book.field("permission").orEmpty().trim().equals(scanLicense.raw.trim(),true))return WikiGrant()
        }
        if(licenses.any {it.name.startsWith("pd-") && it!==scanLicense})return WikiGrant()
        val args=scanLicense.arguments.map(String::trim)
        // No death-year calculation from the device clock, and no inference
        // from a weaker/ambiguous automatic-expiry declaration.
        if(args.size!=1 || !args[0].equals("PD-old-100-expired",true))return WikiGrant()
        val fileId=file.wikiInt("pageid");val fileRevision=file.wikiInt("lastrevid")
        val indexId=index.wikiInt("pageid");val indexRevision=index.wikiInt("lastrevid")
        return WikiGrant(true,listOf(scanLicense.raw,revisionUrl(page),
            "https://${page.edition.host}/w/index.php?curid=$indexId&oldid=$indexRevision",
            "https://commons.wikimedia.org/w/index.php?curid=$fileId&oldid=$fileRevision"))
    }
    private fun blocked(name:String)=listOf("copyvio","copyright violation","no permission","no license","nobots","delete","speedy","in use","copyrighted","fair use","pd-us","pd-old-70","pd-old-80","pd-old-90","cc-by-nc","cc-by-nd").any {name==it || name.startsWith("$it/") || name.startsWith("$it-")}
    private fun revisionUrl(page:WikiPage)="https://${page.edition.host}/w/index.php?curid=${page.pageId}&oldid=${page.revision}"
    private suspend fun metadata(api:String,title:String,namespace:Int):JsonObject? {
        val rows=wikiPages(wikiJson(bytes(wikiBase(api)+"&titles=${wikiParameter(title)}&prop=info%7Crevisions&rvprop=ids%7Ccontent&rvslots=main&rvlimit=1")))
        if(rows.size!=1)throw WikisourceInvalid()
        val row=rows.single() as? JsonObject ?: throw WikisourceInvalid()
        if(row["missing"]!=null || row["invalid"]!=null)return null
        if(row.wikiInt("ns")!=namespace || wikiTitle(row.wikiText("title",512))!=wikiTitle(title))return null
        if(row.wikiInt("pageid")<=0 || row.wikiInt("lastrevid")<=0)throw WikisourceInvalid()
        val revision=((row["revisions"] as? JsonArray)?.singleOrNull() as? JsonObject)?.wikiInt("revid") ?: throw WikisourceInvalid()
        if(revision!=row.wikiInt("lastrevid"))throw WikisourceInvalid()
        return row
    }
}
