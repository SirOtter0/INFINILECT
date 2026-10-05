// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.media

import androidx.compose.ui.graphics.ImageBitmap

/** UI conversion only. Framework types never enter documents/controllers/parser models. */
internal expect suspend fun rasterImageBitmap(raster: Raster): ImageBitmap
