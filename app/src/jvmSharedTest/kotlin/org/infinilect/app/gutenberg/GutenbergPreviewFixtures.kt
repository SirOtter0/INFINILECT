// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.gutenberg

import kotlinx.serialization.json.*

// Tiny authored fixtures derived from the observed 2026-10-04 service schema.
internal const val previewRoot = """{"metadata":{"title":"Project Gutenberg"},"links":[{"rel":"self","href":"https://opds-test.pglaf.org/opds/","type":"application/opds+json"},{"rel":"search","href":"https://opds-test.pglaf.org/opds/search{?query,title,author}","type":"application/opds+json","templated":true}],"groups":[{"metadata":{"title":"Navigation"},"navigation":[]}]}"""
internal fun previewPublication(id: String="84", title: String="A Book 🦦", extra: String=""): String =
    """{"metadata":{"@type":"http://schema.org/Book","identifier":"https://www.gutenberg.org/ebooks/$id","title":"$title","language":["en","es"],"author":[{"name":"Author, One"},{"name":"Autor 二"}]$extra},"links":[{"rel":"self","href":"https://opds-test.pglaf.org/opds/publications?id=$id","type":"application/opds-publication+json"},{"rel":"http://opds-spec.org/acquisition/open-access","href":"https://www.gutenberg.org/cache/epub/$id/pg$id-images-3.epub","type":"application/epub+zip","length":12345}]}"""
internal fun previewPage(query: String="books", page: Int=1, publications: String=previewPublication(), next: Boolean=false): String {
    fun url(number: Int)=io.ktor.http.URLBuilder("${GUTENBERG_ROOT}search").apply {
        parameters.append("limit","25");parameters.append("query",query);parameters.append("page",number.toString())
    }.buildString()
    val first=JsonPrimitive(url(page))
    val following=JsonPrimitive(url(page+1))
    return """{"metadata":{"numberOfItems":${if(next)100 else if(publications.isEmpty())0 else page*25},"itemsPerPage":25,"currentPage":$page},"links":[{"rel":"self","href":$first,"type":"application/opds+json"}${if(next) ",{\"rel\":\"next\",\"href\":$following,\"type\":\"application/opds+json\"}" else ""}],"publications":[$publications]}"""
}
internal fun previewBytes(value: String)=value.toByteArray(Charsets.UTF_8)
