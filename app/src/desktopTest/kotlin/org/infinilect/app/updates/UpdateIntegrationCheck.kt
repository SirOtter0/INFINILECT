// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import java.nio.file.*
import kotlinx.coroutines.runBlocking

/** Opt-in official metadata only. Not an APK fixture, install or Android test. */
object UpdateIntegrationCheck {
    @JvmStatic fun main(args: Array<String>)=runBlocking {
        val directory=Files.createTempDirectory("infinilect-update-check")
        val backend=GitHubUpdates(directory,object : UpdateInstaller {
            override val installationSupported=false
            override val installed=InstalledUpdateBuild(INSTALLED_DESKTOP_VERSION,1,false)
            override suspend fun inspect(file: Path): ApkIdentity=error("No APK access permitted")
            override suspend fun handoff(file: Path): InstallHandoff=error("No installation permitted")
            override suspend fun automaticAllowed()=false
            override fun requestPermission()=Unit
            override fun openOfficialPage()=Unit
        })
        try {
            val release=backend.latest(UpdateChannel.STABLE)
            println("officialRepository=SirOtter0/INFINILECT; channel=stable; latest=${release?.tag ?: "none"}; trustedInstallation=${backend.trustedInstallationConfigured}; assetRequests=0")
        } finally {backend.close();Files.deleteIfExists(directory)}
    }
}
