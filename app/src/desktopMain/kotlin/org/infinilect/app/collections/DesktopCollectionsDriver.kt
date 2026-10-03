// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** Uses the same persistent per-user base as progress, never its record/cache directory. */
internal fun desktopCollectionsFile(progressDirectory: Path?): Path? =
    progressDirectory?.takeIf { it.isAbsolute }?.parent?.resolve(COLLECTIONS_DATABASE_NAME)

internal fun desktopCollectionsDriver(file: Path?): SqlDriver {
    require(file != null && file.isAbsolute)
    Files.createDirectories(file.parent)
    require(Files.isDirectory(file.parent, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
    Files.getFileAttributeView(file.parent,PosixFileAttributeView::class.java,LinkOption.NOFOLLOW_LINKS)
        ?.setPermissions(PosixFilePermissions.fromString("rwx------"))
    require(!Files.exists(file) || Files.size(file) <= MAX_COLLECTIONS_DATABASE_BYTES)
    // URI encoding keeps '?'/'#' in a legitimate user directory out of JDBC options.
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.toUri().toASCIIString()}", collectionsJdbcProperties())
    try {
        initializeCollectionsSchema(driver)
        Files.getFileAttributeView(file,PosixFileAttributeView::class.java,LinkOption.NOFOLLOW_LINKS)
            ?.setPermissions(PosixFilePermissions.fromString("rw-------"))
        return driver
    } catch (error: Throwable) { driver.close(); throw error }
}
