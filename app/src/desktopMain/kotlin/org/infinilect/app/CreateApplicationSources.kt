// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import org.infinilect.app.network.createSources
import org.infinilect.app.cache.DiskResourceCache
import org.infinilect.app.cache.desktopCacheDirectory
import org.infinilect.app.progress.desktopProgressDirectory
import org.infinilect.app.collections.*

fun createApplicationSources(): ApplicationSources {
    val progress = desktopProgressDirectory()
    val store = SqlCollectionsStore({ desktopCollectionsDriver(desktopCollectionsFile(progress)) })
    return createSources(DiskResourceCache(desktopCacheDirectory()), progress,
        collections = ApplicationCollections(store.library,store.history,release = store::close),
        developmentEpubEnabled = System.getProperty("infinilect.epubDemo") == "true" || System.getenv("INFINILECT_EPUB_DEMO") == "1")
}
