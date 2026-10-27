// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import kotlinx.coroutines.*

internal fun createDesktopUpdates(directory: Path?, preferences: Path?): UpdateController = UpdateController(GitHubUpdates(directory,object : UpdateInstaller {
    override val installed=InstalledUpdateBuild(INSTALLED_DESKTOP_VERSION,1,false)
    override suspend fun inspect(file: Path): ApkIdentity = updateError(UpdateFailure.INCOMPATIBLE)
    override suspend fun handoff(file: Path): InstallHandoff = updateError(UpdateFailure.INCOMPATIBLE)
    override suspend fun automaticAllowed()=true
    override fun requestPermission()=Unit
    override fun openOfficialPage() {if(!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) updateError(UpdateFailure.INSTALLER);Desktop.getDesktop().browse(URI(OFFICIAL_RELEASES_PAGE))}
},preferencesDirectory=preferences),System::currentTimeMillis)
