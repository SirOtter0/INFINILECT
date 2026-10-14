// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal const val TRANSLATION_MODEL_DISK_BYTES = 120L * 1024 * 1024
internal const val TRANSLATION_MODEL_FILES = 128

/** Fixed two-model namespace, including downloads. Never follows links or scans without a bound. */
internal fun translationModelStorageFits(root: Path, reserve: Long = 0): Boolean {
    if (reserve !in 0..TRANSLATION_MODEL_DISK_BYTES) return false
    if (!Files.exists(root, NOFOLLOW_LINKS)) return true
    if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return false
    return try { Files.walk(root, 4).use { stream ->
        var count = 0; var bytes = 0L
        for (entry in stream.iterator()) {
            if (++count > TRANSLATION_MODEL_FILES || Files.isSymbolicLink(entry)) return@use false
            if (Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                bytes += Files.size(entry)
                if (bytes + reserve > TRANSLATION_MODEL_DISK_BYTES) return@use false
            }
        }
        true
    } } catch (_: Exception) { false }
}
