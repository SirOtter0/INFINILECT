// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

/** Closed vocabulary: no publication ID, query, URL, profile, path or arbitrary message. */
internal enum class TechnicalEvent {
    SESSION, HOME_VISIBLE, HOME_DISPOSED, HOME_LOADING, HOME_PAUSED,
    GENERAL_STARTED, GENRE_STARTED, REQUEST_COMPLETE, REQUEST_CANCELLED, REQUEST_FAILED,
    STALE_RESPONSE, HOME_COMPLETE, NAV_HOME, NAV_LIBRARY, NAV_HISTORY, NAV_SEARCH, NAV_SETTINGS,
    COVER_STARTED, COVER_READY, COVER_FALLBACK, COVER_FAILED, COVER_CANCELLED, COVER_RELEASED, COVERS_CLOSED,
    BACKGROUND, FOREGROUND,
    DOWNLOAD_STARTED, DOWNLOAD_RESOLVING, DOWNLOAD_OPENING, DOWNLOAD_TRANSFERRING,
    DOWNLOAD_VALIDATING, DOWNLOAD_COMMITTING, DOWNLOAD_HTTP_ERROR,
    DOWNLOAD_UNSUPPORTED_FORMAT, DOWNLOAD_RIGHTS_UNVERIFIED, DOWNLOAD_STORAGE_ERROR,
    DOWNLOAD_COMPLETED, DOWNLOAD_FAILED, DOWNLOAD_CANCELLED, DOWNLOAD_CAUSE,
}
internal fun interface TechnicalDiagnostics {
    fun record(event: TechnicalEvent, generation: Long, count: Int)
}
internal fun TechnicalDiagnostics?.event(event: TechnicalEvent, generation: Long = 0, count: Int = 0) {
    this?.record(event, generation, count)
}
