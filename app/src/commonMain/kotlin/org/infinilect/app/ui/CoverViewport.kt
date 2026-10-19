// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.UnplacedAwareModifierNode
import kotlinx.coroutines.flow.MutableStateFlow

/** Geometry changes only emit when visibility changes; no per-pixel Compose state.
 * Root AND ancestor clips matter for nested carousels, sidebars and bottom navigation.
 * Does not retain coordinates, publications, bitmaps or a coroutine scope. */
internal class CoverViewport {
    val visible = MutableStateFlow(false)
    val modifier: Modifier = ViewportElement(this)
}

private data class ViewportElement(val viewport: CoverViewport) : ModifierNodeElement<ViewportNode>() {
    override fun create() = ViewportNode(viewport)
    override fun update(node: ViewportNode) {
        if (node.viewport !== viewport) { node.viewport.visible.value = false; node.viewport = viewport }
    }
}

private class ViewportNode(var viewport: CoverViewport) : Modifier.Node(),
    GlobalPositionAwareModifierNode, UnplacedAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val root = coordinates.findRootCoordinates()
        val bounds = root.localBoundingBoxOf(coordinates, clipBounds = true)
        // Only intersection with the root is needed, not its translated window coordinates.
        // Avoid four localToWindow transforms and the additional window Rect on each callback.
        viewport.visible.value = !bounds.isEmpty && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < root.size.width && bounds.top < root.size.height
    }
    override fun onUnplaced() { viewport.visible.value = false }
    override fun onDetach() { viewport.visible.value = false }
    override fun onReset() { viewport.visible.value = false }
}
