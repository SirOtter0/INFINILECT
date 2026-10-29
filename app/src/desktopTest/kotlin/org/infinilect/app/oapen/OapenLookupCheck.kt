// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import kotlinx.coroutines.runBlocking
import org.infinilect.core.PublicationId

/** One opt-in official metadata lookup. No full-text search, redirects or file download. */
object OapenLookupCheck {
    @JvmStatic fun main(args: Array<String>)=runBlocking {
        require(args.isEmpty())
        OapenSource().use {source ->
            val result=checkNotNull(source.bibliography(PublicationId(OAPEN_SOURCE_ID,"20.500.12657/25287")))
            check(result.publication.resources.isEmpty())
            check(result.publisher!=null && result.description!=null && result.identifiers.isNotEmpty())
            println("OAPEN official OAI GetRecord: metadata parsed; publisher/description/identifiers present; announced format count=${result.announcedFormats.size}; acquisition disabled; no file requests.")
        }
    }
}
