// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports

import kotlin.test.*

/** Host lifecycle transitions, not ActivityResult/SAF or physical rotation verification. */
class AndroidDocumentPickerStateTest {
    @Test fun oneRequestReservedOrLaunchedAndOneResultConsumed() {
        val state = AndroidDocumentPickerState(); val request = state.begin()
        assertFailsWith<IllegalStateException> { state.begin() }
        assertTrue(state.launch(request)); assertFalse(state.launch(request))
        assertFailsWith<IllegalStateException> { state.begin() }
        assertSame(request, state.result()); assertNull(state.result())
        assertNotSame(request, state.begin())
    }

    @Test fun cancellationBeforeLaunchReleasesReservationWithoutLaunching() {
        val state = AndroidDocumentPickerState(); val cancelled = state.begin()
        state.cancel(cancelled); assertFalse(state.launch(cancelled)); assertNull(state.result())
        val next = state.begin(); assertTrue(state.launch(next))
        state.cancel(cancelled); assertSame(next, state.result())
    }

    @Test fun cancellationAfterLaunchDrainsOldResultBeforeAcceptingAnotherRequest() {
        val state = AndroidDocumentPickerState(); val cancelled = state.begin()
        assertTrue(state.launch(cancelled)); state.cancel(cancelled); state.cancel(cancelled)
        assertFailsWith<IllegalStateException> { state.begin() }
        assertNull(state.result()) // Old URI cannot be delivered to any continuation.
        val next = state.begin(); assertTrue(state.launch(next))
        state.cancel(cancelled); assertFalse(state.launchFailed(cancelled))
        assertSame(next, state.result())
    }

    @Test fun staleCancellationAfterResultCannotCancelNextRequest() {
        val state = AndroidDocumentPickerState(); val first = state.begin()
        state.launch(first); assertSame(first, state.result())
        val next = state.begin(); state.cancel(first); assertFalse(state.launchFailed(first))
        assertTrue(state.launch(next)); assertSame(next, state.result())
    }

    @Test fun launchFailureReleasesActiveOrCancelledLaunch() {
        for (cancel in listOf(false, true)) {
            val state = AndroidDocumentPickerState(); val request = state.begin()
            state.launch(request); if (cancel) state.cancel(request)
            assertTrue(state.launchFailed(request)); assertFalse(state.launchFailed(request))
            assertNotSame(request, state.begin())
        }
    }

    @Test fun closeIsPermanentAndIdempotentFromEveryPhase() {
        for (phase in 0..3) {
            val state = AndroidDocumentPickerState()
            val request = if (phase == 0) null else state.begin()
            if (phase >= 2) state.launch(request!!)
            if (phase == 3) state.cancel(request!!)
            state.close(); state.close(); assertTrue(state.isClosed())
            assertFailsWith<IllegalStateException> { state.begin() }
            assertNull(state.result())
            request?.let {
                state.cancel(it); assertFalse(state.launch(it)); assertFalse(state.launchFailed(it))
            }
            assertTrue(state.isClosed()); assertNull(state.result())
        }
    }
}
