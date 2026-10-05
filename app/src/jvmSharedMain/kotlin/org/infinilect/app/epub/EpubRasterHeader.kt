// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.epub

/** EPUB keeps its historical helper/policy; the hardened preflight is format-neutral. */
internal fun epubRasterDimensions(bytes: ByteArray, type: String, job: kotlinx.coroutines.Job? = null): Pair<Int, Int> =
    org.infinilect.app.media.rasterDimensions(bytes, type, job)
