// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.cache

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.infinilect.app.androidCacheDirectory

class AndroidCacheDirectoryTest {
    @Test fun usesOnlyTheSuppliedApplicationPrivateCacheDirectory() {
        val privateCache = File("/data/user/0/org.infinilect.app/cache")
        assertEquals(privateCache.toPath().resolve("resource-cache-v1"), androidCacheDirectory(privateCache))
    }
}
