// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.cache

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.infinilect.core.ResourceCacheKey

internal const val MAX_CACHE_IDENTITY_BYTES = 32 * 1024

/** Versioned, length-prefixed UTF-8 fields; never a source-provided filename or URL. */
internal fun ResourceCacheKey.encodeIdentity(): ByteArray {
    val fields = listOf(publicationId.sourceId.value, publicationId.localId, resourceKey,
        format.name, mediaType, revision)
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { stream ->
        stream.writeInt(1)
        for (field in fields) {
            require(field.length <= MAX_CACHE_IDENTITY_BYTES)
            val bytes = field.encodeToByteArray()
            require(bytes.size <= MAX_CACHE_IDENTITY_BYTES - output.size() - 4)
            stream.writeInt(bytes.size)
            stream.write(bytes)
        }
    }
    return output.toByteArray()
}

internal fun cacheDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
