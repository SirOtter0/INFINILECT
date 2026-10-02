// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import java.time.Instant
import kotlinx.coroutines.runBlocking

object OapenAlternateAccessCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.isEmpty())
        println("OAPEN alternate check at ${Instant.now()}")
        OapenAlternateProbe().use { probe ->
            try {
                val evidence = probe.inspect()
                println("GET $OAPEN_OAI_RECORD HTTP ${evidence.metadataStatus}; consumed=${evidence.metadataBytes} bytes")
                evidence.pdf?.let { pdf ->
                    println("PDF announced size=${pdf.size}; rights=${pdf.rights}; license=${pdf.licenseUrl}")
                    println("HEAD ${pdf.url} HTTP ${evidence.headStatus}; consumed=0 bytes; redirect=${evidence.redirect}")
                }
                println("Requests=${if (evidence.headStatus == null) 1 else 2}; no retries; no file body consumed.")
                println("Metadata accessibility does not establish successful acquisition. No graphical UI was run.")
            } finally {
                println("Attempted requests=${probe.attemptedRequests}; metadata status=${probe.lastMetadataStatus}; consumed=${probe.consumedMetadataBytes} bytes; no retries.")
            }
        }
    }
}
