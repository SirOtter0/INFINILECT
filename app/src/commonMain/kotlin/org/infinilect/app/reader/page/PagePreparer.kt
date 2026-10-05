// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import org.infinilect.core.*
import org.infinilect.app.media.RasterPolicy

internal object PagePolicy {
    const val MAX_PAGES = 512
    const val RETAINED = 3 // current / first-visible page and at most two adjacent pages
    const val PREFETCH = 1
    const val DECODE_TIMEOUT_MILLIS = 10_000L
    fun supported(page: PageEntry): Boolean = page.resource.mediaType in setOf("image/png", "image/jpeg") &&
        RasterPolicy.dimensions(page.dimensions.width, page.dimensions.height) &&
        page.dimensions.width.toDouble() / page.dimensions.height in 0.125..8.0
    /** Stable layout even for unsupported metadata: loading/error never changes reserved height. */
    fun aspectRatio(page: PageEntry): Float = (page.dimensions.width.toFloat() / page.dimensions.height).coerceIn(0.125f, 8f)
}
internal interface PagePreparer {
    suspend fun prepare(publication: Publication, loader: ResourceLoader): PageDocument
    fun close() = Unit
    suspend fun awaitClosed() = Unit
}
internal expect fun defaultPagePreparer(): PagePreparer
