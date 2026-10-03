// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.Android

internal actual fun platformHttpEngine(): HttpClientEngine = Android.create {
    connectTimeout = 5_000
    socketTimeout = 15_000
    // The engine itself disables HttpURLConnection redirects; source clients also
    // disable Ktor redirects. Never override requestConfig/sslManager permissively.
}
