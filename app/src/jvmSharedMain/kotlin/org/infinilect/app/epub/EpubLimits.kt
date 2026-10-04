// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

import org.infinilect.app.reader.EpubException
import org.infinilect.app.reader.EpubFailure
import org.infinilect.core.EpubEntryPath

/** Finite production ceilings
tests may only lower them. */
internal data class EpubLimits(
val archiveBytes: Long = 32L * 1024 * 1024, val expandedBytes: Long = 64L * 1024 * 1024, val entryBytes: Long = 8L * 1024 * 1024, val entries: Int = 512, val ratio: Int = 100, val xmlBytes: Int = 1024 * 1024, val manifest: Int = 256, val spine: Int = 128, ) {
    init {
        require(archiveBytes in 1..32L*1024*1024 && expandedBytes in 1..64L*1024*1024)
        require(entryBytes in 1..8L*1024*1024 && entries in 1..512 && ratio in 1..100)
        require(xmlBytes in 1..1024*1024 && manifest in 1..256 && spine in 1..128)
    }
}
internal const val EPUB_BUFFER_BYTES = 8192
internal const val EPUB_DIRECTORY_NAME = "epub-preparation-v1"
internal fun invalid(): Nothing = throw EpubException(EpubFailure.INVALID)
internal fun limit(): Nothing = throw EpubException(EpubFailure.LIMIT)
internal fun requireEpub(value: Boolean) {
    if (!value) invalid()
}

/** URI references may normalize safe relative dots
ZIP names themselves cannot contain them.
* Never accepts a scheme, encoded alias, absolute path, backslash, query or fragment.
*/
internal fun resolveEpubPath(base: EpubEntryPath?, reference: String): EpubEntryPath {
    requireEpub(reference.isNotEmpty() && reference.length <= 512 && !reference.startsWith('/'))
    requireEpub(reference.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._~-/"
    }
    )
    val parts = base?.value?.split('/')?.dropLast(1)?.toMutableList() ?: mutableListOf()
    for (segment in reference.split('/')) when (segment) {
        "" -> invalid()
        "." -> Unit
        ".." -> {
            requireEpub(parts.isNotEmpty())
            parts.removeAt(parts.lastIndex)
        }
        else -> parts.add(segment)
    }
    return try {
        EpubEntryPath(parts.joinToString("/"))
    }
    catch (_: IllegalArgumentException) {
        invalid()
    }
}
