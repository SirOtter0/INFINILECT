// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlinx.coroutines.runBlocking
import org.infinilect.app.downloads.verifyOfflineReaders
import kotlin.test.Test

class OfflineDesktopPdfTest {
    @Test fun retainedPdfIsParsedAndRenderedByDesktopPdfBoxWithNetworkUnavailable()=runBlocking<Unit> {verifyOfflineReaders(this,true)}
}
