// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.core

/** Canonical, case-sensitive ZIP-internal path, never a URL or filesystem path.
 * Literal spaces/UTF-8 names are preserved; URI decoding belongs to the package adapter.
 */
data class EpubEntryPath(val value: String) {
    init {
        require(value.length in 1..512 && value.split('/').size <= 32)
        require(value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._~-/ ()!$&'+,=@[]" ||
            it >= '\u00a0' && it !in '\ue000'..'\uf8ff' && it !in '\ufdd0'..'\ufdef' && it < '\ufff0' && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069' && it != '\ufeff' })
        require(value.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) == value)
        var index = 0
        while (index < value.length) {
            val first = value[index++].code
            val point = if (first in 0xd800..0xdbff) 0x10000 + ((first - 0xd800) shl 10) + (value[index++].code - 0xdc00) else first
            require(point < 0xf0000 && (point and 0xffff) < 0xfffe)
        }
        require(value.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    }
}

data class EpubMetadata(
    val identifier: String,
    val title: String,
    val languages: List<String>,
    val modified: String?,
    val creators: List<String> = emptyList(),
    val rights: String? = null,
)

data class EpubManifestItem(
    val id: String,
    val path: EpubEntryPath,
    val mediaType: String,
    val properties: Set<String> = emptySet(),
)

data class EpubSpineItem(val itemId: String, val linear: Boolean = true)

/** Prepared structural EPUB2/EPUB3, NOT a renderer or a promise that content is safe to execute.
 * Metadata/paths come from bounded validation. No source, engine, ZIP or platform types.
 * Future renderers must impose their own no-script/no-remote/content sandbox policy.
 */
interface EpubDocument {
    val publicationId: PublicationId
    val packagePath: EpubEntryPath
    val metadata: EpubMetadata
    val manifest: List<EpubManifestItem>
    val spine: List<EpubSpineItem>
    val navigationItemId: String

    /** Only manifest-owned paths; a fresh sequential handle, locally backed, no network.
     * Caller owns close. Closing this document invalidates and closes all its handles.
     */
    suspend fun openResource(path: EpubEntryPath): ResourceContent

    /** Idempotently invalidates the document; platform cleanup may finish asynchronously. */
    fun close()
}
