// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import org.infinilect.app.generated.resources.*
import org.jetbrains.compose.resources.PluralStringResource

internal enum class UiPlural(val resource: PluralStringResource, val english: List<String>) {
    PUBLICATIONS(Res.plurals.publication_count, listOf("%1\$s publication", "%1\$s publications")),
    SELECTED(Res.plurals.selected_count, listOf("%1\$s selected", "%1\$s selected")),
    SOURCES(Res.plurals.source_count, listOf("%1\$s source selected", "%1\$s sources selected")),
}

private val argument = Regex("%([1-9][0-9]*)\\\$s")
/** Replace parameters in the template once. User text containing %2$s remains literal data. */
internal fun substituteArguments(template: String, arguments: List<String>): String = argument.replace(template) {
    arguments.getOrNull(it.groupValues[1].toInt() - 1) ?: error("Missing interface format argument")
}
internal expect fun formatInterfaceNumber(value: Double, language: String, fractionalDigits: Int): String

// These are presentation adapters for existing app-owned English status contracts only.
// They must never receive publication text, rights statements, titles, queries or provider metadata.
private val statusKeys = UiText.entries.associateBy { it.fallback.replace('’', '\'') }
private data class StatusTemplate(val key: UiText, val pattern: Regex)
private val statusTemplates by lazy { UiText.entries.filter { argument.containsMatchIn(it.fallback) }.sortedByDescending { it.fallback.length }
    .map { key ->
        val template = key.fallback.replace('’', '\'')
        var cursor = 0
        val pattern = buildString {
            append("^")
            argument.findAll(template).forEach { match ->
                append(Regex.escape(template.substring(cursor, match.range.first)))
                append("([\\s\\S]*?)")
                cursor = match.range.last + 1
            }
            append(Regex.escape(template.substring(cursor))); append("$")
        }
        StatusTemplate(key, Regex(pattern))
    }

}

internal fun statusKey(value: String): UiText? = statusKeys[value.replace('’', '\'')]
    ?: statusTemplates.firstOrNull { it.pattern.matches(value.replace('’', '\'')) }?.key
internal fun LocalizedStrings.status(value: String): String {
    val normalized = value.replace('’', '\'')
    statusKeys[normalized]?.let { return this[it] }
    for (template in statusTemplates) {
        val result = template.pattern.matchEntire(value) ?: continue
        return format(template.key, *result.groupValues.drop(1).toTypedArray())
    }
    // Unknown provider exceptions are not safe user-facing messages. Coverage tests reject
    // missing mappings for our own status contracts instead of accepting this fallback.
    return this[UiText.UI_THE_ACTION_COULD_NOT_COMPLETE_PLEASE_TRY_AGAIN]
}
internal fun LocalizedStrings.sourceName(name: String): String =
    when(name) {"Imported files" -> this[UiText.UI_IMPORTED_FILES]; "Wikisource (es)" -> this[UiText.WIKISOURCE_SOURCE_LABEL]; else -> name}
