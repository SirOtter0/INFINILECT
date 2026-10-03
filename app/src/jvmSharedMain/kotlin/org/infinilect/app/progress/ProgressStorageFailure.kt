// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

/** Internal diagnostics contain no paths, identifiers, content or exception messages. */
internal data class ProgressStorageFailure(
    val operation: Operation,
    val stage: Stage,
    val reason: Reason,
) {
    enum class Operation { GET, SAVE, REMOVE }
    enum class Stage {
        PATH, DIRECTORY_CREATE, DIRECTORY_VALIDATE, DIRECTORY_PERMISSIONS,
        LOCK_OPEN, LOCK_PERMISSIONS, LOCK_ACQUIRE, IDENTITY, RECORD_READ, RECORD_DECODE,
        RECORD_ENCODE, DIRECTORY_SCAN, QUOTA, TEMP_OPEN, TEMP_PERMISSIONS, TEMP_WRITE,
        TEMP_SYNC, COMMIT, TEMP_CLEANUP, REMOVE,
    }
    enum class Reason { UNAVAILABLE, INVALID, BUSY, LIMIT, SECURITY, UNSUPPORTED, IO, OTHER }
}
