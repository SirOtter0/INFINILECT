// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import org.infinilect.app.network.createSources
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.cache.desktopCacheDirectory
import org.infinilect.app.progress.desktopProgressDirectory

fun createApplicationSources(): ApplicationSources = createSources(DiskResourceCache(desktopCacheDirectory()), desktopProgressDirectory())
