// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import org.infinilect.core.*

/** Display metadata is separate from resource authority and durable publication identity. */
internal data class DiscoveryEntry(val publication: Publication, val subjects: List<String> = emptyList(), val description: String? = null, val thumbnail: String? = null) {
    val genres = subjects.flatMap(::mappedGenres).toSet()
}
internal data class DiscoveryPage(val entries: List<DiscoveryEntry>, val nextToken: String? = null)
internal enum class Genre(val label: String, val archiveSubject: String) {
    SCIENCE_FICTION("Science fiction", "Science fiction"), FANTASY("Fantasy", "Fantasy"),
    MYSTERY("Mystery", "Detective and mystery stories"), HISTORY("History", "History"),
    PHILOSOPHY("Philosophy", "Philosophy"), ADVENTURE("Adventure", "Adventure stories"),
    ROMANCE("Romance", "Love stories");
    /** Exact, source-supplied subject aliases, not inferred work categories. */
    val archiveSubjects = listOf(archiveSubject, label).distinctBy { it.lowercase() }
}
/** Exact subject segments/aliases only; never infer a genre from title or author. */
internal fun mappedGenres(subject: String): Set<Genre> = subject.split("--").map { it.trim().lowercase() }.flatMap { segment ->
    Genre.entries.filter { genre -> genre.archiveSubjects.any { segment == it.lowercase() } }
}.toSet()
internal fun normalizedQuery(raw: String): String {
    require(raw.length <= 1024 && raw.none { it.code < 32 && !it.isWhitespace() || it.code in 127..159 })
    return raw.trim().split(Regex("\\s+")).joinToString(" ").also { require(it.length <= 256) }
}
internal data class DiscoveryRequest(val query: String, val genre: Genre? = null, val language: String? = null) {
    init { require(query == normalizedQuery(query)); require(language == null || language in DISCOVERY_LANGUAGES.keys) }
}
internal val DISCOVERY_LANGUAGES = linkedMapOf("en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese")
internal interface DiscoverySource {
    val canBrowseGenres: Boolean get() = false
    suspend fun discover(request: DiscoveryRequest, token: String? = null): DiscoveryPage
}
internal data class DiscoveryPreferences(val homeEnabled: Boolean = false, val personalized: Boolean = true,
    val interests: Set<Genre> = emptySet(), val inferenceAfter: Long = 0) {
    init { require(inferenceAfter >= 0) }
}
internal object DiscoveryStrings {
    const val SEARCH = "Search"
    const val EXPANDED = "Expanded"
    const val COLLAPSED = "Collapsed"
    const val SOURCES_FILTERS = "Sources & filters"
    const val CLEAR_GENRE = "Clear genre"
    const val CLEAR_SEARCH = "Clear search"
    const val FILTERS = "Filters"
    const val SOURCE_SECTION = "Sources"
    const val LANGUAGE_SECTION = "Language"
    const val SUBJECT_SECTION = "Subject · Internet Archive"
    const val RESET_FILTERS = "Reset filters"
    const val DONE = "Done"
    const val SEARCH_HELP = "About catalog search"
    const val LANGUAGE_NOTICE = "Languages filter returned metadata. Missing language is excluded."
    const val ALL_LANGUAGES = "All languages"
    const val GENRE_NOTICE = "Subjects search Internet Archive's limited CC0 text catalog, not the whole Archive. Exact subject aliases are used. Languages filter loaded pages; more pages may contain matches."
    const val NO_SOURCES = "Select at least one source. Subject browsing currently requires Internet Archive."
    const val QUERY_ERROR = "Use at most 256 characters and no control characters."
    const val SEARCH_INTRO = "Search across catalogs, or explore a subject above. Nothing is downloaded by browsing."
    const val NO_GENRE_MATCHES = "No matches in the loaded CC0 catalog pages. Load more, change the language, or try another subject."
    const val NO_MATCHES = "No matches on loaded pages. Load more or try another query, source or language."
    const val PAGE_LIMIT = "Showing up to 100 results per source. Refine your search for more."
    const val CATALOG_ONLY = "Catalog metadata only. Acquisition is unavailable for this source."
    const val REMOVE_FROM_LIBRARY = "Remove from Library?"
    const val REMOVAL_NOTICE = "Only Library membership is removed. Source files, History and reading positions are kept."
    const val REMOVE = "Remove"
    const val CANCEL = "Cancel"
    const val DISCOVERY = "Discovery"
    const val SHOW_ONLINE_DISCOVERY_ON_HOME = "Show online discovery on Home"
    const val PERSONALIZE_WITH_LOCAL_INTERESTS = "Personalize with local interests"
    const val RECOMMENDATION_NOTICE = "Recommendations use declared subjects and selected interests, never guessed genres. Library items are excluded."
    const val RESET_RECOMMENDATION_PREFERENCES = "Reset recommendation preferences"
    const val COUNTRY_NOTICE = "Country is a declared residence preference, not verified legal eligibility. It is not sent to these catalogs."
    const val TITLE = "Search & discover"
    const val SEARCH_LABEL = "Title, author, or keyword"
    const val RIGHTS_UNKNOWN = "No source rights statement was supplied. Legal availability is undetermined."
    const val RIGHTS = "Catalog presence is not permission to download or proof of rights in your country. Opening always rechecks the owning source."
    const val PRIVACY = "Catalog requests send public query/filter parameters and advertised thumbnail URLs. Library, History, reading positions, name and residence country are not sent."
    const val COVER_NOTE = "Advertised Gutenberg thumbnails are optional. Other or unavailable artwork uses a local title cover."
    const val UNAVAILABLE = "This catalog could not respond. Your local publications remain available."
    const val GENERAL = "Explore the catalogs"
    const val RECOMMENDED = "Recommended for you"
    const val RETRY = "Retry"
    const val VIEW_SEARCH = "View Search"
    const val VIEW_ALL = "View all"
    const val PERSONALIZED_EXPLANATION = "Based on selected interests and known subjects in local reading activity."
    const val GENERAL_EXPLANATION = "General catalog discovery; not personalized."
    const val HOME_UNAVAILABLE = "Online discovery unavailable"
    const val HOME_RETRY_EXPLANATION = "Local reading is ready. Retry catalogs when connected."
    const val RETRY_DISCOVERY = "Retry discovery"
    const val OPT_IN_TITLE = "Explore beyond your Library"
    const val OPT_IN_EXPLANATION = "Online catalogs are optional. Your local Home works offline."
    const val OPT_IN_ACTION = "Explore online catalogs"
    fun sourceUnavailable(name: String) = "$name could not respond."
    fun retrySource(name: String) = "Retry $name"
    fun moreFrom(name: String) = "More from $name"
    fun selectedSources(count: Int) = "$count sources selected"
}
/** Display-only limits; no delivery links or identifiers are invented. */
internal fun boundedEntry(entry: DiscoveryEntry) = entry.copy(publication = entry.publication.copy(
    title = entry.publication.title.take(2048), authors = entry.publication.authors.take(8).map { it.take(256) },
    languages = entry.publication.languages.take(8).map { it.take(64) }, rights = entry.publication.rights?.take(16_384)),
    subjects = entry.subjects.take(16).map { it.take(256) }, description = entry.description?.take(4096)?.takeIf { it.isNotBlank() })

/** Inert, bounded text projection. No HTML engine, links, external entities or remote resources. */
internal fun catalogPlainText(raw: String): String? {
    val text = raw.take(16_384).replace(Regex("<[^>]{0,2048}>"), " ")
        .replace(Regex("&(?:amp|lt|gt|quot|apos|nbsp);")) { token -> when(token.value) {
            "&amp;" -> "&"; "&lt;" -> "<"; "&gt;" -> ">"; "&quot;" -> "\""; "&apos;" -> "'"; else -> " "
        } }
        .replace(Regex("[ \\t]+"), " ").replace(Regex("""\n{3,}"""), "\n\n").trim().take(4096)
    return text.takeIf { it.isNotBlank() }
}

/** Five explicit BCP47/ISO639 aliases, not a guessed language. */
internal fun languageMatches(tag: String, selected: String): Boolean {
    val base = tag.lowercase().substringBefore('-').substringBefore('_')
    return base in when (selected) { "en" -> setOf("en", "eng"); "es" -> setOf("es", "spa");
        "fr" -> setOf("fr", "fra", "fre"); "de" -> setOf("de", "deu", "ger"); "pt" -> setOf("pt", "por"); else -> emptySet() }
}
