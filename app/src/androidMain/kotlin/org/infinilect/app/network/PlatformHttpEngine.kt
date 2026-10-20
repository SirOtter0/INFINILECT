// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

internal actual fun platformHttpEngine(): HttpClientEngine {
    // Android's HttpURLConnection + Ktor RawSourceChannel can close/discard a body
    // concurrently with its blocking read (C55 AsyncTimeout.enter crash). Use the
    // maintained cancellable-call adapter, keeping policies in shared source clients.
    val calls = Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 }
    val connections = ConnectionPool(2, 5, TimeUnit.MINUTES)
    return OkHttp.create {
        clientCacheSize = 2 // Metadata and thumbnail timeout variants share these resources.
        config {
            dispatcher(calls); connectionPool(connections)
            connectTimeout(5, TimeUnit.SECONDS)
            readTimeout(15, TimeUnit.SECONDS); writeTimeout(15, TimeUnit.SECONDS)
            followRedirects(false); followSslRedirects(false)
            retryOnConnectionFailure(false)
            cache(null); cookieJar(CookieJar.NO_COOKIES)
            // Platform trust and hostname validation remain the OkHttp Android defaults.
        }
    }
}
