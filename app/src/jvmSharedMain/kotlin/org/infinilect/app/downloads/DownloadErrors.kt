// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.downloads

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import javax.net.ssl.SSLException

/** Deliberately excludes the original exception/message/path from the durable journal. */
internal fun downloadError(error: Exception, fallback: DownloadFailure) = if(error is DownloadException) error else
    DownloadException(fallback, technicalCause=when(error) {
        is AccessDeniedException, is SecurityException -> DownloadCause.ACCESS
        is AtomicMoveNotSupportedException -> DownloadCause.ATOMIC_MOVE
        is UnsupportedOperationException -> DownloadCause.UNSUPPORTED_OPERATION
        is UnknownHostException -> DownloadCause.DNS
        is SSLException -> DownloadCause.TLS
        is SocketTimeoutException -> DownloadCause.TIMEOUT
        is IOException -> DownloadCause.IO
        else -> DownloadCause.UNKNOWN
    })
