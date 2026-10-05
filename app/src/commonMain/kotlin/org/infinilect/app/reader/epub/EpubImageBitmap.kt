// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import androidx.compose.ui.graphics.ImageBitmap

/** UI-only platform conversion. No framework type enters semantic/parser/application contracts. */
internal suspend fun epubImageBitmap(raster: EpubRaster): ImageBitmap = org.infinilect.app.media.rasterImageBitmap(raster)
