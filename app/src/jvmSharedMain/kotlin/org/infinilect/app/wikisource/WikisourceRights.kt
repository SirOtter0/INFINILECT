// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

import kotlinx.serialization.json.*
import org.infinilect.app.discovery.CatalogPageKind
import org.infinilect.app.discovery.CatalogRightsState

/** Positive, source-declared evidence, separate from EPUB validity and from a
 * jurisdiction guarantee. This is a deliberately small POLICY, not a title list.
 * Unknown templates, examples and generic site footers never authorize a work. */
internal class WikisourceRights(private val bytes:suspend(String)->ByteArray) {
    suspend fun assess(page:WikiPage):WikiGrant {
        if(page.namespace!=0 || page.kind in setOf(CatalogPageKind.AUTHOR,CatalogPageKind.INDEX,CatalogPageKind.VERSIONS,CatalogPageKind.DISAMBIGUATION))return WikiGrant()
        val text=try {wikiEvidenceText(page.wikitext)}catch(_:WikisourceInvalid){return WikiGrant()}
        /* Legacy blanket marker rejection removed: ignored examples are excluded
         * from the evidence view rather than rejecting unrelated real notes. */
        val declarations=try {wikiDeclarations(text)}catch(_:WikisourceInvalid){return WikiGrant()}
        // A quoted declaration cannot grant permission, but rendered footnotes
        // and quotations can still introduce third-party artwork/transclusions.
        val scopeText=try {wikiEvidenceText(page.wikitext,includeCitations=true)}catch(_:WikisourceInvalid){return WikiGrant()}
        val scopeDeclarations=try {wikiDeclarations(scopeText,includeCitations=true)}catch(_:WikisourceInvalid){return WikiGrant()}
        if(declarations.any {blocked(it.name)} || Regex("all rights reserved|todos los derechos reservados|tots els drets reservats",RegexOption.IGNORE_CASE).containsMatchIn(text))return WikiGrant(state=CatalogRightsState.RESTRICTED)
        val unresolved=WikiGrant(state=if(declarations.any {it.name.startsWith("dp") || it.name.startsWith("pd-") || it.name.startsWith("cc-")})CatalogRightsState.INSUFFICIENT else CatalogRightsState.UNKNOWN)
        // Applied, work-scoped declarations. A statement on an author, a site
        // footer or a cited example is not inherited by this work.
        val rules=when(page.edition) {
            WikisourceEdition.ES->mapOf("dp-100" to "es.work.dp100.v1")
            WikisourceEdition.CA->mapOf("dp-100" to "ca.work.dp100.v1")
            WikisourceEdition.EN->mapOf("pd-old" to "en.work.pdold.v1","pd-release" to "en.work.release.v1")
        }
        val direct=declarations.singleOrNull {it.parent==null && it.name in rules && it.arguments.isEmpty()}
        val translation=scopeDeclarations.any {t->t.arguments.any {arg->arg.substringBefore('=').trim().lowercase() in
            setOf("translator","override_translator","translation","edition","traductor","traductora","traducción","traducció","ilustrador","illustrator","override_illustrator") && arg.substringAfter('=',"").isNotBlank()}} || Regex("traducci[oó]n|traducido por|traducci[oó]|tradu[iï]t per|translated by|translation by",RegexOption.IGNORE_CASE).containsMatchIn(scopeText)
        val safeTemplates=setOf("header","encabezado","encapçalament","pagename","listaref","reflist","smallrefs","c","center","centre","center block/s","center block/e","auxtoc","ppb")+rules.keys
        val unknown=scopeDeclarations.any {t->t.name !in safeTemplates && !t.name.startsWith("defaultsort:") &&
            !(page.edition==WikisourceEdition.EN && t.name=="wp link" && t.parent=="header" && t.arguments.size==1 &&
                t.arguments.single().let {it.length in 1..512 && it.none {c->c.isISOControl() || c in "[]{}<>|="}})}
        val images=Regex("<\\s*(?:img|svg|image)\\b|\\[\\[\\s*:?(?:File|Image|Archivo|Imagen|Fitxer|Imatge):",RegexOption.IGNORE_CASE).containsMatchIn(scopeText)
        val children=Regex("\\[\\[\\s*/").containsMatchIn(scopeText)
        if(direct!=null && !translation && !unknown && !images && !children && (!Regex("<pages\\b",RegexOption.IGNORE_CASE).containsMatchIn(text) || page.edition==WikisourceEdition.EN && direct.name=="pd-old") &&
            Regex("(?im)^\\s*"+Regex.escape(direct.raw)+"\\s*$").containsMatchIn(text)) {
            val template=when(page.edition){WikisourceEdition.EN->"Template:"+if(direct.name=="pd-old")"PD-old" else "PD-release";else->"Plantilla:DP-100"}
            return WikiGrant(true,listOf(direct.raw,revisionUrl(page),page.edition.canonical(template)),rule=rules.getValue(direct.name))
        }
        // A single proofread-page slice can be tied to one scan. Do not infer
        // rights for a TOC's descendants, arbitrary transclusions or embedded art.
        if(declarations.any {it.parent==null && it.name !in setOf("listaref","reflist","smallrefs")})return unresolved
        if(Regex("\\[\\[\\s*/|\\{\\{\\s*:").containsMatchIn(text))return unresolved
        val pages=Regex("<pages\\b([^<>]{1,2048})/\\s*>",RegexOption.IGNORE_CASE).findAll(text).toList()
        if(pages.size!=1 || Regex("<pages\\b",RegexOption.IGNORE_CASE).findAll(text).count()!=1)return unresolved
        val attrs=pages.single().groupValues[1]
        val indexes=Regex("(?:^|\\s)index\\s*=\\s*\"([^\"<>]{1,512})\"",RegexOption.IGNORE_CASE).findAll(attrs).toList()
        if(indexes.size!=1)return unresolved
        val scan=wikiTitle(indexes.single().groupValues[1])
        if(scan.any {it.isISOControl() || it in "/\\:#?[]{}|%"} || !Regex(".+\\.(djvu|pdf|jpg|png)",RegexOption.IGNORE_CASE).matches(scan))return unresolved
        // After removing the slice, accept only category/interlanguage links and
        // reference-list markup. Prose/other pages need their own rights evidence.
        val remaining=text.removeRange(pages.single().range)
            .replace(Regex("\\[\\[[^\\[\\]]{1,2048}]]"),"")
            .let {s->declarations.fold(s){out,t->out.replace(t.raw,"")}}.trim()
        if(remaining.isNotEmpty())return unresolved
        val indexPrefix=when(page.edition){WikisourceEdition.ES->"Índice";WikisourceEdition.EN->"Index";WikisourceEdition.CA->"Llibre"}
        val index=metadata(page.edition.api,"$indexPrefix:$scan",page.edition.indexNamespace) ?: return unresolved
        val indexText=wikiContent(index,setOf("wikitext","proofread-index"))
        if(wikiDeclarations(indexText).none {it.parent==null && it.name==":mediawiki:proofreadpage index template" || it.parent==null && it.name==":mediawiki:proofreadpage_index_template"})return unresolved
        val file=metadata(WIKISOURCE_COMMONS_API,"File:$scan",6) ?: return unresolved
        val fileText=wikiEvidenceText(wikiContent(file))
        val licenses=try {wikiDeclarations(fileText)}catch(_:WikisourceInvalid){return unresolved}
        if(licenses.any {blocked(it.name)})return unresolved
        val scanLicense=licenses.singleOrNull {it.name=="pd-scan" && (it.parent==null || it.parent=="book")} ?: return unresolved
        if(scanLicense.parent=="book") {
            val book=licenses.singleOrNull {it.parent==null && it.name=="book"} ?: return unresolved
            if(!book.field("permission").orEmpty().trim().equals(scanLicense.raw.trim(),true))return unresolved
        }
        if(licenses.any {it.name.startsWith("pd-") && it!==scanLicense})return unresolved
        val args=scanLicense.arguments.map(String::trim)
        // No death-year calculation from the device clock, and no inference
        // from a weaker/ambiguous automatic-expiry declaration.
        if(args.size!=1 || !args[0].equals("PD-old-100-expired",true))return unresolved
        val fileId=file.wikiInt("pageid");val fileRevision=file.wikiInt("lastrevid")
        val indexId=index.wikiInt("pageid");val indexRevision=index.wikiInt("lastrevid")
        return WikiGrant(true,listOf(scanLicense.raw,revisionUrl(page),
            "https://${page.edition.host}/w/index.php?curid=$indexId&oldid=$indexRevision",
            "https://commons.wikimedia.org/w/index.php?curid=$fileId&oldid=$fileRevision"),rule="commons.scan.pd100.v1")
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
