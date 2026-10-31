// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.wikisource

/** A bounded declaration reader, NOT a MediaWiki evaluator. Unknown syntax never
 * grants rights. Comments/nowiki examples cannot become license declarations. */
internal data class WikiTemplate(val name:String,val arguments:List<String>,val parent:String?,val raw:String) {
    fun field(name:String)=arguments.mapNotNull {part ->
        val split=part.indexOf('=')
        if(split>=0 && part.substring(0,split).trim().equals(name,true))part.substring(split+1).trim() else null
    }.singleOrNull()
}
internal fun wikiDeclarations(input:String):List<WikiTemplate> {
    if(input.length>524288)throw WikisourceInvalid()
    // Monotonic scanning also bounds malformed inputs with many unterminated
    // comments/tags; do not repeatedly regex-scan the remaining document.
    val visible=StringBuilder(input.length);var cursor=0
    while(cursor<input.length) {
        if(input.startsWith("<!--",cursor)) {
            val end=input.indexOf("-->",cursor+4)
            if(end<0)throw WikisourceInvalid()
            visible.append(' ');cursor=end+3;continue
        }
        val ignored=if(input[cursor]=='<')listOf("nowiki","pre","syntaxhighlight","source").firstOrNull {tag->
            input.regionMatches(cursor+1,tag,0,tag.length,true) &&
                input.getOrNull(cursor+1+tag.length)?.let {it.isWhitespace() || it=='/' || it=='>'}==true
        } else null
        if(ignored!=null) {
            val openEnd=input.indexOf('>',cursor+1)
            if(openEnd<0 || openEnd-cursor>2048)throw WikisourceInvalid()
            if(input[openEnd-1]=='/')cursor=openEnd+1 else {
                val end=input.indexOf("</$ignored",openEnd+1,ignoreCase=true)
                if(end<0)throw WikisourceInvalid()
                val close=input.indexOf('>',end)
                if(close<0 || close-end>64 || input.substring(end+ignored.length+2,close).isNotBlank())throw WikisourceInvalid()
                cursor=close+1
            }
            visible.append(' ')
        }else visible.append(input[cursor++])
    }
    val text=visible.toString()
    data class Frame(val start:Int,val parent:String?,val pieces:MutableList<String>,var from:Int)
    val stack=mutableListOf<Frame>();val result=mutableListOf<WikiTemplate>();var links=0;var i=0
    while(i<text.length) {
        when {
            text.startsWith("{{{",i) -> throw WikisourceInvalid() // parameter evaluation is deliberately unsupported
            text.startsWith("{{",i) -> {
                if(stack.size>=24)throw WikisourceInvalid()
                val parent=stack.lastOrNull()?.let {f->(f.pieces.firstOrNull() ?: text.substring(f.from,i)).trim().lowercase()}
                stack+=Frame(i,parent,mutableListOf(),i+2);i+=2
            }
            text.startsWith("}}",i) -> {
                val f=stack.removeLastOrNull() ?: throw WikisourceInvalid()
                f.pieces+=text.substring(f.from,i)
                if(result.size>=2048)throw WikisourceInvalid()
                result+=WikiTemplate(f.pieces.first().trim().replace('_',' ').lowercase(),f.pieces.drop(1),f.parent,text.substring(f.start,i+2))
                i+=2
            }
            text.startsWith("[[",i) -> {links++;i+=2}
            text.startsWith("]]",i) -> {links=(links-1).coerceAtLeast(0);i+=2}
            text[i]=='|' && links==0 && stack.isNotEmpty() -> {
                val f=stack.last();f.pieces+=text.substring(f.from,i);f.from=i+1;i++
            }
            else -> i++
        }
    }
    if(stack.isNotEmpty())throw WikisourceInvalid()
    return result
}
internal fun wikiLabel(raw:String?):String? {
    if(raw==null || raw.length>2048 || "{{" in raw || '<' in raw || '>' in raw)return null
    val text=raw.replace(Regex("\\[\\[([^\\[\\]]+)]]")) {m->m.groupValues[1].substringAfterLast('|').substringAfter(':')}
        .replace("''","").trim()
    return text.takeIf {it.isNotBlank() && it.length<=512 && it.none(Char::isISOControl)}
}
internal fun wikiAuthors(text:String):List<String> {
    val header=wikiDeclarations(text).singleOrNull {it.parent==null && it.name in setOf("header","encabezado","encapçalament")}
    return listOfNotNull(wikiLabel(header?.field("author") ?: header?.field("autor")))
}
