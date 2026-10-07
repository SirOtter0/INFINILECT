// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.net.URI
import java.text.Normalizer
import org.infinilect.app.reader.epub.EpubTarget
import org.infinilect.core.*

/** RFC3986 relative path resolution with one strict UTF-8 percent decode per segment.
 * Literal spaces are tolerated from ordinary producers; '+' is never form-decoded.
 * Encoded separators/dot segments/percent, query, authority and schemes fail closed.
 */
internal fun resolveEpubPath(base: EpubEntryPath?, reference: String): EpubEntryPath {
    requireEpub(reference.length in 1..512)
    val uri = try {
        URI(buildString { reference.forEach { if (it == ' ') append("%20") else append(it) } })
    } catch (_: Exception) { invalid() }
    requireEpub(!uri.isAbsolute && !uri.isOpaque && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null)
    val raw = uri.rawPath ?: invalid()
    requireEpub(raw.isNotEmpty() && !raw.startsWith('/'))
    val parts = base?.value?.split('/')?.dropLast(1)?.toMutableList() ?: mutableListOf()
    for (segment in raw.split('/')) {
        val decoded = decodeEpubComponent(segment)
        requireEpub('/' !in decoded && '\\' !in decoded && '%' !in decoded)
        requireEpub('%' !in segment || decoded !in setOf(".", ".."))
        when (decoded) {
            "" -> invalid()
            "." -> Unit
            ".." -> { requireEpub(parts.isNotEmpty()); parts.removeAt(parts.lastIndex) }
            else -> parts.add(decoded)
        }
    }
    val path = parts.joinToString("/")
    requireEpub(Normalizer.isNormalized(path, Normalizer.Form.NFC))
    return try { EpubEntryPath(path) } catch (_: IllegalArgumentException) { invalid() }
}

/** Decode once, refusing malformed UTF-8 and incomplete escapes. Never interprets '+'. */
private fun decodeEpubComponent(raw: String): String = buildString {
    var i = 0
    while (i < raw.length) {
        if (raw[i] != '%') { append(raw[i++]); continue }
        val bytes = java.io.ByteArrayOutputStream()
        while (i < raw.length && raw[i] == '%') {
            requireEpub(i + 2 < raw.length)
            val high = raw[i + 1].digitToIntOrNull(16) ?: invalid()
            val low = raw[i + 2].digitToIntOrNull(16) ?: invalid()
            bytes.write(high * 16 + low); i += 3
        }
        append(try { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { invalid() })
    }
}

internal fun resolveEpubTarget(
    base: EpubEntryPath, reference: String, manifest: List<EpubManifestItem>, spinePaths: Set<EpubEntryPath>? = null,
): EpubTarget {
    requireEpub(reference.length in 1..640 && reference.count { it == '#' } <= 1)
    val file = reference.substringBefore('#')
    val path = if (file.isEmpty()) base else resolveEpubPath(base, file)
    requireEpub(manifest.any { it.path == path } && (spinePaths == null || path in spinePaths))
    val anchor = reference.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(::decodeEpubComponent)
    requireEpub(!reference.contains('#') || anchor != null)
    anchor?.let { requireEpub(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}").matches(it)) }
    return EpubTarget(path, anchor)
}
