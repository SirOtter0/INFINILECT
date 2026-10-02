// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.archive

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.infinilect.app.acquisition.*
import org.infinilect.core.PublicationFormat

/** An explicit CLI demonstration through the production contracts; never runs during build. */
object InternetArchiveAcquisitionCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.isEmpty())
        println("Internet Archive acquisition check at ${Instant.now()}")
        val observations = mutableListOf<ArchiveHttpEvidence>()
        try {
            InternetArchiveSource(userAgent = EXPERIMENT_USER_AGENT, observe = { synchronized(observations) { observations += it } }).use { source ->
                val page = source.search("identifier:gmb-2015-93040")
                val result = page.publications.single { it.id.localId == "gmb-2015-93040" }
                val publication = checkNotNull(source.getPublication(result.id))
                check(publication.rights == "http://creativecommons.org/publicdomain/zero/1.0/")
                val resource = checkNotNull(selectDemoResource(publication, PublicationFormat.TEXT))
                val evidence = demonstrateAcquisition(DirectResourceLoader(source), resource)
                println("Item=${publication.id.localId}; rights/license=${publication.rights}; file=${resource.key}")
                println("Resource ready: ${evidence.format}; size=${evidence.sizeBytes}; UTF-8 prefix consumed=${evidence.sampledBytes} bytes")
                println("Handle closed. A future reader requires a fresh handle. No graphical UI was run.")
            }
        } finally {
            synchronized(observations) {
                observations.forEach { println("GET ${it.url} HTTP ${it.status}; consumed=${it.consumedBytes.get()} bytes; redirect=${it.location}") }
                println("Requests=${observations.size}; total application-consumed bytes=${observations.sumOf { it.consumedBytes.get() }}; no retries.")
            }
        }
    }
}
