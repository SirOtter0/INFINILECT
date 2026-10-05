// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import java.nio.file.Path
import kotlin.test.*
import org.infinilect.app.progress.desktopProgressDirectory

class DesktopPageSettingsDirectoryTest {
    private data class Case(val os: String, val home: String, val environment: Map<String, String>, val expected: String)
    @Test fun settingsUsePersistentDataPolicySeparatelyFromCacheAndProgress() {
        for ((os, home, environment, expected) in listOf(
            Case("Linux", "/user", mapOf("XDG_DATA_HOME" to "/data"), "/data/org.infinilect.app"),
            Case("Mac OS X", "/user", emptyMap(), "/user/Library/Application Support/org.infinilect.app"),
            Case("Windows", "/user", mapOf("LOCALAPPDATA" to "/local"), "/local/org.infinilect.app"),
        )) {
            val progress = assertNotNull(desktopProgressDirectory(os, home, environment))
            val settings = progress.parent.resolve(PAGE_SETTINGS_DIRECTORY_NAME)
            assertEquals(Path.of(expected).resolve(PAGE_SETTINGS_DIRECTORY_NAME), settings)
            assertTrue(settings.isAbsolute); assertNotEquals(progress, settings)
        }
    }
    @Test fun relativeEnvironmentCannotTurnPreferencesIntoCwdStorage() {
        val directory = assertNotNull(desktopProgressDirectory("Linux", "/user", mapOf("XDG_DATA_HOME" to "relative"))).parent.resolve(PAGE_SETTINGS_DIRECTORY_NAME)
        assertEquals(Path.of("/user/.local/share/org.infinilect.app/page-reader-preferences-v1"), directory)
        assertNull(desktopProgressDirectory("Linux", "relative", emptyMap()))
    }
}
