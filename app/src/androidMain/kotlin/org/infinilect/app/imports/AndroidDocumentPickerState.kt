// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

/** Only OpenDocument request lifecycle. Bind continuation/launcher changes under this monitor.
 * A launched cancellation must drain its untagged Activity Result before another launch.
 * This state contains no Activity, URI, publication or storage policy. */
class AndroidDocumentPickerState {
    class Request internal constructor()
    private enum class Phase { IDLE, RESERVED, IN_FLIGHT, DRAINING, CLOSED }
    private var phase = Phase.IDLE
    private var request: Request? = null

    @Synchronized fun begin(): Request {
        check(phase == Phase.IDLE) { "Document picker is closed or awaiting a result." }
        return Request().also { request = it; phase = Phase.RESERVED }
    }

    @Synchronized fun launch(token: Request): Boolean {
        if (request !== token || phase != Phase.RESERVED) return false
        phase = Phase.IN_FLIGHT
        return true
    }

    @Synchronized fun cancel(token: Request) {
        if (request !== token) return
        when (phase) {
            Phase.RESERVED -> idle() // No launch/result exists yet.
            Phase.IN_FLIGHT -> phase = Phase.DRAINING
            else -> Unit
        }
    }

    @Synchronized fun result(): Request? = when (phase) {
        Phase.IN_FLIGHT -> request.also { idle() }
        Phase.DRAINING -> { idle(); null }
        else -> null
    }

    @Synchronized fun launchFailed(token: Request): Boolean {
        if (request !== token || phase == Phase.CLOSED) return false
        idle()
        return true
    }

    @Synchronized fun close() { phase = Phase.CLOSED; request = null }
    @Synchronized fun isClosed(): Boolean = phase == Phase.CLOSED
    private fun idle() { phase = Phase.IDLE; request = null }
}
