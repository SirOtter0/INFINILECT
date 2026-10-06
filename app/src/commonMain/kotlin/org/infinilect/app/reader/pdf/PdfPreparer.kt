// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.pdf

import org.infinilect.core.*

internal interface PdfPreparer {
    suspend fun prepare(publication: Publication, resource: PublicationResource, loader: ResourceLoader): PdfDocument
    fun close()
    suspend fun awaitClosed()
}
