// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.cache

import java.nio.file.Paths
import kotlin.test.*

class DesktopCacheDirectoryTest {
    @Test fun linuxUsesAbsoluteXdgApplicationNamespace() {
        assertEquals(Paths.get("/private/cache/org.infinilect.app/resource-cache-v1"),
            desktopCacheDirectory("Linux", "/users/me", mapOf("XDG_CACHE_HOME" to "/private/cache")))
    }
    @Test fun relativeXdgFallsBackToHomeNotWorkingTree() {
        assertEquals(Paths.get("/users/me/.cache/org.infinilect.app/resource-cache-v1"),
            desktopCacheDirectory("Linux", "/users/me", mapOf("XDG_CACHE_HOME" to "repo/cache")))
        assertNull(desktopCacheDirectory("Linux", "relative-home", emptyMap()))
    }
    @Test fun macUsesPerUserLibraryCaches() {
        assertEquals(Paths.get("/users/me/Library/Caches/org.infinilect.app/resource-cache-v1"),
            desktopCacheDirectory("Mac OS X", "/users/me", emptyMap()))
    }
    @Test fun windowsSelectsLocalAppDataAndNeverRelativePath() {
        // Test host paths are POSIX; selection is independent of host OS.
        assertEquals(Paths.get("/local/org.infinilect.app/resource-cache-v1"),
            desktopCacheDirectory("Windows 11", "/users/me", mapOf("LOCALAPPDATA" to "/local")))
        assertEquals(Paths.get("/users/me/AppData/Local/org.infinilect.app/resource-cache-v1"),
            desktopCacheDirectory("Windows 11", "/users/me", mapOf("LOCALAPPDATA" to "relative")))
    }
}
