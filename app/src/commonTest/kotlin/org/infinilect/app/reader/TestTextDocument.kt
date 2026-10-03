// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader

import org.infinilect.core.*

/** Pure, small fixture only. Production has no whole-document String implementation. */
internal fun TextDocument(id: PublicationId, title: String, text: String, progressId: ReadingProgressId? = null): TextDocument =
    TextDocument(id, title, object : TextWindows {
        private val window = TextWindow(0, 0, text)
        override val codePoints = window.locations.codePoints
        override val count = 1
        override fun start(index: Int): Int { require(index == 0); return 0 }
        override suspend fun read(index: Int): TextWindow { require(index == 0); return window }
        override fun close() {}
    }, progressId)
