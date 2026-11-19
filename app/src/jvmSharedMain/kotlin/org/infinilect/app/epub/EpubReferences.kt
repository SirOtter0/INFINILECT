// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import java.net.URI
import java.text.Normalizer
import org.infinilect.app.reader.epub.EpubTarget
import org.infinilect.app.reader.epub.EpubExternalReference
import org.infinilect.core.*

/** No DNS, IO, redirects or opener. Only XHTML a[href] calls this classifier.
 * Only HTTPS destinations are retained, as non-actionable text references.
 * They are never retrieved. Relative paths return null
 * and still undergo the existing owned-path validation, not URI/browser resolution.
 */
internal fun epubExternalReference(reference: String): EpubExternalReference? {
    requireEpub(reference.length in 1..512)
    if (!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(reference)) return null
    requireEpub(reference.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
    val uri = try { URI(reference) } catch (_: Exception) { invalid() }
    val scheme = uri.scheme?.lowercase()
    requireEpub(scheme == "https" && !uri.isOpaque && uri.rawUserInfo == null)
    val host = uri.host?.lowercase() ?: invalid()
    requireEpub(uri.rawAuthority == host || uri.rawAuthority?.lowercase() == host ||
        uri.rawAuthority?.lowercase() == "$host:443")
    requireEpub(uri.port == -1 || uri.port == 443)
    val labels = host.split('.')
    requireEpub(host.length <= 253 && labels.size >= 2 && labels.all {
        it.length in 1..63 && Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?").matches(it)
    } && Regex("[a-z]{2,63}").matches(labels.last()))
    requireEpub(labels.last() !in setOf("localhost", "local", "internal", "lan", "home", "test", "invalid", "onion"))
    requireEpub(host != "localhost" && !host.endsWith(".localhost"))
    // Preserve the original spelling while rejecting encoded control/backslash and
    // invalid UTF-8 escapes. No decoded value is ever used as a filesystem path.
    val decoded = decodeEpubComponent(reference)
    requireEpub(decoded.none { it.isISOControl() || it == '\\' })
    return EpubExternalReference(reference)
}

/** Safe recovery of legacy numeric XHTML IDs; not OPF/NCX identifier validation. */
internal fun validEpubContentAnchor(value: String): Boolean =
    Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}").matches(value)

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
    anchor?.let { requireEpub(validEpubContentAnchor(it)) }
    return EpubTarget(path, anchor)
}
