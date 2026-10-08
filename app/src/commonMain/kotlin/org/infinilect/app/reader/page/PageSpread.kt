// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import org.infinilect.core.PageEntry

/** Presentation identities only. The first logical page is the durable anchor. */
internal data class PageSpread(val anchor: Int, val second: Int? = null) {
    val indices: List<Int> get() = if (second == null) listOf(anchor) else listOf(anchor, second)
    fun visualOrder(mode: PageReadingMode) = if (mode == PageReadingMode.PAGED_RTL) indices.reversed() else indices
    fun indicator(count: Int) = if (second == null) "${anchor + 1} / $count" else "${anchor + 1}–${second + 1} / $count"
}

/** Sequential grouping; square pages pair too. Wide pages never consume a partner.
 * Cover-alone is fixed policy, not a heuristic or another saved preference. */
internal fun pageSpreads(pages: List<PageEntry>, settings: PageReaderSettings): List<PageSpread> {
    if (settings.layout == PageLayout.SINGLE || !pagedMode(settings.mode)) return pages.indices.map { PageSpread(it) }
    fun wide(index: Int) = pages[index].dimensions.width > pages[index].dimensions.height
    return buildList {
        var index = 0
        while (index < pages.size) {
            val pair = index != 0 && !wide(index) && index + 1 < pages.size && !wide(index + 1)
            add(PageSpread(index, if (pair) index + 1 else null))
            index += if (pair) 2 else 1
        }
    }
}
