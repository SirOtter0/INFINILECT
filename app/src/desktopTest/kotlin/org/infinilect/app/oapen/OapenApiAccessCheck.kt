// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import kotlinx.coroutines.runBlocking

/** One opt-in metadata request; never downloads a publication or runs during build. */
object OapenApiAccessCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size <= 1) { "Provide at most one query." }
        OapenApiProbe().use { probe ->
            val result = probe.inspect(args.singleOrNull() ?: "water")
            println("OAPEN REST HTTP ${result.httpStatus}; metadata bytes consumed: ${result.responseBytes}.")
            println("No publication mapping, pagination or file acquisition was attempted. No graphical UI was run.")
            check(result.httpStatus == 200) { "OAPEN API access check failed; obtain access guidance from OAPEN before implementing acquisition." }
            println("Endpoint responded. Publication schema and acquisition still require separate verification.")
        }
    }
}
