// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import java.nio.file.Path
import kotlin.test.*
import org.infinilect.app.cache.desktopCacheDirectory

class DesktopProgressDirectoryTest {
    @Test fun linuxUsesAbsoluteXdgDataHomeSeparatelyFromCache() {
        val env=mapOf("XDG_DATA_HOME" to "/user/data","XDG_CACHE_HOME" to "/user/cache")
        assertEquals(Path.of("/user/data/org.infinilect.app/reading-progress-v1"),desktopProgressDirectory("Linux","/user",env))
        assertNotEquals(desktopCacheDirectory("Linux","/user",env),desktopProgressDirectory("Linux","/user",env))
    }
    @Test fun relativeEnvironmentFallsBackToAbsoluteHome() {
        assertEquals(Path.of("/user/.local/share/org.infinilect.app/reading-progress-v1"),
            desktopProgressDirectory("Linux","/user",mapOf("XDG_DATA_HOME" to "relative")))
    }
    @Test fun unsafeMissingAndInvalidPathsDisablePersistence() {
        assertNull(desktopProgressDirectory("Linux","relative",emptyMap()))
        assertNull(desktopProgressDirectory("Linux",null,emptyMap()))
        assertNull(desktopProgressDirectory("Linux","\u0000",emptyMap()))
    }
    @Test fun macUsesApplicationSupportRatherThanCaches() {
        assertEquals(Path.of("/user/Library/Application Support/org.infinilect.app/reading-progress-v1"),
            desktopProgressDirectory("Mac OS X","/user",emptyMap()))
    }
    @Test fun windowsUsesNamespacedLocalUserData() {
        // Absolute paths are interpreted by the host OS in this pure policy test.
        assertEquals(Path.of("/local/org.infinilect.app/reading-progress-v1"),
            desktopProgressDirectory("Windows 11",null,mapOf("LOCALAPPDATA" to "/local")))
        assertEquals(Path.of("/user/AppData/Local/org.infinilect.app/reading-progress-v1"),
            desktopProgressDirectory("Windows 11","/user",emptyMap()))
    }
}
