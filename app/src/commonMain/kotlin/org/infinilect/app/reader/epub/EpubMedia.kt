// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infinilect.core.*

internal object EpubImagePolicy {
    const val ENCODED_BYTES = 2 * 1024 * 1024
    const val DIMENSION = 2048
    const val PIXELS = 1_048_576
    const val RETAINED = 2
    fun dimensions(width: Int, height: Int) = width in 1..DIMENSION && height in 1..DIMENSION && width.toLong() * height <= PIXELS
}
// Compatibility aliases keep EPUB contracts/test behavior while the actual decoder is neutral.
internal typealias EpubRaster = org.infinilect.app.media.Raster
internal typealias EpubRasterDecoder = org.infinilect.app.media.RasterDecoder
internal fun defaultEpubRasterDecoder(): EpubRasterDecoder = org.infinilect.app.media.defaultRasterDecoder()
internal sealed interface EpubMediaState {
    data object Unavailable : EpubMediaState
    data class Ready(val raster: EpubRaster) : EpubMediaState
}

/** One serialized decode worker, two visible images, no URI loader or persistent bitmap cache.
 * UI-thread commands; IO work returns to the owning scope before publication.
 */
internal class EpubMediaController(
    private val document: EpubDocument,
    private val scope: CoroutineScope,
    private val decoder: EpubRasterDecoder = defaultEpubRasterDecoder(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow<Map<EpubImage, EpubMediaState>>(emptyMap())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var request: Job? = null
    private var generation = 0L
    private var closed = false
    private var selection = emptyList<EpubImage>()
    internal val retained get() = mutableState.value.size

    fun visible(images: List<EpubImage>) {
        if (closed) return
        val wanted = images.distinct().take(EpubImagePolicy.RETAINED)
        if (wanted == selection) return
        selection = wanted
        val ticket = ++generation
        request?.cancel()
        mutableState.value = mutableState.value.filterKeys { it in wanted }
        request = scope.launch {
            for (image in wanted) {
                if (mutableState.value.containsKey(image)) continue
                val result = try {
                    withTimeout(10_000) {
                        mutex.withLock {
                            withContext(dispatcher) {
                                currentCoroutineContext().ensureActive()
                                require(document.manifest.any { it.path == image.path && it.mediaType == image.mediaType })
                                require(image.mediaType == "image/png" || image.mediaType == "image/jpeg")
                                val content = document.openResource(image.path)
                                val declared = content.sizeBytes
                                val bytes = content.readBytes(EpubImagePolicy.ENCODED_BYTES)
                                require(declared == null || declared == bytes.size.toLong())
                                currentCoroutineContext().ensureActive()
                                EpubMediaState.Ready(decoder.decode(bytes, image.mediaType))
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive(); EpubMediaState.Unavailable
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { currentCoroutineContext().ensureActive(); EpubMediaState.Unavailable }
                currentCoroutineContext().ensureActive()
                if (closed || ticket != generation) return@launch
                mutableState.value = mutableState.value + (image to result)
            }
        }
    }
    fun reset() { generation++; request?.cancel(); selection = emptyList(); mutableState.value = emptyMap() }
    fun close() { if (closed) return; closed = true; reset() }
}
