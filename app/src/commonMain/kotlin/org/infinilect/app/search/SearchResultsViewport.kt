// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.search

/** Retain one viewport per search session; replace it once for each successful new page.
 * Loading/errors keep the old viewport. The owner must outlive the search screen so
 * reader Back and unrelated recompositions retain the current page's position.
 */
internal class SearchResultsViewport<T>(private val createPosition: () -> T) {
    private var generation: Long? = null
    private var position = createPosition()

    fun forState(state: SearchState): T {
        val result = when (state) {
            is SearchState.Results -> state.result
            is SearchState.Empty -> state.result
            else -> return position
        }
        if (generation != null && generation != result.generation) position = createPosition()
        generation = result.generation
        return position
    }
}
