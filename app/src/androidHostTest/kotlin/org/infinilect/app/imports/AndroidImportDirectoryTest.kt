// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.imports
import java.io.File
import kotlin.test.*
class AndroidImportDirectoryTest {
    @Test fun durableImportsUsePrivateFilesInsteadOfCache() {
        val path=androidImportDirectory(File("/private/app/files"))
        assertEquals("/private/app/files/local-imports-v1",path.toString());assertFalse(path.toString().contains("cache"))
    }
}
