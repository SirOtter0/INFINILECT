// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports
import java.nio.file.*
import kotlinx.coroutines.runBlocking
import org.infinilect.app.progress.desktopProgressDirectory
import org.infinilect.core.readBytes
import kotlin.test.*
class DesktopLocalImportTest {
    @Test fun pickerSelectionReadsBoundedSourceAndOwnsClose()=runBlocking<Unit> {
        val file=Files.writeString(Files.createTempDirectory("desktop-selection").resolve("name.txt"),"original 🦦")
        val selection=desktopLocalFileSelection(file)
        assertEquals("name.txt",selection.displayName)
        assertContentEquals("original 🦦".encodeToByteArray(),selection.open().readBytes(128))
        Files.delete(file)
        assertFails{selection.open()}
    }
    @Test fun persistentImportPathUsesSafePlatformDataPolicy() {
        fun path(os:String,home:String?,env:Map<String,String>)=desktopProgressDirectory(os,home,env)?.parent?.resolve(IMPORT_DIRECTORY_NAME)
        assertEquals(Paths.get("/data/org.infinilect.app/local-imports-v1"),path("Linux","/users/me",mapOf("XDG_DATA_HOME" to "/data")))
        assertEquals(Paths.get("/users/me/.local/share/org.infinilect.app/local-imports-v1"),path("Linux","/users/me",mapOf("XDG_DATA_HOME" to "relative")))
        assertNull(path("Linux","relative",mapOf("XDG_DATA_HOME" to "relative")))
        assertEquals(Paths.get("/users/me/Library/Application Support/org.infinilect.app/local-imports-v1"),path("Mac OS X","/users/me",emptyMap()))
    }
}
