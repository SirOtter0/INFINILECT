// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.network

import io.ktor.client.engine.HttpClientEngine

/** Platform engine only: all source policies remain in the shared adapters. */
internal expect fun platformHttpEngine(): HttpClientEngine
